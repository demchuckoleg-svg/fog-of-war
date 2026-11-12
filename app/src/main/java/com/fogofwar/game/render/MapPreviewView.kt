package com.fogofwar.game.render

import android.content.Context
import android.graphics.Bitmap
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
 *
 * The map is rasterised once into a bitmap per size/map id: the previous version issued up
 * to 4096 drawRect calls on every invalidate (roster changes, layout passes).
 */
class MapPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tilePaint = Paint().apply { isAntiAlias = false }
    private var map: TileMap? = null
    private var cache: Bitmap? = null
    private var cacheWidth = 0
    private var cacheHeight = 0
    private var cacheMapId = Int.MIN_VALUE

    var displayedMapId: Int = -1
        set(value) {
            if (field == value) return
            field = value
            map = MapGenerator.generate(value, GameConfig.MAP_SEED)
            cache = null
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val map = this.map ?: MapGenerator.generate(0, GameConfig.MAP_SEED).also { this.map = it }
        if (cache == null || cacheWidth != width || cacheHeight != height || cacheMapId != displayedMapId) {
            cache?.recycle()
            cache = renderPreview(map)
            cacheWidth = width
            cacheHeight = height
            cacheMapId = displayedMapId
        }
        cache?.let { canvas.drawBitmap(it, 0f, 0f, paint) }
    }

    private fun renderPreview(map: TileMap): Bitmap {
        val bitmap = Bitmap.createBitmap(
            width.coerceAtLeast(1),
            height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        val scale = min(width.toFloat() / map.width, height.toFloat() / map.height)
        val offsetX = (width - map.width * scale) / 2f
        val offsetY = (height - map.height * scale) / 2f

        tilePaint.color = 0xFF0B100C.toInt()
        canvas.drawRect(offsetX, offsetY, offsetX + map.width * scale, offsetY + map.height * scale, tilePaint)

        for (ty in 0 until map.height) {
            for (tx in 0 until map.width) {
                val color = when (map.tileAt(tx, ty)) {
                    Tile.EMPTY -> continue
                    Tile.BRICK -> 0xFF8A4A2C.toInt()
                    Tile.CRACKED -> 0xFF5A2E1C.toInt()
                    Tile.STEEL -> 0xFF5A6672.toInt()
                    Tile.WATER -> 0xFF1E5C80.toInt()
                }
                tilePaint.color = color
                canvas.drawRect(
                    offsetX + tx * scale,
                    offsetY + ty * scale,
                    offsetX + (tx + 1) * scale,
                    offsetY + (ty + 1) * scale,
                    tilePaint,
                )
            }
        }

        tilePaint.color = 0xFF57D68B.toInt()
        val r = (scale * 1.6f).coerceAtLeast(2f)
        for (spawn in map.spawns) {
            canvas.drawCircle(
                offsetX + (spawn[0] + 0.5f) * scale,
                offsetY + (spawn[1] + 0.5f) * scale,
                r,
                tilePaint,
            )
        }

        tilePaint.style = Paint.Style.STROKE
        tilePaint.strokeWidth = 2f
        tilePaint.color = 0x33FFFFFF
        canvas.drawRect(offsetX, offsetY, offsetX + map.width * scale, offsetY + map.height * scale, tilePaint)
        tilePaint.style = Paint.Style.FILL
        tilePaint.color = 0x99FF4D4D.toInt()
        canvas.drawCircle(
            offsetX + GameConfig.MAP_TILES / 2f * scale,
            offsetY + GameConfig.MAP_TILES / 2f * scale,
            scale * 1.4f,
            tilePaint,
        )
        return bitmap
    }
}
