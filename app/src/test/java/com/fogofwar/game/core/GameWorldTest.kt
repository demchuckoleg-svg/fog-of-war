package com.fogofwar.game.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameWorldTest {

    @Test
    fun mapsAreDeterministic() {
        for (id in 0..3) {
            val a = MapGenerator.generate(id, 987654321L)
            val b = MapGenerator.generate(id, 987654321L)
            assertArrayEquals("map $id differs between generations", a.tiles, b.tiles)
            assertEquals(GameConfig.MAX_PLAYERS, a.spawns.size)
        }
    }

    @Test
    fun otherSeedsProduceOtherMaps() {
        val a = MapGenerator.generate(0, 1L)
        val b = MapGenerator.generate(0, 2L)
        assertFalse(a.tiles.contentEquals(b.tiles))
    }

    @Test
    fun mapsHaveEnoughCoverAndWater() {
        for (id in 0..3) {
            val map = MapGenerator.generate(id, 33L)
            val bricks = map.tiles.count { it.toInt() == Tile.BRICK.ordinal }
            val steel = map.tiles.count { it.toInt() == Tile.STEEL.ordinal }
            assertTrue("map $id is too empty (brick=$bricks steel=$steel)", bricks + steel > 150)
            if (id == 2) {
                val water = map.tiles.count { it.toInt() == Tile.WATER.ordinal }
                assertTrue("bridges map has no water", water > 100)
            }
        }
    }

    @Test
    fun everySpawnIsClearOnEveryMap() {
        for (id in 0..3) {
            val map = MapGenerator.generate(id, 42L)
            for (spawn in map.spawns) {
                val x = (spawn[0] + 0.5f) * GameConfig.TILE
                val y = (spawn[1] + 0.5f) * GameConfig.TILE
                assertTrue(
                    "spawn ${spawn[0]},${spawn[1]} blocked on map $id",
                    map.isFreeForTankBox(x, y, GameConfig.TANK_HALF),
                )
            }
        }
    }

    @Test
    fun brickNeedsTwoHitsAndReportsChanges() {
        val map = TileMap()
        map.set(3, 3, Tile.BRICK)
        val first = map.hit(3, 3)
        assertEquals(Tile.CRACKED, first?.tile)
        assertEquals(Tile.CRACKED, map.tileAt(3, 3))
        val second = map.hit(3, 3)
        assertEquals(Tile.EMPTY, second?.tile)
        assertEquals(Tile.EMPTY, map.tileAt(3, 3))
        assertEquals(null, map.hit(3, 3))
    }

    @Test
    fun bulletsDamageEnemiesAndRespectSpawnHealth() {
        val world = GameWorld(MapGenerator.generate(0, 5L), 0, 5L)
        world.addPlayer(0, "host")
        world.addPlayer(1, "guest")
        val shooter = world.tank(0)!!
        val target = world.tank(1)!!
        // Central arena is guaranteed to be empty.
        shooter.x = 1000f
        shooter.y = 1024f
        shooter.dir = 0f
        target.x = 1080f
        target.y = 1024f
        target.dir = 0f

        world.setInput(0, TankInput(0f, 0f, true))
        repeat(12) { world.step(GameConfig.TICK_DT) }
        assertEquals(GameConfig.MAX_HP - 1, target.hp)
    }

    @Test
    fun zoneShrinksAfterDelay() {
        val world = GameWorld(MapGenerator.generate(1, 9L), 1, 9L)
        world.addPlayer(0, "a")
        world.addPlayer(1, "b")
        val start = world.zoneRadius
        repeat(((GameConfig.ZONE_SHRINK_DELAY + 6f) * GameConfig.TICK_HZ).toInt()) {
            world.step(GameConfig.TICK_DT)
        }
        assertTrue("zone did not shrink", world.zoneRadius < start)
    }

    @Test
    fun lastAlivePlayerWins() {
        val world = GameWorld(MapGenerator.generate(2, 11L), 2, 11L)
        world.addPlayer(0, "winner")
        world.addPlayer(1, "loser")
        val loser = world.tank(1)!!
        repeat(GameConfig.MAX_HP) { world.damage(loser, 0, ignoreInvuln = true) }
        assertFalse(world.roundOver)
        world.step(GameConfig.TICK_DT)
        assertTrue(world.roundOver)
        assertEquals(0, world.winnerSlot)
        assertEquals("winner", world.winnerName)
    }

    @Test
    fun sonarPulseStartsAfterPause() {
        val world = GameWorld(MapGenerator.generate(3, 12L), 3, 12L)
        world.addPlayer(0, "a")
        world.addPlayer(1, "b")
        repeat(2) { world.step(GameConfig.TICK_DT) }
        assertFalse(world.sonarActive)
        repeat(((GameConfig.SONAR_PAUSE + 0.5f) * GameConfig.TICK_HZ).toInt()) {
            world.step(GameConfig.TICK_DT)
        }
        assertTrue(world.sonarActive)
        assertTrue(world.sonarRadius > 0f)
    }

    @Test
    fun sixtySecondBotRoundStaysValid() {
        val world = GameWorld(MapGenerator.generate(0, 77L), 0, 77L)
        world.addPlayer(0, "player")
        for (slot in 1..4) world.addBot(slot, "bot$slot")
        repeat(GameConfig.TICK_HZ * 60) {
            world.step(GameConfig.TICK_DT)
            for (tank in world.tanks) {
                assertTrue("x out of bounds: ${tank.x}", tank.x in 0f..GameConfig.WORLD_SIZE)
                assertTrue("y out of bounds: ${tank.y}", tank.y in 0f..GameConfig.WORLD_SIZE)
                assertTrue("bad hp: ${tank.hp}", tank.hp in 0..GameConfig.MAX_HP)
            }
            assertTrue(world.zoneRadius >= GameConfig.ZONE_MIN_RADIUS)
        }
        assertFalse(world.bullets.any { it.travelled > GameConfig.BULLET_MAX_RANGE + 50f })
    }
}
