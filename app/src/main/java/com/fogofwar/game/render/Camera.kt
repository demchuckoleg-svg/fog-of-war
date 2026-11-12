package com.fogofwar.game.render

import com.fogofwar.game.core.GameConfig

/** Follows the local tank and keeps the camera inside the map. */
class Camera {
    var scale: Float = 2.4f
        private set
    var x: Float = GameConfig.WORLD_SIZE / 2f
        private set
    var y: Float = GameConfig.WORLD_SIZE / 2f
        private set
    var screenW: Float = 1f
        private set
    var screenH: Float = 1f
        private set

    val offsetX: Float get() = screenW / 2f - x * scale
    val offsetY: Float get() = screenH / 2f - y * scale

    fun update(w: Float, h: Float, focusX: Float, focusY: Float) {
        screenW = w
        screenH = h
        scale = (h / (14f * GameConfig.TILE)).coerceIn(1.3f, 4.2f)

        val halfW = w / (2f * scale)
        val halfH = h / (2f * scale)
        val world = GameConfig.WORLD_SIZE
        x = if (halfW * 2f >= world) world / 2f else focusX.coerceIn(halfW, world - halfW)
        y = if (halfH * 2f >= world) world / 2f else focusY.coerceIn(halfH, world - halfH)
    }

    val worldLeft: Float get() = x - screenW / (2f * scale)
    val worldTop: Float get() = y - screenH / (2f * scale)
    val worldRight: Float get() = x + screenW / (2f * scale)
    val worldBottom: Float get() = y + screenH / (2f * scale)
}
