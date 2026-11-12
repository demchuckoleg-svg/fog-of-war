package com.fogofwar.game.core

import kotlin.math.floor

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

    /** Tile coordinates of spawn points (up to MAX_PLAYERS). */
    val spawns: MutableList<IntArray> = ArrayList()

    fun index(tx: Int, ty: Int): Int = ty * width + tx

    fun inBounds(tx: Int, ty: Int): Boolean = tx in 0 until width && ty in 0 until height

    fun tileAt(tx: Int, ty: Int): Tile =
        if (inBounds(tx, ty)) Tile.fromOrdinal(tiles[index(tx, ty)].toInt()) else Tile.STEEL

    fun tileAtWorld(x: Float, y: Float): Tile =
        tileAt(floor(x / GameConfig.TILE).toInt(), floor(y / GameConfig.TILE).toInt())

    fun set(tx: Int, ty: Int, tile: Tile) {
        if (inBounds(tx, ty)) tiles[index(tx, ty)] = tile.ordinal.toByte()
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
                TileChange(idx, Tile.CRACKED)
            }
            Tile.CRACKED -> {
                tiles[idx] = Tile.EMPTY.ordinal.toByte()
                TileChange(idx, Tile.EMPTY)
            }
            else -> null
        }
    }

    fun applyChange(change: TileChange) {
        if (change.index in tiles.indices) tiles[change.index] = change.tile.ordinal.toByte()
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
