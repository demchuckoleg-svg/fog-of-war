package com.fogofwar.game.core

import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Authoritative simulation. The host owns one instance and steps it at a fixed rate;
 * clients never simulate gameplay, they only render interpolated snapshots.
 */
class GameWorld(
    val map: TileMap,
    val mapId: Int,
    val seed: Long,
) {
    val tanks = ArrayList<Tank>()
    val bullets = ArrayList<Bullet>()
    val pickups = ArrayList<Pickup>()
    val mines = ArrayList<Mine>()

    /** Tile changes produced since the last drain, streamed to clients. */
    private val pendingTileChanges = ArrayList<TileChange>()

    /** One-shot visual events since the last drain, streamed to clients. */
    private val pendingEffects = ArrayList<EffectEvent>()

    var elapsed = 0f
        private set
    var zoneRadius = GameConfig.ZONE_START_RADIUS
        private set
    var sonarRadius = 0f
        private set
    var sonarActive = false
        private set
    var roundOver = false
        private set
    var winnerSlot = -1
        private set
    var winnerName: String? = null
        private set

    private var sonarTimer = 0f
    private var pickupTimer = 0f
    private var pickupsInitialized = false
    private var lastPickupType = -1

    private val inputs = java.util.concurrent.ConcurrentHashMap<Int, TankInput>()
    private val lastInputNanos = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val bots = HashMap<Int, BotController>()
    private var startedWithOpponents = false
    private val pendingRemovals = java.util.concurrent.ConcurrentLinkedQueue<Int>()
    private val previousDrop = HashMap<Int, Boolean>()
    private val rng = Rng(seed xor 0x5DEECE66DL)

    val centerX: Float get() = GameConfig.WORLD_SIZE / 2f
    val centerY: Float get() = centerX

    fun tank(slot: Int): Tank? = tanks.firstOrNull { it.slot == slot }

    fun aliveCount(): Int = tanks.count { it.alive }

    fun setInput(slot: Int, input: TankInput) {
        inputs[slot] = input.clamped()
        lastInputNanos[slot] = System.nanoTime()
    }

    fun addPlayer(slot: Int, name: String, isBot: Boolean = false): Tank {
        val spawn = map.spawns[slot % map.spawns.size]
        val x = (spawn[0] + 0.5f) * GameConfig.TILE
        val y = (spawn[1] + 0.5f) * GameConfig.TILE
        val dir = atan2(centerY - y, centerX - x)
        val tank = Tank(slot, name, x, y, dir, dir, isBot)
        tanks.add(tank)
        if (tanks.size >= 2) startedWithOpponents = true
        return tank
    }

    fun addBot(slot: Int, name: String): Tank {
        val tank = addPlayer(slot, name, isBot = true)
        bots[slot] = BotController(slot, rng)
        return tank
    }

    fun removeTank(slot: Int) {
        tanks.removeAll { it.slot == slot }
        inputs.remove(slot)
        lastInputNanos.remove(slot)
        bots.remove(slot)
        previousDrop.remove(slot)
    }

    /** Thread-safe removal request, processed at the start of the next tick. */
    fun requestRemove(slot: Int) {
        pendingRemovals.add(slot)
    }

    fun drainTileChanges(): List<TileChange> {
        if (pendingTileChanges.isEmpty()) return emptyList()
        val out = ArrayList(pendingTileChanges)
        pendingTileChanges.clear()
        return out
    }

    fun drainEffects(): List<EffectEvent> {
        if (pendingEffects.isEmpty()) return emptyList()
        val out = ArrayList(pendingEffects)
        pendingEffects.clear()
        return out
    }

    fun step(dt: Float) {
        if (roundOver) return
        var removed = pendingRemovals.poll()
        while (removed != null) {
            removeTank(removed)
            removed = pendingRemovals.poll()
        }
        elapsed += dt

        if (!pickupsInitialized) {
            pickupsInitialized = true
            repeat(3) { spawnPickup() }
        }

        for ((slot, bot) in bots) {
            inputs[slot] = bot.update(this, dt)
        }
        for (tank in tanks) {
            val raw = inputs[tank.slot] ?: TankInput()
            val last = lastInputNanos[tank.slot]
            val stale = last != null &&
                System.nanoTime() - last > GameConfig.INPUT_TIMEOUT_MS * 1_000_000L
            val input = if (stale) TankInput(lights = raw.lights) else raw
            updateTank(tank, input, dt)
        }
        updateBullets(dt)
        updateMines(dt)
        updatePickups(dt)
        updateZone(dt)
        updateSonar(dt)
        checkRoundOver()
    }

    private fun updateTank(tank: Tank, input: TankInput, dt: Float) {
        tank.cooldown = max(0f, tank.cooldown - dt)
        tank.invuln = max(0f, tank.invuln - dt)
        tank.powerTime = max(0f, tank.powerTime - dt)
        tank.lightTime = max(0f, tank.lightTime - dt)
        tank.dropCooldown = max(0f, tank.dropCooldown - dt)
        tank.lightsOn = input.lights
        if (!tank.alive) {
            tank.moving = false
            previousDrop[tank.slot] = input.dropMine
            return
        }

        val startX = tank.x
        val startY = tank.y
        val move = input.clamped()
        val moveLen = hypot(move.moveX, move.moveY)
        if (moveLen > 0.08f) {
            val ux = move.moveX / moveLen
            val uy = move.moveY / moveLen
            tank.bodyDir = lerpAngle(tank.bodyDir, atan2(uy, ux), dt * GameConfig.BODY_TURN_SPEED)

            val speed = GameConfig.TANK_SPEED * moveLen.coerceAtMost(1f) * dt
            val nx = tank.x + ux * speed
            if (map.isFreeForTankBox(nx, tank.y, GameConfig.TANK_HALF)) tank.x = nx
            val ny = tank.y + uy * speed
            if (map.isFreeForTankBox(tank.x, ny, GameConfig.TANK_HALF)) tank.y = ny

            tank.x = tank.x.coerceIn(GameConfig.TILE, GameConfig.WORLD_SIZE - GameConfig.TILE)
            tank.y = tank.y.coerceIn(GameConfig.TILE, GameConfig.WORLD_SIZE - GameConfig.TILE)
        }
        // Sonar stealth and tread animation use real displacement, not a stick held against a wall.
        tank.moving = hypot(tank.x - startX, tank.y - startY) > 0.5f

        // Hull-independent turret: aim stick wins, otherwise lazily recenters while driving.
        if (move.hasAim) {
            tank.turretDir = lerpAngle(tank.turretDir, move.aimAngle(), dt * GameConfig.TURRET_TURN_SPEED)
        } else if (moveLen > 0.08f) {
            tank.turretDir = lerpAngle(tank.turretDir, tank.bodyDir, dt * GameConfig.TURRET_IDLE_TURN_SPEED)
        }

        if (input.fire && tank.cooldown <= 0f) {
            fire(tank)
        }

        val dropEdge = input.dropMine && previousDrop[tank.slot] != true
        previousDrop[tank.slot] = input.dropMine
        if (dropEdge && tank.mines > 0 && tank.dropCooldown <= 0f) {
            dropMine(tank)
        }
    }

    private fun fire(tank: Tank) {
        val powered = tank.isPowered
        tank.cooldown = if (powered) GameConfig.FIRE_COOLDOWN_POWER else GameConfig.FIRE_COOLDOWN
        val speed = if (powered) GameConfig.BULLET_SPEED_POWER else GameConfig.BULLET_SPEED
        val mx = tank.muzzleX()
        val my = tank.muzzleY()
        bullets.add(
            Bullet(
                mx,
                my,
                cos(tank.turretDir) * speed,
                sin(tank.turretDir) * speed,
                tank.slot,
                powered,
            )
        )
        pendingEffects.add(EffectEvent(EffectKind.SHOT, mx, my))
    }

    private fun dropMine(tank: Tank) {
        tank.mines -= 1
        tank.dropCooldown = GameConfig.MINE_DROP_COOLDOWN
        val dist = GameConfig.TANK_HALF + 9f
        val mx = tank.x - cos(tank.bodyDir) * dist
        val my = tank.y - sin(tank.bodyDir) * dist
        val x = if (map.isFreeForTankBox(mx, my, 8f)) mx else tank.x
        val y = if (map.isFreeForTankBox(x, my, 8f)) my else tank.y
        mines.add(Mine(x, y, tank.slot))
    }

    private fun updateBullets(dt: Float) {
        val iterator = bullets.iterator()
        while (iterator.hasNext()) {
            val bullet = iterator.next()
            val frameDistance = (hypot(bullet.vx, bullet.vy)) * dt
            val steps = max(1, ceil(frameDistance / 8f).toInt())
            var removed = false
            for (i in 0 until steps) {
                if (removed) break
                bullet.x += bullet.vx * dt / steps
                bullet.y += bullet.vy * dt / steps
                bullet.travelled += frameDistance / steps

                val tx = floor(bullet.x / GameConfig.TILE).toInt()
                val ty = floor(bullet.y / GameConfig.TILE).toInt()
                if (map.blocksBullet(tx, ty)) {
                    val change = if (bullet.power) map.hitHard(tx, ty) else map.hit(tx, ty)
                    change?.let { pendingTileChanges.add(it) }
                    removed = true
                    break
                }

                var detonatedMine: Mine? = null
                for (mine in mines) {
                    if (hypot(mine.x - bullet.x, mine.y - bullet.y) <= 16f) {
                        detonatedMine = mine
                        break
                    }
                }
                if (detonatedMine != null) {
                    detonateMine(detonatedMine)
                    removed = true
                    break
                }

                for (tank in tanks) {
                    if (!tank.alive || tank.slot == bullet.owner) continue
                    if (tank.invuln > 0f) continue
                    val nearestX = bullet.x.coerceIn(tank.x - GameConfig.TANK_HALF, tank.x + GameConfig.TANK_HALF)
                    val nearestY = bullet.y.coerceIn(tank.y - GameConfig.TANK_HALF, tank.y + GameConfig.TANK_HALF)
                    val dx = bullet.x - nearestX
                    val dy = bullet.y - nearestY
                    if (dx * dx + dy * dy <= GameConfig.BULLET_RADIUS * GameConfig.BULLET_RADIUS) {
                        damage(tank, bullet.owner, ignoreInvuln = false)
                        removed = true
                        break
                    }
                }

                if (bullet.travelled > GameConfig.BULLET_MAX_RANGE) removed = true
            }
            if (removed) iterator.remove()
        }
    }

    private fun updateMines(dt: Float) {
        for (mine in mines) {
            if (mine.armed) continue
            mine.armTime += dt
            if (mine.armTime >= GameConfig.MINE_ARM_TIME) mine.armed = true
        }
        val snapshot = ArrayList(mines)
        for (mine in snapshot) {
            if (!mine.armed) continue
            for (tank in tanks) {
                if (!tank.alive || tank.slot == mine.owner) continue
                if (hypot(tank.x - mine.x, tank.y - mine.y) <= GameConfig.MINE_TRIGGER_RADIUS) {
                    detonateMine(mine)
                    break
                }
            }
        }
    }

    /** Detonates a mine, destroys brick around it, damages tanks and chains nearby mines. */
    fun detonateMine(first: Mine) {
        val work = ArrayList<Mine>()
        work.add(first)
        while (work.isNotEmpty()) {
            val mine = work.removeAt(work.size - 1)
            if (!mines.remove(mine)) continue
            pendingEffects.add(EffectEvent(EffectKind.EXPLOSION, mine.x, mine.y, GameConfig.MINE_BLAST_RADIUS))

            val tx0 = floor((mine.x - GameConfig.MINE_BLAST_RADIUS) / GameConfig.TILE).toInt()
            val tx1 = floor((mine.x + GameConfig.MINE_BLAST_RADIUS) / GameConfig.TILE).toInt()
            val ty0 = floor((mine.y - GameConfig.MINE_BLAST_RADIUS) / GameConfig.TILE).toInt()
            val ty1 = floor((mine.y + GameConfig.MINE_BLAST_RADIUS) / GameConfig.TILE).toInt()
            for (ty in ty0..ty1) {
                for (tx in tx0..tx1) {
                    val tile = map.tileAt(tx, ty)
                    if (tile != Tile.BRICK && tile != Tile.CRACKED) continue
                    val cx = (tx + 0.5f) * GameConfig.TILE
                    val cy = (ty + 0.5f) * GameConfig.TILE
                    if (hypot(cx - mine.x, cy - mine.y) > GameConfig.MINE_BLAST_RADIUS) continue
                    map.hitHard(tx, ty)?.let { pendingTileChanges.add(it) }
                }
            }

            for (tank in tanks) {
                if (!tank.alive || tank.slot == mine.owner) continue
                if (hypot(tank.x - mine.x, tank.y - mine.y) <= GameConfig.MINE_BLAST_RADIUS + GameConfig.TANK_HALF) {
                    damage(tank, mine.owner, ignoreInvuln = false)
                }
            }

            for (other in ArrayList(mines)) {
                if (hypot(other.x - mine.x, other.y - mine.y) <= GameConfig.MINE_BLAST_RADIUS) {
                    work.add(other)
                }
            }
        }
    }

    private fun updatePickups(dt: Float) {
        if (pickups.size < GameConfig.MAX_PICKUPS) {
            pickupTimer += dt
            if (pickupTimer >= GameConfig.PICKUP_INTERVAL) {
                pickupTimer = 0f
                spawnPickup()
            }
        }
        for (pickup in ArrayList(pickups)) {
            for (tank in tanks) {
                if (!tank.alive) continue
                if (hypot(tank.x - pickup.x, tank.y - pickup.y) <= GameConfig.PICKUP_RADIUS) {
                    if (pickup.type == GameConfig.PICKUP_REPAIR && tank.hp >= GameConfig.MAX_HP) continue
                    applyPickup(tank, pickup)
                    pickups.remove(pickup)
                    break
                }
            }
        }
    }

    private fun applyPickup(tank: Tank, pickup: Pickup) {
        when (pickup.type) {
            GameConfig.PICKUP_REPAIR -> tank.hp = min(GameConfig.MAX_HP, tank.hp + 1)
            GameConfig.PICKUP_POWER -> tank.powerTime = GameConfig.POWER_DURATION
            GameConfig.PICKUP_LIGHT -> tank.lightTime = GameConfig.LIGHT_BOOST_DURATION
        }
        pendingEffects.add(EffectEvent(EffectKind.PICKUP, pickup.x, pickup.y))
    }

    private fun spawnPickup() {
        for (attempt in 0 until 80) {
            val tx = rng.nextInt(3, map.width - 3)
            val ty = rng.nextInt(3, map.height - 3)
            val x = (tx + 0.5f) * GameConfig.TILE
            val y = (ty + 0.5f) * GameConfig.TILE
            if (!map.isFreeForTankBox(x, y, 12f)) continue
            if (hypot(x - centerX, y - centerY) > (zoneRadius * 0.8f).coerceAtLeast(60f)) continue
            if (tanks.any { it.distanceTo(x, y) < 170f }) continue
            if (pickups.any { hypot(it.x - x, it.y - y) < 150f }) continue
            if (mines.any { hypot(it.x - x, it.y - y) < 90f }) continue
            // Avoid re-rolling the same type twice in a row too often.
            var type = when (rng.nextInt(10)) {
                in 0..3 -> GameConfig.PICKUP_REPAIR
                in 4..7 -> GameConfig.PICKUP_POWER
                else -> GameConfig.PICKUP_LIGHT
            }
            if (type == lastPickupType) type = (type + 1) % 3
            lastPickupType = type
            pickups.add(Pickup(x, y, type))
            return
        }
    }

    private fun updateZone(dt: Float) {
        if (elapsed > GameConfig.ZONE_SHRINK_DELAY) {
            zoneRadius = max(GameConfig.ZONE_MIN_RADIUS, zoneRadius - GameConfig.ZONE_SHRINK_SPEED * dt)
        }
        for (tank in tanks) {
            if (!tank.alive) continue
            val dist = hypot(tank.x - centerX, tank.y - centerY)
            if (dist > zoneRadius) {
                tank.outsideTimer += dt
                if (tank.outsideTimer >= GameConfig.ZONE_DAMAGE_INTERVAL) {
                    tank.outsideTimer = 0f
                    damage(tank, -1, ignoreInvuln = true)
                }
            } else {
                tank.outsideTimer = 0f
            }
        }
    }

    private fun updateSonar(dt: Float) {
        if (!sonarActive) {
            sonarTimer += dt
            if (sonarTimer >= GameConfig.SONAR_PAUSE) {
                sonarTimer = 0f
                sonarActive = true
                sonarRadius = 0f
            }
        } else {
            sonarRadius += GameConfig.SONAR_SPEED * dt
            if (sonarRadius >= GameConfig.SONAR_MAX_RADIUS) {
                sonarActive = false
                sonarRadius = 0f
                sonarTimer = 0f
            }
        }
    }

    fun damage(tank: Tank, sourceSlot: Int, ignoreInvuln: Boolean) {
        if (!tank.alive) return
        if (!ignoreInvuln && tank.invuln > 0f) return
        tank.hp -= 1
        tank.lastDamageFrom = sourceSlot
        if (!ignoreInvuln) tank.invuln = GameConfig.INVULN_TIME
        if (tank.hp <= 0) {
            tank.hp = 0
            tank.alive = false
            pendingEffects.add(EffectEvent(EffectKind.EXPLOSION, tank.x, tank.y, 54f))
        }
    }

    private fun checkRoundOver() {
        if (roundOver) return
        if (tanks.size < 2 && !startedWithOpponents) return
        val alive = tanks.filter { it.alive }
        if (alive.size <= 1) {
            roundOver = true
            val winner = alive.firstOrNull()
            winnerSlot = winner?.slot ?: -1
            winnerName = winner?.name
        }
    }

    fun nearestEnemy(tank: Tank, maxDistance: Float): Tank? {
        var best: Tank? = null
        var bestDist = maxDistance
        for (other in tanks) {
            if (other === tank || !other.alive) continue
            val d = tank.distanceTo(other.x, other.y)
            if (d < bestDist) {
                bestDist = d
                best = other
            }
        }
        return best
    }

    fun hasLineOfSight(ax: Float, ay: Float, bx: Float, by: Float): Boolean {
        val dx = bx - ax
        val dy = by - ay
        val dist = hypot(dx, dy)
        if (dist < 1f) return true
        val steps = (dist / 10f).toInt() + 1
        for (i in 1 until steps) {
            val t = i.toFloat() / steps
            if (map.blocksLightAtWorld(ax + dx * t, ay + dy * t)) return false
        }
        return true
    }
}
