package com.fogofwar.game.core

/** One-shot gameplay event used for effects on every screen. */
enum class EffectKind { SHOT, EXPLOSION, PICKUP }

data class EffectEvent(
    val kind: EffectKind,
    val x: Float,
    val y: Float,
    val radius: Float = 0f,
)
