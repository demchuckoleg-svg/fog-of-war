package com.fogofwar.game.net

import com.fogofwar.game.core.Tile
import com.fogofwar.game.core.TileChange
import java.nio.ByteBuffer

enum class RoomState { IDLE, HOSTING, DISCOVERING, CONNECTING, LOBBY, IN_GAME }

data class PlayerInfo(
    val slot: Int,
    val name: String,
    val wins: Int = 0,
    val isBot: Boolean = false,
)

data class HostInfo(val endpointId: String, val name: String)

data class LobbyUiState(
    val roomState: RoomState = RoomState.IDLE,
    val isHost: Boolean = false,
    val mySlot: Int = 0,
    val players: List<PlayerInfo> = emptyList(),
    val mapId: Int = 0,
    val hosts: List<HostInfo> = emptyList(),
    val status: String? = null,
) {
    val inLobby: Boolean
        get() = roomState == RoomState.HOSTING || roomState == RoomState.LOBBY ||
            roomState == RoomState.CONNECTING || roomState == RoomState.DISCOVERING
}

sealed class NetMessage {
    data class Hello(val name: String) : NetMessage()
    data class Welcome(val slot: Int) : NetMessage()
    data class Roster(val roomState: RoomState, val mapId: Int, val players: List<PlayerInfo>) : NetMessage()
    data class StartRound(val mapId: Int, val seed: Long, val players: List<PlayerInfo>) : NetMessage()
    data class InputMsg(val seq: Int, val moveX: Float, val moveY: Float, val fire: Boolean) : NetMessage()
    data class SnapshotMsg(val snapshot: Snapshot) : NetMessage()
    data class RoundOver(val winnerSlot: Int, val winnerName: String, val players: List<PlayerInfo>) : NetMessage()
    data class MapSelect(val mapId: Int) : NetMessage()
    data class RoomClosed(val reason: String) : NetMessage()
}

/** Binary wire protocol, kept deliberately tiny and versioned by a single service id. */
object Protocol {
    const val VERSION = 1

    private const val MSG_HELLO = 1
    private const val MSG_WELCOME = 2
    private const val MSG_ROSTER = 3
    private const val MSG_START = 4
    private const val MSG_INPUT = 5
    private const val MSG_SNAPSHOT = 6
    private const val MSG_ROUND_OVER = 7
    private const val MSG_MAP_SELECT = 8
    private const val MSG_ROOM_CLOSED = 9

    // ---------------------------------------------------------------- encoding

    fun hello(name: String): ByteArray {
        val b = ByteBuffer.allocate(256)
        b.put(MSG_HELLO.toByte())
        putString(b, name)
        return finish(b)
    }

    fun welcome(slot: Int): ByteArray {
        val b = ByteBuffer.allocate(8)
        b.put(MSG_WELCOME.toByte())
        b.put(slot.toByte())
        return finish(b)
    }

    fun roster(state: RoomState, mapId: Int, players: List<PlayerInfo>): ByteArray {
        val b = ByteBuffer.allocate(512)
        b.put(MSG_ROSTER.toByte())
        b.put(state.ordinal.toByte())
        b.put(mapId.toByte())
        putPlayers(b, players)
        return finish(b)
    }

    fun startRound(mapId: Int, seed: Long, players: List<PlayerInfo>): ByteArray {
        val b = ByteBuffer.allocate(512)
        b.put(MSG_START.toByte())
        b.put(mapId.toByte())
        b.putLong(seed)
        putPlayers(b, players)
        return finish(b)
    }

    fun input(seq: Int, moveX: Float, moveY: Float, fire: Boolean): ByteArray {
        val b = ByteBuffer.allocate(16)
        b.put(MSG_INPUT.toByte())
        b.putInt(seq)
        b.put((moveX.coerceIn(-1f, 1f) * 127f).toInt().toByte())
        b.put((moveY.coerceIn(-1f, 1f) * 127f).toInt().toByte())
        b.put(if (fire) 1 else 0)
        return finish(b)
    }

    fun snapshot(snapshot: Snapshot): ByteArray {
        val size = 128 + snapshot.tanks.size * 20 + snapshot.bullets.size * 12 + snapshot.tileChanges.size * 8
        val b = ByteBuffer.allocate(size)
        b.put(MSG_SNAPSHOT.toByte())
        b.putInt(snapshot.tick.toInt())
        b.put(snapshot.tanks.size.toByte())
        for (t in snapshot.tanks) {
            b.put(t.slot.toByte())
            b.putFloat(t.x)
            b.putFloat(t.y)
            b.putFloat(t.dir)
            b.put(t.hp.toByte())
            b.put(if (t.alive) 1 else 0)
        }
        b.put(snapshot.bullets.size.toByte())
        for (bullet in snapshot.bullets) {
            b.putFloat(bullet.x)
            b.putFloat(bullet.y)
        }
        b.putFloat(snapshot.zoneRadius)
        b.putFloat(snapshot.sonarRadius)
        b.put(if (snapshot.sonarActive) 1 else 0)
        b.put(snapshot.tileChanges.size.toByte())
        for (change in snapshot.tileChanges) {
            b.putShort(change.index.toShort())
            b.put(change.tile.ordinal.toByte())
        }
        return finish(b)
    }

