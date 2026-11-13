package com.fogofwar.game.net

import com.fogofwar.game.core.EffectEvent
import com.fogofwar.game.core.EffectKind
import com.fogofwar.game.core.RoundRules
import com.fogofwar.game.core.Tile
import com.fogofwar.game.core.TileChange
import java.nio.ByteBuffer

enum class RoomState { IDLE, HOSTING, DISCOVERING, CONNECTING, LOBBY, IN_GAME }

data class PlayerInfo(
    val slot: Int,
    val name: String,
    val wins: Int = 0,
    val isBot: Boolean = false,
    val ready: Boolean = false,
    val team: Int = 0,
)

/**
 * A room found by discovery. [mapId], [players], [inGame] and [code] come from the
 * advertising endpoint name ([RoomDescriptor]) and are `null`/empty for old hosts that
 * advertise a plain nickname.
 */
data class HostInfo(
    val endpointId: String,
    val name: String,
    val mapId: Int? = null,
    val players: Int = 0,
    val inGame: Boolean = false,
    val code: String = "",
) {
    companion object {
        fun fromEndpoint(endpointId: String, endpointName: String): HostInfo {
            val raw = endpointName.trim().ifBlank { "Комната" }
            val descriptor = RoomDescriptor.parse(raw) ?: return HostInfo(endpointId, raw)
            return HostInfo(
                endpointId = endpointId,
                name = descriptor.name,
                mapId = descriptor.mapId,
                players = descriptor.players,
                inGame = descriptor.inGame,
                code = descriptor.code,
            )
        }
    }
}

data class LobbyUiState(
    val roomState: RoomState = RoomState.IDLE,
    val isHost: Boolean = false,
    val mySlot: Int = 0,
    val players: List<PlayerInfo> = emptyList(),
    val mapId: Int = 0,
    val hosts: List<HostInfo> = emptyList(),
    val roomCode: String = "",
    val status: String? = null,
    val rules: RoundRules = RoundRules.CLASSIC,
) {
    val inLobby: Boolean
        get() = roomState == RoomState.HOSTING || roomState == RoomState.LOBBY ||
            roomState == RoomState.CONNECTING || roomState == RoomState.DISCOVERING
}

sealed class NetMessage {
    data class Hello(val name: String, val version: Int = Protocol.VERSION) : NetMessage()
    data class Welcome(val slot: Int, val version: Int = Protocol.VERSION) : NetMessage()
    data class Roster(
        val roomState: RoomState,
        val mapId: Int,
        val players: List<PlayerInfo>,
        val rules: RoundRules = RoundRules.CLASSIC,
    ) : NetMessage()
    data class StartRound(
        val mapId: Int,
        val seed: Long,
        val players: List<PlayerInfo>,
        val rules: RoundRules = RoundRules.CLASSIC,
    ) : NetMessage()
    data class InputMsg(
        val seq: Int,
        val moveX: Float,
        val moveY: Float,
        val aimX: Float,
        val aimY: Float,
        val fire: Boolean,
        val lights: Boolean,
        val throwGrenade: Boolean,
    ) : NetMessage()
    data class SnapshotMsg(val snapshot: Snapshot) : NetMessage()
    data class TileSync(val changes: List<TileChange>) : NetMessage()
    data class RoundOver(
        val winnerSlot: Int,
        val winnerTeam: Int,
        val winnerName: String,
        val players: List<PlayerInfo>,
        val results: List<RoundResult> = emptyList(),
    ) : NetMessage()
    data class MapSelect(val mapId: Int) : NetMessage()
    data class RoomClosed(val reason: String) : NetMessage()
    data class SetReady(val ready: Boolean) : NetMessage()
    data class Kick(val slot: Int, val reason: String) : NetMessage()
}

/** Binary wire protocol. Bump [VERSION] when the format changes. */
object Protocol {
    const val VERSION = 5

    private const val MSG_HELLO = 1
    private const val MSG_WELCOME = 2
    private const val MSG_ROSTER = 3
    private const val MSG_START = 4
    private const val MSG_INPUT = 5
    private const val MSG_SNAPSHOT = 6
    private const val MSG_ROUND_OVER = 7
    private const val MSG_MAP_SELECT = 8
    private const val MSG_ROOM_CLOSED = 9
    private const val MSG_TILE_SYNC = 10
    private const val MSG_SET_READY = 11
    private const val MSG_KICK = 12

    private const val INPUT_FIRE = 1
    private const val INPUT_LIGHTS = 2
    private const val INPUT_GRENADE = 4

