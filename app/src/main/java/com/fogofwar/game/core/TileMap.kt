package com.fogofwar.game.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

enum class Tile {
    EMPTY,
    BRICK,
    CRACKED,
    STEEL,
    WATER;

    companion object {
        private val VALUES = values()
        fun fromOrdinal(v: Int): Tile = VALUES.getOrElse(v) { EMPTY }
    }
}

/** A change of one tile, streamed from the host to clients. */
data class TileChange(val index: Int, val tile: Tile)

class TileMap(
    val width: Int = GameConfig.MAP_TILES,
    val height: Int = GameConfig.MAP_TILES,
) {
    val tiles: ByteArray = ByteArray(width * height)

    /** Increments on every tile mutation; render caches use it to invalidate. */
    var revision: Long = 0L
        private set

    /** Tile coordinates of spawn points (up to MAX_PLAYERS). */
    val spawns: MutableList<IntArray> = ArrayList()

    /** Team-mode spawns: three for team A, then three for team B. */
    val teamSpawns: MutableList<IntArray> = ArrayList()

    fun index(tx: Int, ty: Int): Int = ty * width + tx

    fun inBounds(tx: Int, ty: Int): Boolean = tx in 0 until width && ty in 0 until height

    fun tileAt(tx: Int, ty: Int): Tile =
        if (inBounds(tx, ty)) Tile.fromOrdinal(tiles[index(tx, ty)].toInt()) else Tile.STEEL

    fun tileAtWorld(x: Float, y: Float): Tile =
        tileAt(floor(x / GameConfig.TILE).toInt(), floor(y / GameConfig.TILE).toInt())

    fun set(tx: Int, ty: Int, tile: Tile) {
        if (inBounds(tx, ty)) {
            val idx = index(tx, ty)
            val value = tile.ordinal.toByte()
            if (tiles[idx] != value) {
                tiles[idx] = value
                revision++
            }
        }
    }

    fun setWorld(x: Float, y: Float, tile: Tile) {
        set(floor(x / GameConfig.TILE).toInt(), floor(y / GameConfig.TILE).toInt(), tile)
    }

    fun isSolidForTank(tx: Int, ty: Int): Boolean {
        if (!inBounds(tx, ty)) return true
        return when (tileAt(tx, ty)) {
            Tile.EMPTY -> false
            else -> true
        }
    }

    fun blocksLight(tx: Int, ty: Int): Boolean = when (tileAt(tx, ty)) {
        Tile.BRICK, Tile.CRACKED, Tile.STEEL -> true
        else -> false
    }

    fun blocksLightAtWorld(x: Float, y: Float): Boolean =
        blocksLight(floor(x / GameConfig.TILE).toInt(), floor(y / GameConfig.TILE).toInt())

    /**
     * Distance from ([ax], [ay]) to the first light-blocking tile in direction
     * ([dirX], [dirY]), or [maxDistance] when the ray is clear.
     *
     * Grid traversal (Amanatides–Woo) visits only the tiles the ray crosses, so a thin
     * wall cannot be skipped between samples. The direction does not need to be normalized.
     */
    fun raycastLight(ax: Float, ay: Float, dirX: Float, dirY: Float, maxDistance: Float): Float {
        if (maxDistance <= 0f) return 0f
        val length = hypot(dirX, dirY)
        if (length < 1e-6f) return maxDistance
        val dx = dirX / length
        val dy = dirY / length

        var tx = floor(ax / GameConfig.TILE).toInt()
        var ty = floor(ay / GameConfig.TILE).toInt()
        if (blocksLight(tx, ty)) return 0f

        val stepX = sign(dx).toInt()
        val stepY = sign(dy).toInt()
        val deltaX = if (stepX == 0) Float.POSITIVE_INFINITY else GameConfig.TILE / abs(dx)
        val deltaY = if (stepY == 0) Float.POSITIVE_INFINITY else GameConfig.TILE / abs(dy)
        var maxX = if (stepX == 0) {
            Float.POSITIVE_INFINITY
        } else {
            val boundary = (if (stepX > 0) tx + 1 else tx) * GameConfig.TILE
            max(0f, (boundary - ax) / dx)
        }
        var maxY = if (stepY == 0) {
            Float.POSITIVE_INFINITY
        } else {
            val boundary = (if (stepY > 0) ty + 1 else ty) * GameConfig.TILE
            max(0f, (boundary - ay) / dy)
        }

        var distance = 0f
        var guard = (maxDistance / GameConfig.TILE).toInt() + width + height + 8
        while (distance < maxDistance && guard-- > 0) {
            if (maxX < maxY) {
                distance = maxX
                tx += stepX
                maxX += deltaX
            } else {
                distance = maxY
                ty += stepY
                maxY += deltaY
            }
            if (distance > maxDistance) return maxDistance
            if (blocksLight(tx, ty)) return distance
        }
        return maxDistance
    }

    /** True when a light ray from A to B is stopped by a wall before reaching B. */
    fun blocksLightAlong(ax: Float, ay: Float, bx: Float, by: Float): Boolean {
        val dx = bx - ax
        val dy = by - ay
        val distance = hypot(dx, dy)
        if (distance < 1f) return false
        return raycastLight(ax, ay, dx, dy, distance) < distance
    }

    fun blocksBullet(tx: Int, ty: Int): Boolean = when (tileAt(tx, ty)) {
        Tile.BRICK, Tile.CRACKED, Tile.STEEL -> true
        else -> false
    }

    /** Applies a bullet hit, returns the change to broadcast (if any). */
    fun hit(tx: Int, ty: Int): TileChange? {
        if (!inBounds(tx, ty)) return null
        val idx = index(tx, ty)
        return when (tileAt(tx, ty)) {
            Tile.BRICK -> {
                tiles[idx] = Tile.CRACKED.ordinal.toByte()
                revision++
                TileChange(idx, Tile.CRACKED)
            }
            Tile.CRACKED -> {
                tiles[idx] = Tile.EMPTY.ordinal.toByte()
                revision++
                TileChange(idx, Tile.EMPTY)
            }
            else -> null
        }
    }

    /** A powered bullet / explosion removes a brick in one hit. */
    fun hitHard(tx: Int, ty: Int): TileChange? {
        if (!inBounds(tx, ty)) return null
        return when (tileAt(tx, ty)) {
            Tile.BRICK, Tile.CRACKED -> {
                val idx = index(tx, ty)
                tiles[idx] = Tile.EMPTY.ordinal.toByte()
                revision++
                TileChange(idx, Tile.EMPTY)
            }
            else -> null
        }
    }

    fun applyChange(change: TileChange) {
        if (change.index in tiles.indices) {
            val value = change.tile.ordinal.toByte()
            if (tiles[change.index] != value) {
                tiles[change.index] = value
                revision++
            }
        }
    }

    /** True if an axis-aligned box (half extents) does not intersect any solid tile. */
    fun isFreeForTankBox(cx: Float, cy: Float, half: Float): Boolean {
        val left = floor((cx - half) / GameConfig.TILE).toInt()
        val right = floor((cx + half) / GameConfig.TILE).toInt()
        val top = floor((cy - half) / GameConfig.TILE).toInt()
        val bottom = floor((cy + half) / GameConfig.TILE).toInt()
        for (ty in top..bottom) {
            for (tx in left..right) {
                if (isSolidForTank(tx, ty)) return false
            }
        }
        return true
    }

    fun clearRect(tx0: Int, ty0: Int, tx1: Int, ty1: Int) {
        for (ty in ty0..ty1) {
            for (tx in tx0..tx1) set(tx, ty, Tile.EMPTY)
        }
    }

    fun fillRect(tx0: Int, ty0: Int, tx1: Int, ty1: Int, tile: Tile) {
        for (ty in ty0..ty1) {
            for (tx in tx0..tx1) set(tx, ty, tile)
        }
    }

    /** Deep copy, used by unit tests and by clients that need a pristine template. */
    fun copy(): TileMap {
        val m = TileMap(width, height)
        tiles.copyInto(m.tiles)
        spawns.forEach { m.spawns.add(it.copyOf()) }
        return m
    }
}
