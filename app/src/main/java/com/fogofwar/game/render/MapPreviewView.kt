package com.fogofwar.game.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.fogofwar.game.core.GameConfig
import com.fogofwar.game.core.MapGenerator
import com.fogofwar.game.core.Tile
import com.fogofwar.game.core.TileMap
import kotlin.math.min

/**
 * Small lobby preview of a generated map (same deterministic seed as the round setup
 * preview, so the layout and cover are representative).
 */
class MapPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var map: TileMap? = null

    var displayedMapId: Int = -1
        set(value) {
            if (field == value) return
            field = value
            map = MapGenerator.generate(value, PREVIEW_SEED)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val map = this.map ?: MapGenerator.generate(0, PREVIEW_SEED).also { this.map = it }
        val scale = min(width.toFloat() / map.width, height.toFloat() / map.height)
        val offsetX = (width - map.width * scale) / 2f
        val offsetY = (height - map.height * scale) / 2f

        paint.style = Paint.Style.FILL
        paint.color = 0xFF0B100C.toInt()
        canvas.drawRect(offsetX, offsetY, offsetX + map.width * scale, offsetY + map.height * scale, paint)

        for (ty in 0 until map.height) {
            for (tx in 0 until map.width) {
                val color = when (map.tileAt(tx, ty)) {
                    Tile.EMPTY -> continue
                    Tile.BRICK -> 0xFF8A4A2C.toInt()
                    Tile.CRACKED -> 0xFF5A2E1C.toInt()
                    Tile.STEEL -> 0xFF5A6672.toInt()
                    Tile.WATER -> 0xFF1E5C80.toInt()
                }
                paint.color = color
                canvas.drawRect(
                    offsetX + tx * scale,
                    offsetY + ty * scale,
                    offsetX + (tx + 1) * scale,
                    offsetY + (ty + 1) * scale,
                    paint,
                )
            }
        }

        paint.color = 0xFF57D68B.toInt()
        val r = (scale * 1.6f).coerceAtLeast(2f)
        for (spawn in map.spawns) {
            canvas.drawCircle(
                offsetX + (spawn[0] + 0.5f) * scale,
                offsetY + (spawn[1] + 0.5f) * scale,
                r,
                paint,
            )
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = 0x33FFFFFF
        canvas.drawRect(offsetX, offsetY, offsetX + map.width * scale, offsetY + map.height * scale, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0x99FF4D4D.toInt()
        canvas.drawCircle(
            offsetX + GameConfig.MAP_TILES / 2f * scale,
            offsetY + GameConfig.MAP_TILES / 2f * scale,
            scale * 1.4f,
            paint,
        )
    }

    private companion object {
        const val PREVIEW_SEED = 90210L
    }
}
