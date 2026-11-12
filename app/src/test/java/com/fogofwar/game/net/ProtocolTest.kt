package com.fogofwar.game.net

import com.fogofwar.game.core.EffectEvent
import com.fogofwar.game.core.EffectKind
import com.fogofwar.game.core.GameConfig
import com.fogofwar.game.core.Tile
import com.fogofwar.game.core.TileChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                TankSnap(
                    0, 100.5f, 200.25f, 0.2f, 1.2f, 3,
                    GameConfig.FLAG_ALIVE or GameConfig.FLAG_LIGHTS or GameConfig.FLAG_MOVING,
                    2, 0.5f,
                ),
                TankSnap(1, 900f, 910f, -0.4f, -0.9f, 0, 0, 0, 0f),
            ),
            bullets = listOf(BulletSnap(300f, 400f), BulletSnap(500f, 600f)),
            pickups = listOf(PickupSnap(1, 350f, 360f)),
            mines = listOf(MineSnap(700f, 710f, true, 0)),
            zoneRadius = 900f,
            sonarRadius = 250f,
            sonarActive = true,
            events = listOf(EffectEvent(EffectKind.EXPLOSION, 10f, 20f, 64f)),
        )
        val message = Protocol.parse(Protocol.snapshot(snapshot))
        assertTrue(message is NetMessage.SnapshotMsg)
        val parsed = (message as NetMessage.SnapshotMsg).snapshot
        assertEquals(123L, parsed.tick)
        assertEquals(2, parsed.tanks.size)
        assertEquals(100.5f, parsed.tanks[0].x, 0.001f)
        assertEquals(200.25f, parsed.tanks[0].y, 0.001f)
        assertEquals(1.2f, parsed.tanks[0].turretDir, 0.001f)
        assertEquals(2, parsed.tanks[0].mines)
        assertTrue(parsed.tanks[0].alive)
        assertTrue(parsed.tanks[0].lightsOn)
        assertTrue(parsed.tanks[0].moving)
        assertFalse(parsed.tanks[0].invulnerable)
        assertFalse(parsed.tanks[1].alive)
        assertEquals(2, parsed.bullets.size)
        assertEquals(1, parsed.pickups.size)
        assertEquals(1, parsed.pickups[0].type)
        assertEquals(1, parsed.mines.size)
        assertTrue(parsed.mines[0].armed)
        assertEquals(900f, parsed.zoneRadius, 0.001f)
        assertTrue(parsed.sonarActive)
        assertEquals(1, parsed.events.size)
        assertEquals(EffectKind.EXPLOSION, parsed.events[0].kind)
        assertEquals(64f, parsed.events[0].radius, 0.001f)
    }

    @Test
    fun tileSyncRoundTripsHundredsOfChanges() {
        // More than the old single-byte counter could express: must not wrap or corrupt.
        val changes = (0 until 300).map { TileChange(it, if (it % 2 == 0) Tile.CRACKED else Tile.EMPTY) }

        val message = Protocol.parse(Protocol.tileSync(changes))

        assertTrue(message is NetMessage.TileSync)
        val parsed = (message as NetMessage.TileSync).changes
        assertEquals(300, parsed.size)
        assertEquals(299, parsed.last().index)
        assertEquals(Tile.EMPTY, parsed.last().tile)
    }

    @Test
    fun snapshotHandlesHundredsOfEvents() {
        val events = (0 until 300).map {
            EffectEvent(EffectKind.SHOT, it.toFloat(), it.toFloat() * 2, 0f)
        }
        val snapshot = Snapshot(
            tick = 7,
            tanks = emptyList(),
            bullets = (0 until 300).map { BulletSnap(it.toFloat(), it.toFloat()) },
            pickups = emptyList(),
            mines = emptyList(),
            zoneRadius = 500f,
            sonarRadius = 0f,
            sonarActive = false,
            events = events,
        )

        val parsed = (Protocol.parse(Protocol.snapshot(snapshot)) as NetMessage.SnapshotMsg).snapshot

        assertEquals(300, parsed.bullets.size)
        assertEquals(300, parsed.events.size)
        assertEquals(299f, parsed.events.last().x, 0.001f)
    }

    @Test
    fun helloAndWelcomeCarryProtocolVersion() {
        val hello = Protocol.parse(Protocol.hello("Олег")) as NetMessage.Hello
        assertEquals("Олег", hello.name)
        assertEquals(Protocol.VERSION, hello.version)

        val welcome = Protocol.parse(Protocol.welcome(3)) as NetMessage.Welcome
        assertEquals(3, welcome.slot)
        assertEquals(Protocol.VERSION, welcome.version)
    }

    @Test
    fun inputRoundTripHandlesBothSticksAndFlags() {
        val message = Protocol.parse(Protocol.input(7, 1f, -1f, 0.5f, 0.25f, true, false, true))
        assertNotNull(message)
        val input = message as NetMessage.InputMsg
        assertEquals(7, input.seq)
        assertEquals(1f, input.moveX, 0.01f)
        assertEquals(-1f, input.moveY, 0.01f)
        assertEquals(0.5f, input.aimX, 0.01f)
        assertEquals(0.25f, input.aimY, 0.01f)
        assertTrue(input.fire)
        assertFalse(input.lights)
        assertTrue(input.dropMine)
    }

    @Test
    fun malformedBytesReturnNull() {
        assertEquals(null, Protocol.parse(byteArrayOf(99, 1, 2, 3)))
        assertEquals(null, Protocol.parse(ByteArray(0)))
    }
}
