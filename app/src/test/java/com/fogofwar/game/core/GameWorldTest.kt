package com.fogofwar.game.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
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
        shooter.bodyDir = 0f
        shooter.turretDir = 0f
        target.x = 1080f
        target.y = 1024f
        target.bodyDir = 0f
        target.turretDir = 0f

        world.setInput(0, TankInput(fire = true))
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
    fun heldStickAgainstWallIsNotMoving() {
        val world = GameWorld(MapGenerator.generate(0, 41L), 0, 41L)
        val tank = world.addPlayer(0, "wall")
        world.addPlayer(1, "other")
        tank.x = GameConfig.TILE + GameConfig.TANK_HALF + 1f
        tank.y = 1024f
        assertTrue(world.map.isFreeForTankBox(tank.x, tank.y, GameConfig.TANK_HALF))
        world.setInput(0, TankInput(moveX = -1f))
        repeat(10) { world.step(GameConfig.TICK_DT) }
        assertFalse(tank.moving)
    }

    @Test
    fun pickupsSpawnInsideSafeZone() {
        val world = GameWorld(MapGenerator.generate(2, 43L), 2, 43L)
        world.addPlayer(0, "a")
        world.addPlayer(1, "b")
        repeat(20 * GameConfig.TICK_HZ) { world.step(GameConfig.TICK_DT) }
        assertTrue(world.pickups.isNotEmpty())
        for (pickup in world.pickups) {
            assertTrue(
                "pickup outside zone",
                hypot(pickup.x - world.centerX, pickup.y - world.centerY) <= world.zoneRadius,
            )
        }
    }

    @Test
    fun releaseShotFiresExactlyAlongTheAim() {
        val world = GameWorld(MapGenerator.generate(0, 61L), 0, 61L)
        val shooter = world.addPlayer(0, "shooter")
        world.addPlayer(1, "target")
        shooter.x = 1000f
        shooter.y = 1024f
        shooter.turretDir = 0f
        world.setInput(0, TankInput(aimX = 0f, aimY = -1f, fire = true))
        world.step(GameConfig.TICK_DT)
        assertEquals(-Math.PI.toFloat() / 2f, shooter.turretDir, 0.01f)
        assertTrue(world.bullets.isNotEmpty())
        val bullet = world.bullets.first()
        assertTrue("bullet went the wrong way", bullet.vy < -100f)
        assertTrue(Math.abs(bullet.vx) < 1f)
    }

    @Test
    fun tanksArePushedApart() {
        val world = GameWorld(MapGenerator.generate(0, 51L), 0, 51L)
        val a = world.addPlayer(0, "a")
        val b = world.addPlayer(1, "b")
        a.x = 1024f
        a.y = 1024f
        b.x = 1036f
        b.y = 1024f
        world.step(GameConfig.TICK_DT)
        assertTrue(
            "tanks still overlap",
            hypot(b.x - a.x, b.y - a.y) >= GameConfig.TANK_HALF * 2f - 1f,
        )
    }

    @Test
    fun grenadeChargesRechargeOverTime() {
        val world = GameWorld(MapGenerator.generate(1, 52L), 1, 52L)
        val tank = world.addPlayer(0, "miner")
        world.addPlayer(1, "other")
        tank.grenades = 0
        repeat((GameConfig.GRENADE_RECHARGE_TIME * GameConfig.TICK_HZ).toInt() + 2) {
            world.step(GameConfig.TICK_DT)
        }
        assertTrue("grenade did not recharge", tank.grenades >= 1)
    }

    @Test
    fun disconnectLeavesSurvivorAsWinner() {
        val world = GameWorld(MapGenerator.generate(3, 31L), 3, 31L)
        world.addPlayer(0, "survivor")
        world.addPlayer(1, "leaver")
        world.requestRemove(1)
        world.step(GameConfig.TICK_DT)
        assertTrue(world.roundOver)
        assertEquals(0, world.winnerSlot)
        assertEquals("survivor", world.winnerName)
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

    @Test
    fun turretRotatesIndependentlyOfHull() {
        val world = GameWorld(MapGenerator.generate(0, 21L), 0, 21L)
        val tank = world.addPlayer(0, "gunner")
        tank.x = 1024f
        tank.y = 1024f
        tank.bodyDir = 0f
        tank.turretDir = 0f
        world.addPlayer(1, "other")
        // Drive right while aiming straight up.
        world.setInput(0, TankInput(moveX = 1f, moveY = 0f, aimX = 0f, aimY = -1f))
        repeat(20) { world.step(GameConfig.TICK_DT) }
        assertEquals(0f, tank.bodyDir, 0.2f)
        assertEquals(-Math.PI.toFloat() / 2f, tank.turretDir, 0.25f)
    }

    @Test
    fun repairPickupHealsAndEmitsEvent() {
        val world = GameWorld(MapGenerator.generate(1, 22L), 1, 22L)
        val tank = world.addPlayer(0, "medic")
        world.addPlayer(1, "other")
        world.step(GameConfig.TICK_DT) // initial crates are spawned on the first tick
        tank.hp = 2
        world.pickups.add(Pickup(tank.x, tank.y, GameConfig.PICKUP_REPAIR))
        world.step(GameConfig.TICK_DT)
        assertEquals(GameConfig.MAX_HP, tank.hp)
        assertTrue(world.pickups.none { hypot(it.x - tank.x, it.y - tank.y) < 1f })
        assertTrue(world.drainEffects().any { it.kind == EffectKind.PICKUP })
    }

    @Test
    fun grenadeExplodesAfterFuseAndDamagesEnemy() {
        val world = GameWorld(MapGenerator.generate(2, 23L), 2, 23L)
        val owner = world.addPlayer(0, "thrower")
        val enemy = world.addPlayer(1, "victim")
        owner.x = 1000f
        owner.y = 1024f
        enemy.x = 1060f
        enemy.y = 1024f
        val grenade = Grenade(1040f, 1024f, 0)
        grenade.landed = true
        grenade.fuse = 0.01f
        world.grenades.add(grenade)
        world.step(GameConfig.TICK_DT)
        assertEquals(GameConfig.MAX_HP - 1, enemy.hp)
        assertTrue(world.grenades.isEmpty())
        assertTrue(world.drainEffects().any { it.kind == EffectKind.EXPLOSION })
    }

    @Test
    fun thrownGrenadeFliesOverAWallAndThenExplodes() {
        val world = GameWorld(MapGenerator.generate(0, 41L), 0, 41L)
        val tank = world.addPlayer(0, "thrower")
        world.addPlayer(1, "other")
        // Tile (32, 32) is the world centre; use a free spot and force a wall right in front.
        tank.x = 32f * GameConfig.TILE
        tank.y = 32f * GameConfig.TILE
        tank.turretDir = 0f
        world.grenades.clear()
        tank.grenades = 1
        tank.throwCooldown = 0f
        world.setInput(0, TankInput(throwGrenade = true))
        world.step(GameConfig.TICK_DT)
        assertEquals(1, world.grenades.size)
        val grenade = world.grenades.first()
        assertFalse("grenade should start in flight", grenade.landed)
        world.setInput(0, TankInput())
        repeat((GameConfig.GRENADE_FLIGHT_TIME / GameConfig.TICK_DT).toInt() + 2) {
            world.step(GameConfig.TICK_DT)
        }
        assertTrue("grenade should land", grenade.landed)
        repeat((GameConfig.GRENADE_FUSE_TIME / GameConfig.TICK_DT).toInt() + 2) {
            world.step(GameConfig.TICK_DT)
        }
        assertTrue("grenade should explode", world.grenades.isEmpty())
    }

    @Test
    fun teamModeBlocksFriendlyFire() {
        val world = GameWorld(
            MapGenerator.generate(1, 5L),
            1,
            5L,
            RoundRules(teams = true, lives = 1),
        )
        val allyA = world.addPlayer(0, "A", team = 0)
        val allyB = world.addPlayer(1, "B", team = 0)
        val enemy = world.addPlayer(2, "C", team = 1)

        world.damage(allyB, allyA.slot, ignoreInvuln = false)
        assertEquals(GameConfig.MAX_HP, allyB.hp)

        world.damage(enemy, allyA.slot, ignoreInvuln = false)
        assertEquals(GameConfig.MAX_HP - 1, enemy.hp)
    }

    @Test
    fun livesModeRespawnsAtSpawn() {
        val rules = RoundRules(lives = 3)
        val world = GameWorld(MapGenerator.generate(1, 6L), 1, 6L, rules)
        val attacker = world.addPlayer(0, "A")
        val victim = world.addPlayer(1, "B")
        val spawnX = victim.spawnX
        val spawnY = victim.spawnY

        repeat(3) { world.damage(victim, attacker.slot, ignoreInvuln = true) }

        assertFalse(victim.alive)
        assertEquals(2, victim.lives)
        repeat((GameConfig.RESPAWN_DELAY * GameConfig.TICK_HZ).toInt() + 2) {
            world.step(GameConfig.TICK_DT)
        }
        assertTrue("tank should respawn", victim.alive)
        assertEquals(GameConfig.MAX_HP, victim.hp)
        assertEquals(spawnX, victim.x, 0.01f)
        assertEquals(spawnY, victim.y, 0.01f)
    }

    @Test
    fun timeLimitAwardsTheMostKills() {
        val world = GameWorld(
            MapGenerator.generate(1, 7L),
            1,
            7L,
            RoundRules(timeLimitSec = 1),
        )
        val leader = world.addPlayer(0, "A")
        world.addPlayer(1, "B")
        leader.kills = 2
        repeat(GameConfig.TICK_HZ + 2) { world.step(GameConfig.TICK_DT) }
        assertTrue(world.roundOver)
        assertEquals(0, world.winnerSlot)
        assertEquals("A", world.winnerName)
    }
}
