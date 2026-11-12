package com.fogofwar.game.core

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Input coming from two sticks and the action buttons.
 * moveX/moveY drive the hull, aimX/aimY rotate the turret and headlight.
 * aim magnitude is preserved (0..1); the client fires only when the stick reaches the ring.
 */
data class TankInput(
    val moveX: Float = 0f,
    val moveY: Float = 0f,
    val aimX: Float = 0f,
    val aimY: Float = 0f,
    val fire: Boolean = false,
    val lights: Boolean = true,
    val dropMine: Boolean = false,
) {
    /** Clamps both sticks to a unit circle (diagonal is not faster). */
    fun clamped(): TankInput {
        val moveLen = hypot(moveX, moveY)
        val aimLen = hypot(aimX, aimY)
        var mx = moveX
        var my = moveY
        var ax = aimX
        var ay = aimY
        if (moveLen > 1f) {
            mx /= moveLen
            my /= moveLen
        }
        if (aimLen > 1f) {
            ax /= aimLen
            ay /= aimLen
        }
        return TankInput(mx, my, ax, ay, fire, lights, dropMine)
    }

    val hasAim: Boolean get() = hypot(aimX, aimY) > GameConfig.AIM_DEADZONE
    val aimMagnitude: Float get() = hypot(aimX, aimY)
    fun aimAngle(): Float = atan2(aimY, aimX)
}class Tank(
    val slot: Int,
    var name: String,
    var x: Float,
    var y: Float,
    var bodyDir: Float,
    var turretDir: Float,
    val isBot: Boolean = false,
) {
    var hp: Int = GameConfig.MAX_HP
    var alive: Boolean = true
    var cooldown: Float = 0f
    var invuln: Float = 0f
    var outsideTimer: Float = 0f
    var lastDamageFrom: Int = -1
    var lightsOn: Boolean = true
    var moving: Boolean = false

    /** Buff timers driven by pickups. */
    var powerTime: Float = 0f
    var lightTime: Float = 0f

    var mines: Int = GameConfig.MINE_CHARGES
    var dropCooldown: Float = 0f
    /** Seconds accumulated toward the next mine charge while below max. */
    var mineRecharge: Float = 0f

    val isPowered: Boolean get() = powerTime > 0f

    fun distanceTo(px: Float, py: Float): Float = hypot(x - px, y - py)

    fun muzzleX(): Float = x + kotlin.math.cos(turretDir) * (GameConfig.TANK_HALF + 9f)
    fun muzzleY(): Float = y + kotlin.math.sin(turretDir) * (GameConfig.TANK_HALF + 9f)

    fun flagBits(): Int {
        var flags = 0
        if (alive) flags = flags or GameConfig.FLAG_ALIVE
        if (lightsOn) flags = flags or GameConfig.FLAG_LIGHTS
        if (moving) flags = flags or GameConfig.FLAG_MOVING
        if (invuln > 0f) flags = flags or GameConfig.FLAG_INVULN
        if (powerTime > 0f) flags = flags or GameConfig.FLAG_POWER
        if (lightTime > 0f) flags = flags or GameConfig.FLAG_LIGHT
        return flags
    }

    fun cooldownFraction(): Float {
        val base = if (powerTime > 0f) GameConfig.FIRE_COOLDOWN_POWER else GameConfig.FIRE_COOLDOWN
        return (cooldown / base).coerceIn(0f, 1f)
    }
}

class Bullet(
    var x: Float,
    var y: Float,
    val vx: Float,
    val vy: Float,
    val owner: Int,
    val power: Boolean = false,
) {
    var travelled: Float = 0f
}

/** Supply crate: 0 = repair, 1 = power, 2 = light. */
class Pickup(
    var x: Float,
    var y: Float,
    val type: Int,
)

class Mine(
    var x: Float,
    var y: Float,
    val owner: Int,
) {
    var armTime: Float = 0f
    var armed: Boolean = false
}
