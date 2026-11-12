package com.fogofwar.game.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileMapRaycastTest {

    private val t = GameConfig.TILE

    @Test
    fun clearRayReturnsMaxDistance() {
        val map = TileMap(8, 8)

        val hit = map.raycastLight(2.5f * t, 4.5f * t, 1f, 0f, 100f)

        assertEquals(100f, hit, 0.001f)
    }

    @Test
    fun wallAtTileBoundaryStopsTheRay() {
        val map = TileMap(8, 8)
        map.fillRect(4, 0, 4, 7, Tile.STEEL)

        val hit = map.raycastLight(2.5f * t, 4.5f * t, 1f, 0f, 400f)

        assertEquals(1.5f * t, hit, 0.01f)
    }

    @Test
    fun singleTileWallIsNotSkipped() {
        val map = TileMap(10, 10)
        map.set(6, 5, Tile.BRICK)

        val hit = map.raycastLight(2.5f * t, 5.5f * t, 1f, 0f, 500f)

        assertEquals(3.5f * t, hit, 0.01f)
    }

    @Test
    fun diagonalRayIsStoppedByAKnifeEdgeGap() {
        val map = TileMap(8, 8)
        map.set(3, 2, Tile.STEEL)
        map.set(2, 3, Tile.STEEL)

        val hit = map.raycastLight(2.5f * t, 2.5f * t, 1f, 1f, 300f)

        assertTrue("a diagonal corner should not leak light", hit < 300f)
    }

    @Test
    fun diagonalRayPassesThroughAnOpenDiagonal() {
        val map = TileMap(8, 8)

        val hit = map.raycastLight(2.5f * t, 2.5f * t, 1f, 1f, 100f)

        assertEquals(100f, hit, 0.001f)
    }

    @Test
    fun rayStartingInsideAWallHasZeroLength() {
        val map = TileMap(8, 8)
        map.set(2, 2, Tile.STEEL)

        assertEquals(0f, map.raycastLight(2.5f * t, 2.5f * t, 1f, 0f, 200f), 0.001f)
    }

    @Test
    fun waterDoesNotBlockLight() {
        val map = TileMap(8, 8)
        map.fillRect(4, 0, 4, 7, Tile.WATER)

        val hit = map.raycastLight(2.5f * t, 4.5f * t, 1f, 0f, 100f)

        assertEquals(100f, hit, 0.001f)
    }

    @Test
    fun backwardRayStopsAtTheWallBehind() {
        val map = TileMap(8, 8)
        map.fillRect(2, 0, 2, 7, Tile.BRICK)

        val hit = map.raycastLight(5.5f * t, 4.5f * t, -1f, 0f, 400f)

        assertEquals(2.5f * t, hit, 0.01f)
    }

    @Test
    fun lineOfSightDetectsAWallBetweenTanks() {
        val map = TileMap(10, 10)
        map.set(5, 5, Tile.BRICK)

        assertTrue(map.blocksLightAlong(2.5f * t, 5.5f * t, 8.5f * t, 5.5f * t))
        assertFalse(map.blocksLightAlong(2.5f * t, 5.5f * t, 4.5f * t, 5.5f * t))
        assertFalse(map.blocksLightAlong(2.5f * t, 5.5f * t, 2.6f * t, 5.5f * t))
    }
}
