package com.fogofwar.game.net

import com.fogofwar.game.core.Tile
import com.fogofwar.game.core.TileChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test
    fun rosterRoundTrip() {
        val players = listOf(
            PlayerInfo(0, "Хост", 2),
            PlayerInfo(1, "Бот 1", 0, isBot = true),
        )
        val bytes = Protocol.roster(RoomState.LOBBY, 2, players)
        val message = Protocol.parse(bytes)
        assertTrue(message is NetMessage.Roster)
        val roster = message as NetMessage.Roster
        assertEquals(RoomState.LOBBY, roster.roomState)
        assertEquals(2, roster.mapId)
        assertEquals(2, roster.players.size)
        assertEquals("Хост", roster.players[0].name)
        assertTrue(roster.players[1].isBot)
    }

    @Test
    fun snapshotRoundTrip() {
        val snapshot = Snapshot(
            tick = 123,
            tanks = listOf(
                TankSnap(0, 100.5f, 200.25f, 1.2f, 3, true),
                TankSnap(1, 900f, 910f, -0.4f, 0, false),
            ),
            bullets = listOf(BulletSnap(300f, 400f), BulletSnap(500f, 600f)),
            zoneRadius = 900f,
            sonarRadius = 250f,
            sonarActive = true,
            tileChanges = listOf(TileChange(42, Tile.CRACKED), TileChange(43, Tile.EMPTY)),
        )
        val message = Protocol.parse(Protocol.snapshot(snapshot))
        assertTrue(message is NetMessage.SnapshotMsg)
        val parsed = (message as NetMessage.SnapshotMsg).snapshot
        assertEquals(123L, parsed.tick)
        assertEquals(2, parsed.tanks.size)
        assertEquals(100.5f, parsed.tanks[0].x, 0.001f)
        assertEquals(200.25f, parsed.tanks[0].y, 0.001f)
        assertEquals(0, parsed.tanks[1].hp)
        assertEquals(false, parsed.tanks[1].alive)
        assertEquals(2, parsed.bullets.size)
        assertEquals(900f, parsed.zoneRadius, 0.001f)
        assertTrue(parsed.sonarActive)
        assertEquals(Tile.CRACKED, parsed.tileChanges[0].tile)
        assertEquals(43, parsed.tileChanges[1].index)
    }

    @Test
    fun inputRoundTripHandlesFullStick() {
        val message = Protocol.parse(Protocol.input(7, 1f, -1f, true))
        assertNotNull(message)
        val input = message as NetMessage.InputMsg
        assertEquals(7, input.seq)
        assertEquals(1f, input.moveX, 0.01f)
        assertEquals(-1f, input.moveY, 0.01f)
        assertTrue(input.fire)
    }

    @Test
    fun malformedBytesReturnNull() {
        assertEquals(null, Protocol.parse(byteArrayOf(99, 1, 2, 3)))
        assertEquals(null, Protocol.parse(ByteArray(0)))
    }
}
