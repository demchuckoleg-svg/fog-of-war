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
    val rules: RoundRules = RoundRules.CLASSIC,
) {
    val tanks = ArrayList<Tank>()
    val bullets = ArrayList<Bullet>()
    val pickups = ArrayList<Pickup>()
    val grenades = ArrayList<Grenade>()

    /** Tile changes produced since the last drain, streamed to clients. */
    private val pendingTileChanges = ArrayList<TileChange>()

    /** One-shot visual events since the last drain, streamed to clients. */
    private val pendingEffects = ArrayList<EffectEvent>()

    /** Deaths since the last drain, streamed to clients for the kill feed. */
    private val pendingKills = ArrayList<KillEvent>()

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
    var winnerTeam = -1
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
    private val previousThrow = HashMap<Int, Boolean>()
    private val rng = Rng(seed xor 0x5DEECE66DL)

    val centerX: Float get() = GameConfig.WORLD_SIZE / 2f
    val centerY: Float get() = centerX

    fun tank(slot: Int): Tank? = tanks.firstOrNull { it.slot == slot }

    fun setInput(slot: Int, input: TankInput) {
        inputs[slot] = input.clamped()
        lastInputNanos[slot] = System.nanoTime()
    }

    fun addPlayer(slot: Int, name: String, isBot: Boolean = false, team: Int = 0): Tank {
        val spawn = map.spawns[slot % map.spawns.size]
        val x = (spawn[0] + 0.5f) * GameConfig.TILE
        val y = (spawn[1] + 0.5f) * GameConfig.TILE
        val dir = atan2(centerY - y, centerX - x)
        val tank = Tank(slot, name, x, y, dir, dir, isBot, team, rules.lives)
        tanks.add(tank)
        if (tanks.size >= 2) startedWithOpponents = true
        return tank
    }

    fun addBot(slot: Int, name: String, team: Int = 0): Tank {
        val tank = addPlayer(slot, name, isBot = true, team = team)
        bots[slot] = BotController(slot, rng)
        return tank
    }

    fun removeTank(slot: Int) {
        tanks.removeAll { it.slot == slot }
        inputs.remove(slot)
        lastInputNanos.remove(slot)
        bots.remove(slot)
        previousThrow.remove(slot)
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

    fun drainKills(): List<KillEvent> {
        if (pendingKills.isEmpty()) return emptyList()
        val out = ArrayList(pendingKills)
        pendingKills.clear()
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
            if (rules.pickups) repeat(3) { spawnPickup() }
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
        resolveTankCollisions()
        updateBullets(dt)
        updateGrenades(dt)
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
        tank.throwCooldown = max(0f, tank.throwCooldown - dt)
        tank.lightsOn = input.lights
        if (!tank.alive) {
            tank.moving = false
            previousThrow[tank.slot] = input.throwGrenade
            if (tank.respawnTimer > 0f) {
                tank.respawnTimer -= dt
                if (tank.respawnTimer <= 0f && tank.lives > 0) respawnTank(tank)
            }
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

        if (tank.grenades < GameConfig.GRENADE_CHARGES) {
            tank.grenadeRecharge += dt
            if (tank.grenadeRecharge >= GameConfig.GRENADE_RECHARGE_TIME) {
                tank.grenadeRecharge = 0f
                tank.grenades = (tank.grenades + 1).coerceAtMost(GameConfig.GRENADE_CHARGES)
            }
        } else {
            tank.grenadeRecharge = 0f
        }

        // Hull-independent turret: aim stick wins, otherwise lazily recenters while driving.
        if (move.hasAim) {
            // Less sensitive near the centre: small deflections turn the turret slowly.
            val responsiveness = 0.4f + 0.6f * move.aimMagnitude.coerceIn(0f, 1f)
            tank.turretDir = lerpAngle(
                tank.turretDir,
                move.aimAngle(),
                dt * GameConfig.TURRET_TURN_SPEED * responsiveness,
            )
        } else if (moveLen > 0.08f) {
            tank.turretDir = lerpAngle(tank.turretDir, tank.bodyDir, dt * GameConfig.TURRET_IDLE_TURN_SPEED)
        }

        if (input.fire && tank.cooldown <= 0f) {
            // A released/tapped stick must shoot exactly where the player aimed, not where the
            // slowly rotating turret happened to be.
            if (move.hasAim) tank.turretDir = move.aimAngle()
            fire(tank)
        }

        val throwEdge = input.throwGrenade && previousThrow[tank.slot] != true
        previousThrow[tank.slot] = input.throwGrenade
        if (throwEdge && tank.grenades > 0 && tank.throwCooldown <= 0f) {
            throwGrenade(tank)
        }
    }

    /** Keeps tanks physically apart so they cannot sit inside each other. */
    private fun resolveTankCollisions() {
        val minDist = GameConfig.TANK_HALF * 2f
        repeat(2) {
            for (i in tanks.indices) {
                val a = tanks[i]
                if (!a.alive) continue
                for (j in i + 1 until tanks.size) {
                    val b = tanks[j]
                    if (!b.alive) continue
                    var dx = b.x - a.x
                    var dy = b.y - a.y
                    var dist = hypot(dx, dy)
                    if (dist >= minDist) continue
                    if (dist < 0.01f) {
                        dx = 1f
                        dy = 0f
                        dist = 1f
                    }
                    val ux = dx / dist
                    val uy = dy / dist
                    val half = (minDist - dist) * 0.5f
                    val movedA = pushTank(a, -ux * half, -uy * half)
                    val movedB = pushTank(b, ux * half, uy * half)
                    if (movedA && !movedB) pushTank(a, -ux * half, -uy * half)
                    if (!movedA && movedB) pushTank(b, ux * half, uy * half)
                }
            }
        }
    }

    private fun pushTank(tank: Tank, dx: Float, dy: Float): Boolean {
        var moved = false
        if (dx != 0f) {
            val nx = tank.x + dx
            if (map.isFreeForTankBox(nx, tank.y, GameConfig.TANK_HALF)) {
                tank.x = nx
                moved = true
            }
        }
        if (dy != 0f) {
            val ny = tank.y + dy
            if (map.isFreeForTankBox(tank.x, ny, GameConfig.TANK_HALF)) {
                tank.y = ny
                moved = true
            }
        }
        return moved
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

    private fun throwGrenade(tank: Tank) {
        tank.grenades -= 1
        tank.throwCooldown = GameConfig.GRENADE_THROW_COOLDOWN
        val startDist = GameConfig.TANK_HALF + 6f
        val startX = tank.x + cos(tank.turretDir) * startDist
        val startY = tank.y + sin(tank.turretDir) * startDist
        val targetX = (startX + cos(tank.turretDir) * GameConfig.GRENADE_THROW_RANGE)
            .coerceIn(GameConfig.TILE, GameConfig.WORLD_SIZE - GameConfig.TILE)
        val targetY = (startY + sin(tank.turretDir) * GameConfig.GRENADE_THROW_RANGE)
            .coerceIn(GameConfig.TILE, GameConfig.WORLD_SIZE - GameConfig.TILE)
        Grenade(startX, startY, tank.slot).also {
            it.launch(startX, startY, targetX, targetY)
            grenades.add(it)
        }
        pendingEffects.add(EffectEvent(EffectKind.SHOT, startX, startY))
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

                var detonatedGrenade: Grenade? = null
                for (grenade in grenades) {
                    if (!grenade.landed) continue
                    if (hypot(grenade.x - bullet.x, grenade.y - bullet.y) <= 16f) {
                        detonatedGrenade = grenade
                        break
                    }
                }
                if (detonatedGrenade != null) {
                    detonateGrenade(detonatedGrenade)
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

    private fun updateGrenades(dt: Float) {
        for (grenade in ArrayList(grenades)) {
            if (!grenade.landed) {
                grenade.flight = (grenade.flight + dt / GameConfig.GRENADE_FLIGHT_TIME)
                    .coerceAtMost(1f)
                grenade.x = grenade.startX + (grenade.targetX - grenade.startX) * grenade.flight
                grenade.y = grenade.startY + (grenade.targetY - grenade.startY) * grenade.flight
                if (grenade.flight >= 1f) {
                    grenade.landed = true
                    grenade.fuse = GameConfig.GRENADE_FUSE_TIME
                }
                continue
            }
            grenade.fuse -= dt
            if (grenade.fuse <= 0f) detonateGrenade(grenade)
        }
    }

    /** Detonates a grenade, destroys brick around it, damages tanks and chains nearby grenades. */
    fun detonateGrenade(first: Grenade) {
        val work = ArrayList<Grenade>()
        work.add(first)
        while (work.isNotEmpty()) {
            val grenade = work.removeAt(work.size - 1)
            if (!grenades.remove(grenade)) continue
            pendingEffects.add(EffectEvent(EffectKind.EXPLOSION, grenade.x, grenade.y, GameConfig.GRENADE_BLAST_RADIUS))

            val tx0 = floor((grenade.x - GameConfig.GRENADE_BLAST_RADIUS) / GameConfig.TILE).toInt()
            val tx1 = floor((grenade.x + GameConfig.GRENADE_BLAST_RADIUS) / GameConfig.TILE).toInt()
            val ty0 = floor((grenade.y - GameConfig.GRENADE_BLAST_RADIUS) / GameConfig.TILE).toInt()
            val ty1 = floor((grenade.y + GameConfig.GRENADE_BLAST_RADIUS) / GameConfig.TILE).toInt()
            for (ty in ty0..ty1) {
                for (tx in tx0..tx1) {
                    val tile = map.tileAt(tx, ty)
                    if (tile != Tile.BRICK && tile != Tile.CRACKED) continue
                    val cx = (tx + 0.5f) * GameConfig.TILE
                    val cy = (ty + 0.5f) * GameConfig.TILE
                    if (hypot(cx - grenade.x, cy - grenade.y) > GameConfig.GRENADE_BLAST_RADIUS) continue
                    map.hitHard(tx, ty)?.let { pendingTileChanges.add(it) }
                }
            }

            for (tank in tanks) {
                if (!tank.alive || tank.slot == grenade.owner) continue
                if (hypot(tank.x - grenade.x, tank.y - grenade.y) <= GameConfig.GRENADE_BLAST_RADIUS + GameConfig.TANK_HALF) {
                    damage(tank, grenade.owner, ignoreInvuln = false)
                }
            }

            for (other in ArrayList(grenades)) {
                if (hypot(other.x - grenade.x, other.y - grenade.y) <= GameConfig.GRENADE_BLAST_RADIUS) {
                    work.add(other)
                }
            }
        }
    }

    private fun updatePickups(dt: Float) {
        if (!rules.pickups) return
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
            if (grenades.any { hypot(it.x - x, it.y - y) < 90f }) continue
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
        val source = if (sourceSlot >= 0) tank(sourceSlot) else null
        // Team mode: allies cannot hurt each other with bullets, grenades or mines.
        if (rules.teams && source != null && source.team == tank.team) return
        tank.hp -= 1
        tank.lastDamageFrom = sourceSlot
        if (source != null) source.damageDealt += 1
        if (!ignoreInvuln) tank.invuln = GameConfig.INVULN_TIME
        if (tank.hp <= 0) {
            tank.hp = 0
            tank.alive = false
            tank.deaths += 1
            if (source != null) source.kills += 1
            pendingKills.add(KillEvent(sourceSlot, tank.slot))
            if (tank.lives > 1) {
                tank.lives -= 1
                tank.respawnTimer = GameConfig.RESPAWN_DELAY
            } else {
                tank.lives = 0
                tank.respawnTimer = 0f
            }
            pendingEffects.add(EffectEvent(EffectKind.EXPLOSION, tank.x, tank.y, 54f))
        }
    }

    private fun respawnTank(tank: Tank) {
        tank.x = tank.spawnX
        tank.y = tank.spawnY
        val dir = atan2(centerY - tank.y, centerX - tank.x)
        tank.bodyDir = dir
        tank.turretDir = dir
        tank.hp = GameConfig.MAX_HP
        tank.alive = true
        tank.invuln = GameConfig.RESPAWN_INVULN
        tank.cooldown = 0f
        tank.outsideTimer = 0f
        tank.respawnTimer = 0f
        tank.powerTime = 0f
        tank.lightTime = 0f
        tank.lastDamageFrom = -1
    }

    private fun checkRoundOver() {
        if (roundOver) return
        if (rules.timeLimitSec > 0 && elapsed >= rules.timeLimitSec) {
            finishByScore()
            return
        }
        if (tanks.size < 2 && !startedWithOpponents) return
        val remaining = tanks.filter { it.lives > 0 }
        if (rules.teams) {
            val teamsLeft = remaining.map { it.team }.toSet()
            if (teamsLeft.size <= 1) finishRound(remaining.firstOrNull())
        } else if (remaining.size <= 1) {
            finishRound(remaining.firstOrNull())
        }
    }

    private fun finishRound(winner: Tank?) {
        if (roundOver) return
        roundOver = true
        winnerSlot = winner?.slot ?: -1
        winnerTeam = if (rules.teams) winner?.team ?: -1 else -1
        winnerName = when {
            winner == null -> null
            rules.teams -> GameConfig.TEAM_NAMES.getOrElse(winner.team) { "Команда" }
            else -> winner.name
        }
    }

    /** Time is up: most kills, then damage, then lives; a tie is a draw. */
    private fun finishByScore() {
        if (rules.teams) {
            val stats = HashMap<Int, IntArray>()
            for (tank in tanks) {
                val entry = stats.getOrPut(tank.team) { IntArray(2) }
                entry[0] += tank.kills
                entry[1] += tank.damageDealt
            }
            val best = stats.entries.maxWithOrNull(
                compareBy<Map.Entry<Int, IntArray>> { it.value[0] }
                    .thenBy { it.value[1] }
            ) ?: return finishRound(null)
            val tied = stats.values.count {
                it[0] == best.value[0] && it[1] == best.value[1]
            } > 1
            if (tied) return finishRound(null)
            val representative = tanks.filter { it.team == best.key }
                .maxWithOrNull(
                    compareByDescending<Tank> { it.kills }
                        .thenByDescending { it.damageDealt }
                )
            finishRound(representative)
        } else {
            val comparator = compareByDescending<Tank> { it.kills }
                .thenByDescending { it.damageDealt }
                .thenByDescending { it.lives }
            val best = tanks.maxWithOrNull(comparator) ?: return finishRound(null)
            val tied = tanks.count { comparator.compare(it, best) == 0 } > 1
            finishRound(if (tied) null else best)
        }
    }

    fun nearestEnemy(tank: Tank, maxDistance: Float): Tank? {
        var best: Tank? = null
        var bestDist = maxDistance
        for (other in tanks) {
            if (other === tank || !other.alive) continue
            if (rules.teams && other.team == tank.team) continue
            val d = tank.distanceTo(other.x, other.y)
            if (d < bestDist) {
                bestDist = d
                best = other
            }
        }
        return best
    }

    fun hasLineOfSight(ax: Float, ay: Float, bx: Float, by: Float): Boolean =
        !map.blocksLightAlong(ax, ay, bx, by)
}
