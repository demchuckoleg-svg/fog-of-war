package com.fogofwar.game.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Immutable 2D vector in world units (1 unit = 1 px at zoom 1, tile = 32 units). */
data class Vec2(val x: Float = 0f, val y: Float = 0f) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)

    val length: Float get() = hypot(x, y)

    fun normalized(): Vec2 {
        val l = length
        return if (l < 0.0001f) ZERO else Vec2(x / l, y / l)
    }

    fun distanceTo(o: Vec2): Float = hypot(x - o.x, y - o.y)

    fun angle(): Float = atan2(y, x)

    companion object {
        val ZERO = Vec2(0f, 0f)

        fun fromAngle(angle: Float, radius: Float = 1f): Vec2 =
            Vec2(cos(angle) * radius, sin(angle) * radius)
    }
}

/** Shortest signed difference between two angles, in (-PI, PI]. */
fun angleDelta(from: Float, to: Float): Float {
    var d = (to - from) % (Math.PI.toFloat() * 2f)
    if (d > Math.PI) d -= (Math.PI.toFloat() * 2f)
    if (d < -Math.PI) d += (Math.PI.toFloat() * 2f)
    return d
}

fun lerpAngle(from: Float, to: Float, t: Float): Float = from + angleDelta(from, to) * t.coerceIn(0f, 1f)
