package com.fogofwar.game.core

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotControllerTest {

    @Test
    fun botIgnoresAnEnemyBehindASteelWall() {
        val world = arena()
        val bot = world.addBot(0, "Бот")
        val enemy = world.addPlayer(1, "Игрок")
        bot.x = 5f * GameConfig.TILE
        bot.y = 20f * GameConfig.TILE
        bot.turretDir = 0f
        bot.bodyDir = 0f
        enemy.x = 15f * GameConfig.TILE
        enemy.y = 20f * GameConfig.TILE
        world.map.fillRect(9, 15, 9, 25, Tile.STEEL)

        val input = BotController(0, Rng(7L)).update(world, GameConfig.TICK_DT)

        assertEquals("bot must not aim through a wall", 0f, input.aimX, 0.001f)
        assertEquals(0f, input.aimY, 0.001f)
        assertFalse(input.fire)
    }

    @Test
    fun botAimsAndFiresWhenTheSightLineOpens() {
        val world = arena()
        val bot = world.addBot(0, "Бот")
        val enemy = world.addPlayer(1, "Игрок")
        bot.x = 5f * GameConfig.TILE
        bot.y = 20f * GameConfig.TILE
        bot.turretDir = 0f
        bot.bodyDir = 0f
        enemy.x = 15f * GameConfig.TILE
        enemy.y = 20f * GameConfig.TILE

        val input = BotController(0, Rng(7L)).update(world, GameConfig.TICK_DT)

        assertTrue("bot should aim at a visible enemy", input.aimX > 0.9f)
        assertTrue(abs(input.aimY) < 0.01f)
        assertTrue(input.fire)
    }

    @Test
    fun botThrowsAGrenadeWhenAnEnemyIsClose() {
        val world = arena()
        val bot = world.addBot(0, "Бот")
        val enemy = world.addPlayer(1, "Игрок")
        bot.x = 20f * GameConfig.TILE
        bot.y = 20f * GameConfig.TILE
        bot.turretDir = 0f
        enemy.x = bot.x + 90f
        enemy.y = bot.y

        val input = BotController(0, Rng(7L)).update(world, GameConfig.TICK_DT)

        assertTrue(input.throwGrenade)
    }

    /** Empty 40x40 map, no walls, both spawns present. */
    private fun arena(): GameWorld {
        val map = TileMap(40, 40)
        map.spawns.add(intArrayOf(19, 19))
        map.spawns.add(intArrayOf(5, 5))
        return GameWorld(map, 0, 1L)
    }
}
