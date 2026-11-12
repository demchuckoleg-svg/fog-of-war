package com.fogofwar.game.net

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
                    TankSnap(0, 400f, 400f, 0f, 3, true),
                    TankSnap(1, 500f, 500f, 1f, 2, true),
                ),
                bullets = listOf(BulletSnap(450f, 450f)),
                zoneRadius = 800f,
                sonarRadius = 0f,
                sonarActive = false,
                tileChanges = emptyList(),
            )
        )
        val frame = round.frameAt(System.nanoTime() / 1_000_000L)
        assertNotNull(frame)
        assertEquals(2, frame!!.tanks.size)
        assertEquals(1, frame.mySlot)
        assertEquals("guest", frame.me?.name)
        assertEquals(800f, frame.zoneRadius, 0.001f)
    }

    @Test
    fun interpolationBetweenTwoSnapshotsIsBetweenPositions() {
        val map = MapGenerator.generate(0, 2L)
        val round = ClientRound(map, 0, 0, players)
        round.onSnapshot(snapshotAt(tick = 1, x = 100f))
        round.onSnapshot(snapshotAt(tick = 2, x = 300f))
        // Two snapshots received at almost the same moment: any delayed frame is safe.
        val frame = round.frameAt(System.nanoTime() / 1_000_000L)
        assertNotNull(frame)
        val x = frame!!.tanks[0].x
        assertTrue("x=$x", x in 100f..300f)
    }

    @Test
    fun tileChangesAreAppliedToTheClientMap() {
        val map = MapGenerator.generate(0, 3L)
        map.set(10, 10, Tile.BRICK)
        val round = ClientRound(map, 0, 0, players)
        round.onSnapshot(
            snapshotAt(tick = 1, x = 100f).copy(
                tileChanges = listOf(TileChange(map.index(10, 10), Tile.CRACKED))
            )
        )
        assertEquals(Tile.CRACKED, map.tileAt(10, 10))
    }

    @Test
    fun roundOverFlagAndWinnerAreExposed() {
        val map = MapGenerator.generate(0, 4L)
        val round = ClientRound(map, 0, 0, players)
        round.onSnapshot(snapshotAt(tick = 1, x = 100f))
        round.setRoundOver("host")
        val frame = round.frameAt(System.nanoTime() / 1_000_000L)
        assertTrue(frame!!.roundOver)
        assertEquals("host", frame.winnerName)
    }

    private fun snapshotAt(tick: Long, x: Float) = Snapshot(
        tick = tick,
        tanks = listOf(TankSnap(0, x, 200f, 0f, 3, true)),
        bullets = emptyList(),
        zoneRadius = 900f,
        sonarRadius = 0f,
        sonarActive = false,
        tileChanges = emptyList(),
    )
}
