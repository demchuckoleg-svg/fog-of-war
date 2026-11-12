package com.fogofwar.game.core

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
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

    /** Tile changes produced since the last drain, streamed to clients. */
    private val pendingTileChanges = ArrayList<TileChange>()

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
    private val inputs = java.util.concurrent.ConcurrentHashMap<Int, TankInput>()
    private val bots = HashMap<Int, BotController>()
    private val pendingRemovals = java.util.concurrent.ConcurrentLinkedQueue<Int>()
    private val rng = Rng(seed xor 0x5DEECE66DL)

    val centerX: Float get() = GameConfig.WORLD_SIZE / 2f
    val centerY: Float get() = centerX

    fun tank(slot: Int): Tank? = tanks.firstOrNull { it.slot == slot }

    fun aliveCount(): Int = tanks.count { it.alive }

    fun setInput(slot: Int, input: TankInput) {
        inputs[slot] = input.clamped()
    }

    fun addPlayer(slot: Int, name: String): Tank {
        val spawn = map.spawns[slot % map.spawns.size]
        val x = (spawn[0] + 0.5f) * GameConfig.TILE
        val y = (spawn[1] + 0.5f) * GameConfig.TILE
        val tank = Tank(slot, name, x, y, kotlin.math.atan2(centerY - y, centerX - x))
        tanks.add(tank)
        return tank
    }

    fun addBot(slot: Int, name: String): Tank {
        val tank = addPlayer(slot, name)
        bots[slot] = BotController(slot, rng)
        return tank
    }

    fun removeTank(slot: Int) {
        tanks.removeAll { it.slot == slot }
        inputs.remove(slot)
        bots.remove(slot)
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

    fun step(dt: Float) {
        if (roundOver) return
        var removed = pendingRemovals.poll()
        while (removed != null) {
            removeTank(removed)
            removed = pendingRemovals.poll()
        }
        elapsed += dt

        for ((slot, bot) in bots) {
            inputs[slot] = bot.update(this, dt)
        }
        for (tank in tanks) {
            updateTank(tank, inputs[tank.slot] ?: TankInput(), dt)
        }
        updateBullets(dt)
        updateZone(dt)
        updateSonar(dt)
        checkRoundOver()
    }

    private fun updateTank(tank: Tank, input: TankInput, dt: Float) {
        tank.cooldown = max(0f, tank.cooldown - dt)
        tank.invuln = max(0f, tank.invuln - dt)
        if (!tank.alive) return

        val move = input.clamped()
        if (move.isMoving) {
            val len = hypot(move.moveX, move.moveY).coerceAtMost(1f)
            val ux = move.moveX / hypot(move.moveX, move.moveY)
            val uy = move.moveY / hypot(move.moveX, move.moveY)
            tank.dir = kotlin.math.atan2(uy, ux)

            val speed = GameConfig.TANK_SPEED * len * dt
            val nx = tank.x + ux * speed
            if (map.isFreeForTankBox(nx, tank.y, GameConfig.TANK_HALF)) tank.x = nx
            val ny = tank.y + uy * speed
            if (map.isFreeForTankBox(tank.x, ny, GameConfig.TANK_HALF)) tank.y = ny

            tank.x = tank.x.coerceIn(GameConfig.TILE, GameConfig.WORLD_SIZE - GameConfig.TILE)
            tank.y = tank.y.coerceIn(GameConfig.TILE, GameConfig.WORLD_SIZE - GameConfig.TILE)
        }

        if (input.fire && tank.cooldown <= 0f) {
            tank.cooldown = GameConfig.FIRE_COOLDOWN
            val muzzle = GameConfig.TANK_HALF + 7f
            bullets.add(
                Bullet(
                    tank.x + cos(tank.dir) * muzzle,
                    tank.y + sin(tank.dir) * muzzle,
                    cos(tank.dir) * GameConfig.BULLET_SPEED,
                    sin(tank.dir) * GameConfig.BULLET_SPEED,
                    tank.slot,
                )
            )
        }
    }

    private fun updateBullets(dt: Float) {
        val iterator = bullets.iterator()
        while (iterator.hasNext()) {
            val bullet = iterator.next()
            val frameDistance = GameConfig.BULLET_SPEED * dt
            val steps = max(1, ceil(frameDistance / 8f).toInt())
            var removed = false
            for (i in 0 until steps && !removed) {
                bullet.x += bullet.vx * dt / steps
                bullet.y += bullet.vy * dt / steps
                bullet.travelled += frameDistance / steps

                val tx = floor(bullet.x / GameConfig.TILE).toInt()
                val ty = floor(bullet.y / GameConfig.TILE).toInt()
                if (map.blocksBullet(tx, ty)) {
                    map.hit(tx, ty)?.let { pendingTileChanges.add(it) }
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
        }
    }

    private fun checkRoundOver() {
        if (roundOver || tanks.size < 2) return
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