    fun roundOver(winnerSlot: Int, winnerName: String, players: List<PlayerInfo>): ByteArray {
        val b = ByteBuffer.allocate(512)
        b.put(MSG_ROUND_OVER.toByte())
        b.put(winnerSlot.toByte())
        putString(b, winnerName)
        putPlayers(b, players)
        return finish(b)
    }

    fun mapSelect(mapId: Int): ByteArray {
        val b = ByteBuffer.allocate(8)
        b.put(MSG_MAP_SELECT.toByte())
        b.put(mapId.toByte())
        return finish(b)
    }

    fun roomClosed(reason: String): ByteArray {
        val b = ByteBuffer.allocate(256)
        b.put(MSG_ROOM_CLOSED.toByte())
        putString(b, reason)
        return finish(b)
    }

    // ---------------------------------------------------------------- decoding

    fun parse(bytes: ByteArray): NetMessage? {
        return try {
            val b = ByteBuffer.wrap(bytes)
            when (b.get().toInt() and 0xFF) {
                MSG_HELLO -> NetMessage.Hello(readString(b))
                MSG_WELCOME -> NetMessage.Welcome(b.get().toInt() and 0xFF)
                MSG_ROSTER -> {
                    val state = readRoomState(b.get().toInt() and 0xFF)
                    val mapId = b.get().toInt() and 0xFF
                    NetMessage.Roster(state, mapId, readPlayers(b))
                }
                MSG_START -> {
                    val mapId = b.get().toInt() and 0xFF
                    val seed = b.long
                    NetMessage.StartRound(mapId, seed, readPlayers(b))
                }
                MSG_INPUT -> NetMessage.InputMsg(
                    b.int,
                    b.get() / 127f,
                    b.get() / 127f,
                    b.get().toInt() != 0,
                )
                MSG_SNAPSHOT -> NetMessage.SnapshotMsg(readSnapshot(b))
                MSG_ROUND_OVER -> NetMessage.RoundOver(
                    b.get().toInt(),
                    readString(b),
                    readPlayers(b),
                )
                MSG_MAP_SELECT -> NetMessage.MapSelect(b.get().toInt() and 0xFF)
                MSG_ROOM_CLOSED -> NetMessage.RoomClosed(readString(b))
                else -> null
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun readSnapshot(b: ByteBuffer): Snapshot {
        val tick = b.int.toLong()
        val tankCount = b.get().toInt() and 0xFF
        val tanks = ArrayList<TankSnap>(tankCount)
        repeat(tankCount) {
            tanks.add(
                TankSnap(
                    slot = b.get().toInt(),
                    x = b.float,
                    y = b.float,
                    dir = b.float,
                    hp = b.get().toInt(),
                    alive = b.get().toInt() != 0,
                )
            )
        }
        val bulletCount = b.get().toInt() and 0xFF
        val bullets = ArrayList<BulletSnap>(bulletCount)
        repeat(bulletCount) {
            bullets.add(BulletSnap(b.float, b.float))
        }
        val zoneRadius = b.float
        val sonarRadius = b.float
        val sonarActive = b.get().toInt() != 0
        val changeCount = b.get().toInt() and 0xFF
        val changes = ArrayList<TileChange>(changeCount)
        repeat(changeCount) {
            val index = b.short.toInt() and 0xFFFF
            val tile = Tile.fromOrdinal(b.get().toInt() and 0xFF)
            changes.add(TileChange(index, tile))
        }
        return Snapshot(tick, tanks, bullets, zoneRadius, sonarRadius, sonarActive, changes)
    }

    private fun readRoomState(ordinal: Int): RoomState =
        RoomState.values().getOrElse(ordinal) { RoomState.IDLE }

    private fun putPlayers(b: ByteBuffer, players: List<PlayerInfo>) {
        val list = players.take(16)
        b.put(list.size.toByte())
        for (p in list) {
            b.put(p.slot.toByte())
            putString(b, p.name)
            b.putShort(p.wins.toShort())
            b.put(if (p.isBot) 1 else 0)
        }
    }

    private fun readPlayers(b: ByteBuffer): List<PlayerInfo> {
        val count = b.get().toInt() and 0xFF
        val out = ArrayList<PlayerInfo>(count)
        repeat(count) {
            val slot = b.get().toInt() and 0xFF
            val name = readString(b)
            val wins = b.short.toInt()
            val bot = b.get().toInt() != 0
            out.add(PlayerInfo(slot, name, wins, bot))
        }
        return out
    }

    private fun putString(b: ByteBuffer, value: String) {
        val data = value.toByteArray(Charsets.UTF_8)
        val size = data.size.coerceAtMost(64)
        b.putShort(size.toShort())
        b.put(data, 0, size)
    }

    private fun readString(b: ByteBuffer): String {
        val size = b.short.toInt() and 0xFFFF
        val data = ByteArray(size)
        b.get(data)
        return String(data, Charsets.UTF_8)
    }

    private fun finish(b: ByteBuffer): ByteArray {
        b.flip()
        val out = ByteArray(b.remaining())
        b.get(out)
        return out
    }
}
