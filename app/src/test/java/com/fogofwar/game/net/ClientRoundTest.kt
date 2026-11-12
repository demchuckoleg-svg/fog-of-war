package com.fogofwar.game.net

import com.fogofwar.game.core.GameConfig
import com.fogofwar.game.core.MapGenerator
import com.fogofwar.game.core.Tile
import com.fogofwar.game.core.TileChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientRoundTest {

    private val players = listOf(
        PlayerInfo(0, "host"),
        PlayerInfo(1, "guest"),
    )

    @Test
    fun firstSnapshotProducesAFrame() {
        val map = MapGenerator.generate(0, 1L)
        val round = ClientRound(map, 0, 1, players)
        round.onSnapshot(
            Snapshot(
                tick = 10,
                tanks = listOf(
                    tank(0, 400f, 400f, 0f, 0f, 3),
                    tank(1, 500f, 500f, 0.5f, 1f, 2),
                ),
                bullets = listOf(BulletSnap(450f, 450f)),
                pickups = listOf(PickupSnap(1, 600f, 600f)),
                grenades = listOf(GrenadeSnap(700f, 700f, false, 0)),
                zoneRadius = 800f,
                sonarRadius = 0f,
                sonarActive = false,
                events = emptyList(),
            )
        )
        val frame = round.frameAt(System.nanoTime() / 1_000_000L)
        assertNotNull(frame)
        assertEquals(2, frame!!.tanks.size)
        assertEquals(1, frame.mySlot)
        assertEquals("guest", frame.me?.name)
        assertEquals(800f, frame.zoneRadius, 0.001f)
        assertEquals(1, frame.pickups.size)
        assertEquals(1, frame.grenades.size)
        assertEquals(1f, frame.tanks[1].turretDir, 0.001f)
    }

    @Test
    fun interpolationBetweenTwoSnapshotsIsBetweenPositions() {
        val map = MapGenerator.generate(0, 2L)
        val round = ClientRound(map, 0, 0, players)
        round.onSnapshot(snapshotAt(tick = 1, x = 100f, body = 0f, turret = 2f))
        round.onSnapshot(snapshotAt(tick = 2, x = 300f, body = 0f, turret = 2.2f))
        val frame = round.frameAt(System.nanoTime() / 1_000_000L)
        assertNotNull(frame)
        val x = frame!!.tanks[0].x
        assertTrue("x=$x", x in 100f..300f)
    }

    @Test
    fun tileChangesAreAppliedToTheClientMapOnTheRenderThread() {
        val map = MapGenerator.generate(0, 3L)
        map.set(10, 10, Tile.BRICK)
        val round = ClientRound(map, 0, 0, players)
        round.onSnapshot(snapshotAt(tick = 1, x = 100f, body = 0f, turret = 0f))
        round.onTileChanges(listOf(TileChange(map.index(10, 10), Tile.CRACKED)))

        round.frameAt(System.nanoTime() / 1_000_000L)

        assertEquals(Tile.CRACKED, map.tileAt(10, 10))
    }

    @Test
    fun roundOverFlagAndWinnerAreExposed() {
        val map = MapGenerator.generate(0, 4L)
        val round = ClientRound(map, 0, 0, players)
        round.onSnapshot(snapshotAt(tick = 1, x = 100f, body = 0f, turret = 0f))
        round.setRoundOver(0, "host")
        val frame = round.frameAt(System.nanoTime() / 1_000_000L)
        assertTrue(frame!!.roundOver)
        assertEquals("host", frame.winnerName)
        assertEquals(0, frame.winnerSlot)
    }

    private fun snapshotAt(tick: Long, x: Float, body: Float, turret: Float) = Snapshot(
        tick = tick,
        tanks = listOf(tank(0, x, 200f, body, turret, 3)),
        bullets = emptyList(),
        pickups = emptyList(),
        grenades = emptyList(),
        zoneRadius = 900f,
        sonarRadius = 0f,
        sonarActive = false,
        events = emptyList(),
    )

    private fun tank(slot: Int, x: Float, y: Float, body: Float, turret: Float, hp: Int) = TankSnap(
        slot = slot,
        x = x,
        y = y,
        bodyDir = body,
        turretDir = turret,
        hp = hp,
        flags = GameConfig.FLAG_ALIVE or GameConfig.FLAG_LIGHTS,
        grenades = 2,
        cooldown = 0f,
    )
}
