package com.fogofwar.game.core

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Simple, cheap bot: drives to the safe zone (or at a visible enemy), shoots when
 * the line of sight is clear, and wiggles sideways when it gets stuck on a wall.
 */
class BotController(private val slot: Int, private val rng: Rng) {
    private var targetX = -1f
    private var targetY = -1f
    private var retargetIn = 0f
    private var detourTime = 0f
    private var detourSign = 1f
    private var stuckCheckIn = 0.6f
    private var lastX = 0f
    private var lastY = 0f
    private var initialized = false

    fun update(world: GameWorld, dt: Float): TankInput {
        val me = world.tank(slot) ?: return TankInput()
        if (!me.alive) return TankInput()
        if (!initialized) {
            lastX = me.x
            lastY = me.y
            initialized = true
        }

        val cx = GameConfig.WORLD_SIZE / 2f
        val cy = cx
        // Bots respect the same fog as humans: they only notice enemies in front of them.
        val enemy = world.nearestEnemy(me, 700f)?.takeIf { candidate ->
            val dx = candidate.x - me.x
            val dy = candidate.y - me.y
            val dist = hypot(dx, dy)
            if (dist < 110f) true
            else dist < 360f && Math.abs(angleDelta(me.dir, atan2(dy, dx))) < 1.1f
        }
        val outside = hypot(me.x - cx, me.y - cy) > world.zoneRadius - 90f
        retargetIn -= dt

        if (outside) {
            if (retargetIn <= 0f) {
                targetX = cx + rng.nextInt(-70, 71)
                targetY = cy + rng.nextInt(-70, 71)
                retargetIn = 0.7f
            }
        } else if (enemy != null && retargetIn <= 0f) {
            targetX = enemy.x
            targetY = enemy.y
            retargetIn = 0.45f
        } else if (targetX < 0f || retargetIn <= 0f) {
            val spread = (world.zoneRadius * 0.45f).coerceAtLeast(80f)
            targetX = cx + rng.nextInt(-spread.toInt(), spread.toInt() + 1)
            targetY = cy + rng.nextInt(-spread.toInt(), spread.toInt() + 1)
            retargetIn = 1.4f + rng.nextFloat()
        }

        var mx = targetX - me.x
        var my = targetY - me.y
        var len = hypot(mx, my)
        if (len < 20f) {
            // Reached the target: pick a new one next tick.
            retargetIn = 0f
            targetX = -1f
            mx = 0f
            my = 0f
            len = 0f
        } else {
            mx /= len
            my /= len
        }

        if (detourTime > 0f) {
            val dx = mx
            mx = -my * detourSign
            my = dx * detourSign
            detourTime -= dt
        }

        // Stuck on a wall -> sidestep for a moment.
        stuckCheckIn -= dt
        if (stuckCheckIn <= 0f) {
            val moved = hypot(me.x - lastX, me.y - lastY)
            if (moved < 12f && len > 0f && detourTime <= 0f) {
                detourTime = 0.75f
                detourSign = if (rng.nextFloat() < 0.5f) -1f else 1f
            }
            lastX = me.x
            lastY = me.y
            stuckCheckIn = 0.55f
        }

        var fire = false
        if (enemy != null && len > 0f) {
            val dist = hypot(enemy.x - me.x, enemy.y - me.y)
            val aim = atan2(my, mx)
            val facingDiff = Math.abs(angleDelta(me.dir, aim))
            if (dist < 430f && facingDiff < 0.32f && world.hasLineOfSight(me.x, me.y, enemy.x + 0.01f, enemy.y)) {
                fire = true
            }
        }
        return TankInput(mx, my, fire)
    }
}