    /** Nicknames are capped well below this; the limit only protects the buffer. */
    private const val MAX_STRING_BYTES = 64

    // ---------------------------------------------------------------- encoding

    fun hello(name: String): ByteArray {
        val b = ByteBuffer.allocate(256)
        b.put(MSG_HELLO.toByte())
        b.put(VERSION.toByte())
        putString(b, name)
        return finish(b)
    }

    fun welcome(slot: Int): ByteArray {
        val b = ByteBuffer.allocate(8)
        b.put(MSG_WELCOME.toByte())
        b.put(VERSION.toByte())
        b.put(slot.toByte())
        return finish(b)
    }

    fun roster(
        state: RoomState,
        mapId: Int,
        players: List<PlayerInfo>,
        rules: RoundRules,
    ): ByteArray {
        val b = ByteBuffer.allocate(80 + players.size * 80)
        b.put(MSG_ROSTER.toByte())
        b.put(state.ordinal.toByte())
        b.put(mapId.toByte())
        putRules(b, rules)
        putPlayers(b, players)
        return finish(b)
    }

    fun startRound(
        mapId: Int,
        seed: Long,
        players: List<PlayerInfo>,
        rules: RoundRules,
    ): ByteArray {
        val b = ByteBuffer.allocate(96 + players.size * 80)
        b.put(MSG_START.toByte())
        b.put(mapId.toByte())
        b.putLong(seed)
        putRules(b, rules)
        putPlayers(b, players)
        return finish(b)
    }

    fun setReady(ready: Boolean): ByteArray {
        val b = ByteBuffer.allocate(4)
        b.put(MSG_SET_READY.toByte())
        b.put(if (ready) 1 else 0)
        return finish(b)
    }

    fun kick(slot: Int, reason: String): ByteArray {
        val b = ByteBuffer.allocate(64)
        b.put(MSG_KICK.toByte())
        b.put(slot.toByte())
        putString(b, reason)
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
        throwGrenade: Boolean,
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
        if (throwGrenade) flags = flags or INPUT_GRENADE
        b.put(flags.toByte())
        return finish(b)
    }

    fun snapshot(snapshot: Snapshot): ByteArray {
        val size = 32 +
            snapshot.tanks.size * 26 +
            snapshot.bullets.size * 8 +
            snapshot.pickups.size * 9 +
            snapshot.grenades.size * 10 +
            snapshot.events.size * 13 +
            snapshot.kills.size * 2
        val b = ByteBuffer.allocate(size)
        b.put(MSG_SNAPSHOT.toByte())
        b.putInt(snapshot.tick.toInt())

        b.putShort(snapshot.tanks.size.toShort())
        for (t in snapshot.tanks) {
            b.put(t.slot.toByte())
            b.putFloat(t.x)
            b.putFloat(t.y)
            b.putFloat(t.bodyDir)
            b.putFloat(t.turretDir)
            b.put(t.hp.toByte())
            b.put(t.flags.toByte())
            b.put(t.grenades.toByte())
            b.put((t.cooldown.coerceIn(0f, 1f) * 255f).toInt().toByte())
            b.put((t.grenadeRecharge.coerceIn(0f, 1f) * 255f).toInt().toByte())
            b.put(t.team.toByte())
            b.put(t.lives.toByte())
            b.put((t.powerLeft.coerceIn(0f, 1f) * 255f).toInt().toByte())
            b.put((t.lightLeft.coerceIn(0f, 1f) * 255f).toInt().toByte())
        }

        b.putShort(snapshot.bullets.size.toShort())
        for (bullet in snapshot.bullets) {
            b.putFloat(bullet.x)
            b.putFloat(bullet.y)
        }

        b.putShort(snapshot.pickups.size.toShort())
        for (pickup in snapshot.pickups) {
            b.put(pickup.type.toByte())
            b.putFloat(pickup.x)
            b.putFloat(pickup.y)
        }

        b.putShort(snapshot.grenades.size.toShort())
        for (grenade in snapshot.grenades) {
            b.putFloat(grenade.x)
            b.putFloat(grenade.y)
            b.put(if (grenade.landed) 1 else 0)
            b.put(grenade.owner.toByte())
        }

        b.putFloat(snapshot.zoneRadius)
        b.putFloat(snapshot.sonarRadius)
        b.put(if (snapshot.sonarActive) 1 else 0)

        b.putShort(snapshot.events.size.toShort())
        for (event in snapshot.events) {
            b.put(event.kind.ordinal.toByte())
            b.putFloat(event.x)
            b.putFloat(event.y)
            b.putFloat(event.radius)
        }

        b.putShort(snapshot.kills.size.toShort())
        for (kill in snapshot.kills) {
            b.put(kill.killer.toByte())
            b.put(kill.victim.toByte())
        }
        return finish(b)
    }

