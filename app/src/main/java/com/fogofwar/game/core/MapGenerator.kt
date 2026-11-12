package com.fogofwar.game.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic map generation. The host only sends (mapId, seed), every client
 * rebuilds the exact same map, so a 64x64 map never travels over the network.
 */
object MapGenerator {
    val MAP_NAMES = listOf(
        "Лабиринт Руин",
        "Стальная Крепость",
        "Мосты",
        "Арена Колонн",
    )

    val MAP_DESCRIPTIONS = listOf(
        "Кирпичные кварталы и узкие улицы",
        "Стальные кольца, открытый центр",
        "Реки, острова и узкие мосты",
        "Симметричные колонны и укрытия",
    )

    private val SPAWN_TILES = listOf(
        intArrayOf(5, 5),
        intArrayOf(58, 5),
        intArrayOf(5, 58),
        intArrayOf(58, 58),
        intArrayOf(32, 3),
        intArrayOf(32, 60),
    )

    fun generate(mapId: Int, seed: Long): TileMap {
        val map = TileMap()
        val rng = Rng(seed xor (mapId.toLong() * 0x9E3779B9L) xor 0x51AF3FL)
        val id = ((mapId % MAP_NAMES.size) + MAP_NAMES.size) % MAP_NAMES.size

        // Solid border.
        map.fillRect(0, 0, map.width - 1, 0, Tile.STEEL)
        map.fillRect(0, map.height - 1, map.width - 1, map.height - 1, Tile.STEEL)
        map.fillRect(0, 0, 0, map.height - 1, Tile.STEEL)
        map.fillRect(map.width - 1, 0, map.width - 1, map.height - 1, Tile.STEEL)

        when (id) {
            0 -> generateRuins(map, rng)
            1 -> generateFortress(map, rng)
            2 -> generateBridges(map, rng)
            else -> generatePillars(map, rng)
        }

        // Arena in the middle, always open.
        map.clearRect(27, 27, 36, 36)

        for (spawn in SPAWN_TILES) {
            map.spawns.add(spawn.copyOf())
            clearSpawn(map, spawn[0], spawn[1])
        }
        // Connect every spawn to the centre with a narrow corridor.
        for (spawn in SPAWN_TILES) carveCorridor(map, spawn[0], spawn[1], 32, 32)
        return map
    }

    private fun generateRuins(map: TileMap, rng: Rng) {
        var by = 5
        while (by < map.height - 4) {
            var bx = 5
            while (bx < map.width - 4) {
                if (rng.nextFloat() >= 0.16f) {
                    val w = rng.nextInt(4, 7)
                    val h = rng.nextInt(4, 7)
                    val tile = if (rng.nextFloat() < 0.16f) Tile.STEEL else Tile.BRICK
                    map.fillRect(bx, by, min(bx + w - 1, map.width - 2), min(by + h - 1, map.height - 2), tile)
                    if (tile == Tile.BRICK && rng.nextFloat() < 0.5f && w > 4 && h > 4) {
                        map.clearRect(bx + 2, by + 2, bx + w - 3, by + h - 3)
                    }
                }
                bx += 11
            }
            by += 11
        }
        repeat(30) {
            val x = rng.nextInt(3, map.width - 4)
            val y = rng.nextInt(3, map.height - 4)
            map.fillRect(x, y, x + rng.nextInt(0, 2), y + rng.nextInt(0, 2), Tile.BRICK)
        }
    }

    private fun generateFortress(map: TileMap, rng: Rng) {
        val cx = map.width / 2
        val cy = map.height / 2
        for (ty in 1 until map.height - 1) {
            for (tx in 1 until map.width - 1) {
                val d = max(abs(tx - cx), abs(ty - cy))
                if (d == 5 || d == 13 || d == 23) {
                    val gap = abs(tx - cx) <= 2 || abs(ty - cy) <= 2
                    if (!gap) map.set(tx, ty, Tile.STEEL)
                }
            }
        }
        repeat(70) {
            val x = rng.nextInt(3, map.width - 4)
            val y = rng.nextInt(3, map.height - 4)
            if (map.tileAt(x, y) == Tile.EMPTY) {
                val w = rng.nextInt(2, 5)
                val h = rng.nextInt(2, 5)
                for (yy in y until min(y + h, map.height - 1)) {
                    for (xx in x until min(x + w, map.width - 1)) map.set(xx, yy, Tile.BRICK)
                }
            }
        }
    }

    private fun generateBridges(map: TileMap, rng: Rng) {
        // Two vertical rivers and one horizontal river.
        val riversX = listOf(19..24, 43..48)
        for (r in riversX) {
            for (ty in 1 until map.height - 1) {
                for (tx in r) map.set(tx, ty, Tile.WATER)
            }
        }
        for (ty in 30..34) {
            for (tx in 1 until map.width - 1) map.set(tx, ty, Tile.WATER)
        }
        // Bridges: two per river plus two over the horizontal river.
        for (r in riversX) {
            for (bridgeY in listOf(8..10, 21..23, 52..54)) {
                for (ty in bridgeY) {
                    for (tx in r) map.set(tx, ty, Tile.EMPTY)
                }
            }
        }
        for (bridgeX in listOf(7..9, 31..33, 55..57)) {
            for (tx in bridgeX) {
                for (ty in 30..34) map.set(tx, ty, Tile.EMPTY)
            }
        }
        repeat(55) {
            val x = rng.nextInt(3, map.width - 4)
            val y = rng.nextInt(3, map.height - 4)
            val w = rng.nextInt(2, 4)
            val h = rng.nextInt(2, 4)
            var ok = true
            for (yy in y..min(y + h, map.height - 2)) {
                for (xx in x..min(x + w, map.width - 2)) {
                    if (map.tileAt(xx, yy) == Tile.WATER) ok = false
                }
            }
            if (ok) map.fillRect(x, y, min(x + w, map.width - 2), min(y + h, map.height - 2), Tile.BRICK)
        }
    }

    private fun generatePillars(map: TileMap, rng: Rng) {
        var gy = 5
        while (gy < map.height - 4) {
            var gx = 5
            while (gx < map.width - 4) {
                when (rng.nextInt(10)) {
                    in 0..3 -> map.fillRect(gx, gy, gx + 1, gy + 1, Tile.STEEL)
                    in 4..7 -> map.fillRect(gx, gy, gx + 2, gy + 2, Tile.BRICK)
                    else -> map.fillRect(gx, gy, gx + 1, gy + 1, Tile.BRICK)
                }
                gx += 8
            }
            gy += 8
        }
        // Central cross of steel to break sight lines near the arena.
        for (i in 20..44) {
            if (abs(i - 32) > 3) {
                map.set(i, 32, Tile.STEEL)
                map.set(32, i, Tile.STEEL)
            }
        }
    }

    private fun clearSpawn(map: TileMap, tx: Int, ty: Int) {
        map.clearRect((tx - 3).coerceAtLeast(1), (ty - 3).coerceAtLeast(1), (tx + 3).coerceAtMost(map.width - 2), (ty + 3).coerceAtMost(map.height - 2))
    }

    /** L-shaped two-tile-wide corridor from spawn to the arena. */
    private fun carveCorridor(map: TileMap, x0: Int, y0: Int, x1: Int, y1: Int) {
        val spanX = min(x0, x1)..max(x0, x1)
        for (x in spanX) {
            map.clearRect(x, y0, x, (y0 + 1).coerceAtMost(map.height - 2))
        }
        val spanY = min(y0, y1)..max(y0, y1)
        for (y in spanY) {
            map.clearRect(x1, y, (x1 + 1).coerceAtMost(map.width - 2), y)
        }
    }
}
