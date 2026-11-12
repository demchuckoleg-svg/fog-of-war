package com.fogofwar.game.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every spawn has to reach the centre and every other spawn on foot. Decorative walls may
 * only be brick (destructible); steel and water must never seal a player in.
 */
class MapConnectivityTest {

    @Test
    fun everySpawnReachesTheCentreOnEveryMap() {
        MapGenerator.MAP_NAMES.indices.forEach { mapId ->
            val map = MapGenerator.generate(mapId, GameConfig.MAP_SEED)
            val start = map.spawns.first()
            val reachable = floodFill(map, start[0], start[1])
            val centre = map.width / 2

            assertTrue(
                "map $mapId: centre is unreachable from spawn ${start[0]},${start[1]}",
                reachable[map.index(centre, centre)],
            )
        }
    }

    @Test
    fun everySpawnReachesEveryOtherSpawnOnEveryMap() {
        MapGenerator.MAP_NAMES.indices.forEach { mapId ->
            val map = MapGenerator.generate(mapId, GameConfig.MAP_SEED)
            val start = map.spawns.first()
            val reachable = floodFill(map, start[0], start[1])

            map.spawns.forEach { spawn ->
                assertTrue(
                    "map $mapId: spawn ${spawn[0]},${spawn[1]} is cut off from ${start[0]},${start[1]}",
                    reachable[map.index(spawn[0], spawn[1])],
                )
            }
        }
    }

    @Test
    fun spawnsAreSpreadAroundTheMap() {
        MapGenerator.MAP_NAMES.indices.forEach { mapId ->
            val map = MapGenerator.generate(mapId, GameConfig.MAP_SEED)
            val spawns = map.spawns.map { it.toList() }.toSet()

            assertTrue("map $mapId should have 6 distinct spawns", spawns.size == GameConfig.MAX_PLAYERS)
        }
    }

    /** Breadth-first search over empty tiles; brick is destructible, steel and water are not. */
    private fun floodFill(map: TileMap, startX: Int, startY: Int): BooleanArray {
        val seen = BooleanArray(map.width * map.height)
        val queue = IntArray(map.width * map.height)
        var head = 0
        var tail = 0
        val startIndex = map.index(startX, startY)
        seen[startIndex] = true
        queue[tail++] = startIndex
        while (head < tail) {
            val index = queue[head++]
            val tx = index % map.width
            val ty = index / map.width
            for (dir in DIRECTIONS) {
                val nx = tx + dir[0]
                val ny = ty + dir[1]
                if (!map.inBounds(nx, ny)) continue
                val next = map.index(nx, ny)
                if (seen[next]) continue
                if (map.tileAt(nx, ny) != Tile.EMPTY) continue
                seen[next] = true
                queue[tail++] = next
            }
        }
        return seen
    }

    private companion object {
        val DIRECTIONS = arrayOf(
            intArrayOf(1, 0),
            intArrayOf(-1, 0),
            intArrayOf(0, 1),
            intArrayOf(0, -1),
        )
    }
}
