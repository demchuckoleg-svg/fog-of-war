package com.fogofwar.game.net

import com.fogofwar.game.core.EffectEvent
import com.fogofwar.game.core.EffectKind
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
    data class InputMsg(
        val seq: Int,
        val moveX: Float,
        val moveY: Float,
        val aimX: Float,
        val aimY: Float,
        val fire: Boolean,
        val lights: Boolean,
        val dropMine: Boolean,
    ) : NetMessage()
    data class SnapshotMsg(val snapshot: Snapshot) : NetMessage()
    data class RoundOver(val winnerSlot: Int, val winnerName: String, val players: List<PlayerInfo>) : NetMessage()
    data class MapSelect(val mapId: Int) : NetMessage()
    data class RoomClosed(val reason: String) : NetMessage()
}

/** Binary wire protocol. Bump VERSION + SERVICE_ID when the format changes. */
object Protocol {
    const val VERSION = 3

    private const val MSG_HELLO = 1
    private const val MSG_WELCOME = 2
    private const val MSG_ROSTER = 3
    private const val MSG_START = 4
    private const val MSG_INPUT = 5
    private const val MSG_SNAPSHOT = 6
    private const val MSG_ROUND_OVER = 7
    private const val MSG_MAP_SELECT = 8
    private const val MSG_ROOM_CLOSED = 9

    private const val INPUT_FIRE = 1
    private const val INPUT_LIGHTS = 2
    private const val INPUT_DROP = 4

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

    fun input(
        seq: Int,
        moveX: Float,
        moveY: Float,
        aimX: Float,
        aimY: Float,
        fire: Boolean,
        lights: Boolean,
        dropMine: Boolean,
    ): ByteArray {
        val b = ByteBuffer.allocate(16)
        b.put(MSG_INPUT.toByte())
        b.putInt(seq)
        b.put((moveX.coerceIn(-1f, 1f) * 127f).toInt().toByte())
        b.put((moveY.coerceIn(-1f, 1f) * 127f).toInt().toByte())
        b.put((aimX.coerceIn(-1f, 1f) * 127f).toInt().toByte())
        b.put((aimY.coerceIn(-1f, 1f) * 127f).toInt().toByte())
        var flags = 0
        if (fire) flags = flags or INPUT_FIRE
        if (lights) flags = flags or INPUT_LIGHTS
        if (dropMine) flags = flags or INPUT_DROP
        b.put(flags.toByte())
        return finish(b)
    }

    fun snapshot(snapshot: Snapshot): ByteArray {
        val size = 160 +
            snapshot.tanks.size * 33 +
            snapshot.bullets.size * 10 +
            snapshot.pickups.size * 12 +
            snapshot.mines.size * 12 +
            snapshot.tileChanges.size * 4 +
            snapshot.events.size * 16
        val b = ByteBuffer.allocate(size)
        b.put(MSG_SNAPSHOT.toByte())
        b.putInt(snapshot.tick.toInt())

        b.put(snapshot.tanks.size.toByte())
        for (t in snapshot.tanks) {
            b.put(t.slot.toByte())
            b.putFloat(t.x)
            b.putFloat(t.y)
            b.putFloat(t.bodyDir)
            b.putFloat(t.turretDir)
            b.put(t.hp.toByte())
            b.put(t.flags.toByte())
            b.put(t.mines.toByte())
            b.put((t.cooldown.coerceIn(0f, 1f) * 255f).toInt().toByte())
            b.put((t.mineRecharge.coerceIn(0f, 1f) * 255f).toInt().toByte())
        }

        b.put(snapshot.bullets.size.toByte())
        for (bullet in snapshot.bullets) {
            b.putFloat(bullet.x)
            b.putFloat(bullet.y)
        }

        b.put(snapshot.pickups.size.toByte())
        for (pickup in snapshot.pickups) {
            b.put(pickup.type.toByte())
            b.putFloat(pickup.x)
            b.putFloat(pickup.y)
        }

        b.put(snapshot.mines.size.toByte())
        for (mine in snapshot.mines) {
            b.putFloat(mine.x)
            b.putFloat(mine.y)
            b.put(if (mine.armed) 1 else 0)
            b.put(mine.owner.toByte())
        }

        b.putFloat(snapshot.zoneRadius)
        b.putFloat(snapshot.sonarRadius)
        b.put(if (snapshot.sonarActive) 1 else 0)

        b.put(snapshot.tileChanges.size.toByte())
        for (change in snapshot.tileChanges) {
            b.putShort(change.index.toShort())
            b.put(change.tile.ordinal.toByte())
        }

        b.put(snapshot.events.size.toByte())
        for (event in snapshot.events) {
            b.put(event.kind.ordinal.toByte())
            b.putFloat(event.x)
            b.putFloat(event.y)
            b.putFloat(event.radius)
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
                MSG_INPUT -> {
                    val seq = b.int
                    val moveX = b.get() / 127f
                    val moveY = b.get() / 127f
                    val aimX = b.get() / 127f
                    val aimY = b.get() / 127f
                    val flags = b.get().toInt() and 0xFF
                    NetMessage.InputMsg(
                        seq = seq,
                        moveX = moveX,
                        moveY = moveY,
                        aimX = aimX,
                        aimY = aimY,
                        fire = flags and INPUT_FIRE != 0,
                        lights = flags and INPUT_LIGHTS != 0,
                        dropMine = flags and INPUT_DROP != 0,
                    )
                }
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
                    bodyDir = b.float,
                    turretDir = b.float,
                    hp = b.get().toInt(),
                    flags = b.get().toInt() and 0xFF,
                    mines = b.get().toInt() and 0xFF,
                    cooldown = (b.get().toInt() and 0xFF) / 255f,
                    mineRecharge = (b.get().toInt() and 0xFF) / 255f,
                )
            )
        }

        val bulletCount = b.get().toInt() and 0xFF
        val bullets = ArrayList<BulletSnap>(bulletCount)
        repeat(bulletCount) {
            bullets.add(BulletSnap(b.float, b.float))
        }

        val pickupCount = b.get().toInt() and 0xFF
        val pickups = ArrayList<PickupSnap>(pickupCount)
        repeat(pickupCount) {
            pickups.add(PickupSnap(b.get().toInt() and 0xFF, b.float, b.float))
        }

        val mineCount = b.get().toInt() and 0xFF
        val mines = ArrayList<MineSnap>(mineCount)
        repeat(mineCount) {
            mines.add(MineSnap(b.float, b.float, b.get().toInt() != 0, b.get().toInt() and 0xFF))
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

        val eventCount = b.get().toInt() and 0xFF
        val events = ArrayList<EffectEvent>(eventCount)
        repeat(eventCount) {
            val kind = EffectKind.values().getOrElse(b.get().toInt() and 0xFF) { EffectKind.SHOT }
            val x = b.float
            val y = b.float
            val radius = b.float
            events.add(EffectEvent(kind, x, y, radius))
        }

        return Snapshot(
            tick = tick,
            tanks = tanks,
            bullets = bullets,
            pickups = pickups,
            mines = mines,
            zoneRadius = zoneRadius,
            sonarRadius = sonarRadius,
            sonarActive = sonarActive,
            tileChanges = changes,
            events = events,
        )
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
