package com.fogofwar.game.core

import kotlin.math.hypot

/** Input coming from a joystick: moveX/moveY in [-1..1], fire is a held button. */
data class TankInput(
    val moveX: Float = 0f,
    val moveY: Float = 0f,
    val fire: Boolean = false,
) {
    /** Clamps the stick to a unit circle (diagonal is not faster). */
    fun clamped(): TankInput {
        val len = hypot(moveX, moveY)
        return if (len <= 1f) this else TankInput(moveX / len, moveY / len, fire)
    }

    val isMoving: Boolean get() = hypot(moveX, moveY) > 0.08f
}

class Tank(
    val slot: Int,
    var name: String,
    var x: Float,
    var y: Float,
    var dir: Float,
    val isBot: Boolean = false,
) {
    var hp: Int = GameConfig.MAX_HP
    var alive: Boolean = true
    var cooldown: Float = 0f
    var invuln: Float = 0f
    var outsideTimer: Float = 0f
    var lastDamageFrom: Int = -1

    fun distanceTo(px: Float, py: Float): Float = hypot(x - px, y - py)
}

class Bullet(
    var x: Float,
    var y: Float,
    val vx: Float,
    val vy: Float,
    val owner: Int,
) {
    var travelled: Float = 0f
}