    /**
     * Tile changes travel separately from snapshots: one bad snapshot can never lose a
     * piece of the map, and a long grenade chain can be split into several reliable messages.
     */
    fun tileSync(changes: List<TileChange>): ByteArray {
        val b = ByteBuffer.allocate(3 + changes.size * 3)
        b.put(MSG_TILE_SYNC.toByte())
        b.putShort(changes.size.toShort())
        for (change in changes) {
            b.putShort(change.index.toShort())
            b.put(change.tile.ordinal.toByte())
        }
        return finish(b)
    }

    fun roundOver(
        winnerSlot: Int,
        winnerTeam: Int,
        winnerName: String,
        players: List<PlayerInfo>,
        results: List<RoundResult>,
    ): ByteArray {
        val b = ByteBuffer.allocate(112 + players.size * 80 + results.size * 5)
        b.put(MSG_ROUND_OVER.toByte())
        b.put(winnerSlot.toByte())
        b.put(winnerTeam.toByte())
        putString(b, winnerName)
        putPlayers(b, players)
        b.put(results.size.toByte())
        for (result in results) {
            b.put(result.slot.toByte())
            b.put(result.kills.toByte())
            b.put(result.deaths.toByte())
            b.putShort(result.damage.toShort())
        }
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
                MSG_HELLO -> {
                    val version = b.get().toInt() and 0xFF
                    NetMessage.Hello(name = readString(b), version = version)
                }
                MSG_WELCOME -> {
                    val version = b.get().toInt() and 0xFF
                    NetMessage.Welcome(slot = b.get().toInt() and 0xFF, version = version)
                }
                MSG_ROSTER -> {
                    val state = readRoomState(b.get().toInt() and 0xFF)
                    val mapId = b.get().toInt() and 0xFF
                    val rules = readRules(b)
                    NetMessage.Roster(state, mapId, readPlayers(b), rules)
                }
                MSG_START -> {
                    val mapId = b.get().toInt() and 0xFF
                    val seed = b.long
                    val rules = readRules(b)
                    NetMessage.StartRound(mapId, seed, readPlayers(b), rules)
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
                        throwGrenade = flags and INPUT_GRENADE != 0,
                    )
                }
                MSG_SNAPSHOT -> NetMessage.SnapshotMsg(readSnapshot(b))
                MSG_TILE_SYNC -> NetMessage.TileSync(readTileChanges(b))
                MSG_ROUND_OVER -> {
                    val winnerSlot = b.get().toInt()
                    val winnerTeam = b.get().toInt()
                    val winnerName = readString(b)
                    val players = readPlayers(b)
                    val resultCount = b.get().toInt() and 0xFF
                    val results = ArrayList<RoundResult>(resultCount)
                    repeat(resultCount) {
                        results.add(
                            RoundResult(
                                slot = b.get().toInt() and 0xFF,
                                kills = b.get().toInt() and 0xFF,
                                deaths = b.get().toInt() and 0xFF,
                                damage = b.short.toInt() and 0xFFFF,
                            )
                        )
                    }
                    NetMessage.RoundOver(winnerSlot, winnerTeam, winnerName, players, results)
                }
                MSG_MAP_SELECT -> NetMessage.MapSelect(b.get().toInt() and 0xFF)
                MSG_ROOM_CLOSED -> NetMessage.RoomClosed(readString(b))
                MSG_SET_READY -> NetMessage.SetReady(b.get().toInt() != 0)
                MSG_KICK -> NetMessage.Kick(
                    slot = b.get().toInt() and 0xFF,
                    reason = readString(b),
                )
                else -> null
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun readSnapshot(b: ByteBuffer): Snapshot {
        val tick = b.int.toLong()

        val tankCount = b.short.toInt() and 0xFFFF
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
                    grenades = b.get().toInt() and 0xFF,
                    cooldown = (b.get().toInt() and 0xFF) / 255f,
                    grenadeRecharge = (b.get().toInt() and 0xFF) / 255f,
                    team = b.get().toInt() and 0xFF,
                    lives = b.get().toInt() and 0xFF,
                    powerLeft = (b.get().toInt() and 0xFF) / 255f,
                    lightLeft = (b.get().toInt() and 0xFF) / 255f,
                )
            )
        }

        val bulletCount = b.short.toInt() and 0xFFFF
        val bullets = ArrayList<BulletSnap>(bulletCount)
        repeat(bulletCount) {
            bullets.add(BulletSnap(b.float, b.float))
        }

        val pickupCount = b.short.toInt() and 0xFFFF
        val pickups = ArrayList<PickupSnap>(pickupCount)
        repeat(pickupCount) {
            pickups.add(PickupSnap(b.get().toInt() and 0xFF, b.float, b.float))
        }

        val grenadeCount = b.short.toInt() and 0xFFFF
        val grenades = ArrayList<GrenadeSnap>(grenadeCount)
        repeat(grenadeCount) {
            grenades.add(GrenadeSnap(b.float, b.float, b.get().toInt() != 0, b.get().toInt() and 0xFF))
        }

        val zoneRadius = b.float
        val sonarRadius = b.float
        val sonarActive = b.get().toInt() != 0

        val eventCount = b.short.toInt() and 0xFFFF
        val events = ArrayList<EffectEvent>(eventCount)
        repeat(eventCount) {
            val kind = EffectKind.values().getOrElse(b.get().toInt() and 0xFF) { EffectKind.SHOT }
            val x = b.float
            val y = b.float
            val radius = b.float
            events.add(EffectEvent(kind, x, y, radius))
        }

        val killCount = b.short.toInt() and 0xFFFF
        val kills = ArrayList<KillSnap>(killCount)
        repeat(killCount) {
            kills.add(KillSnap(killer = b.get().toInt(), victim = b.get().toInt() and 0xFF))
        }

        return Snapshot(
            tick = tick,
            tanks = tanks,
            bullets = bullets,
            pickups = pickups,
            grenades = grenades,
            zoneRadius = zoneRadius,
            sonarRadius = sonarRadius,
            sonarActive = sonarActive,
            events = events,
            kills = kills,
        )
    }

    private fun readTileChanges(b: ByteBuffer): List<TileChange> {
        val count = b.short.toInt() and 0xFFFF
        val changes = ArrayList<TileChange>(count)
        repeat(count) {
            val index = b.short.toInt() and 0xFFFF
            changes.add(TileChange(index, Tile.fromOrdinal(b.get().toInt() and 0xFF)))
        }
        return changes
    }

    private fun readRoomState(ordinal: Int): RoomState =
        RoomState.values().getOrElse(ordinal) { RoomState.IDLE }

    private fun putPlayers(b: ByteBuffer, players: List<PlayerInfo>) {
        b.put(players.size.toByte())
        for (p in players) {
            b.put(p.slot.toByte())
            var flags = 0
            if (p.isBot) flags = flags or 1
            if (p.ready) flags = flags or 2
            b.put(flags.toByte())
            b.put(p.team.toByte())
            b.putShort(p.wins.toShort())
            putString(b, p.name)
        }
    }

    private fun readPlayers(b: ByteBuffer): List<PlayerInfo> {
        val count = b.get().toInt() and 0xFF
        val out = ArrayList<PlayerInfo>(count)
        repeat(count) {
            val slot = b.get().toInt() and 0xFF
            val flags = b.get().toInt() and 0xFF
            val team = b.get().toInt() and 0xFF
            val wins = b.short.toInt()
            val name = readString(b)
            out.add(
                PlayerInfo(
                    slot = slot,
                    name = name,
                    wins = wins,
                    isBot = flags and 1 != 0,
                    ready = flags and 2 != 0,
                    team = team,
                )
            )
        }
        return out
    }

    private fun putRules(b: ByteBuffer, rules: RoundRules) {
        var flags = 0
        if (rules.teams) flags = flags or 1
        if (rules.pickups) flags = flags or 2
        b.put(flags.toByte())
        b.put(rules.lives.toByte())
        b.putShort(rules.timeLimitSec.toShort())
    }

    private fun readRules(b: ByteBuffer): RoundRules {
        val flags = b.get().toInt() and 0xFF
        val lives = b.get().toInt() and 0xFF
        val timeLimit = b.short.toInt() and 0xFFFF
        return RoundRules(
            teams = flags and 1 != 0,
            pickups = flags and 2 != 0,
            lives = lives.coerceAtLeast(1),
            timeLimitSec = timeLimit,
        )
    }

    private fun putString(b: ByteBuffer, value: String) {
        var text = value
        var data = text.toByteArray(Charsets.UTF_8)
        // Never cut a multi-byte character in half: trim whole code points instead.
        while (data.size > MAX_STRING_BYTES && text.isNotEmpty()) {
            text = text.substring(0, text.offsetByCodePoints(text.length, -1))
            data = text.toByteArray(Charsets.UTF_8)
        }
        b.putShort(data.size.toShort())
        b.put(data)
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
