package com.fogofwar.game.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fogofwar.game.core.EffectEvent
import com.fogofwar.game.core.GameConfig
import com.fogofwar.game.core.MapGenerator
import com.fogofwar.game.core.TankInput
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

/**
 * Application-wide Nearby Connections session. It owns the persistent lobby:
 * endpoints stay connected between rounds, so players never re-join the room.
 *
 * Host is authoritative: it maps endpoint ids to player slots and runs the world.
 * Clients only send input and render snapshots.
 */
class NearbyNet(context: Context) {
    interface Listener {
        fun onLobbyChanged(state: LobbyUiState)
        fun onRoundStarted(mapId: Int)
        fun onRoundEnded(winnerName: String?)
        fun onNetworkError(message: String)
    }

    companion object {
        private const val TAG = "NearbyNet"
        const val SERVICE_ID = "com.fogofwar.game.nearby.v2"
    }

    private val client: ConnectionsClient? = try {
        Nearby.getConnectionsClient(context.applicationContext)
    } catch (t: Throwable) {
        Log.e(TAG, "Play Services unavailable", t)
        null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    var nickname: String = "Танк"
    @Volatile
    var isHost: Boolean = false
        private set
    @Volatile
    var roomState: RoomState = RoomState.IDLE
        private set
    var mySlot: Int = 0
        private set
    var mapId: Int = 0
        private set
    var listener: Listener? = null

    @Volatile
    var hostRound: HostRound? = null
        private set
    @Volatile
    var clientRound: ClientRound? = null
        private set
    var lastWinner: String? = null
        private set

    private val humans = LinkedHashMap<Int, PlayerInfo>()
    private val endpointBySlot = HashMap<Int, String>()
    private val slotByEndpoint = HashMap<String, Int>()
    private val pendingNames = HashMap<String, String>()
    private val discovered = LinkedHashMap<String, String>()
    @Volatile
    private var hostEndpoint: String? = null
    private var status: String? = null
    private var inputSeq = 0
    private var sessionGeneration = 0
    private val effectQueue = java.util.concurrent.ConcurrentLinkedQueue<EffectEvent>()

    // ------------------------------------------------------------- public API

    fun updateNickname(name: String) {
        nickname = name.trim().ifBlank { "Танк" }.take(16)
        humans[0]?.let { humans[0] = it.copy(name = nickname) }
    }

    fun attachListener(l: Listener?) {
        listener = l
    }

    fun clearListener(l: Listener) {
        if (listener === l) listener = null
    }

    fun hostRoom() {
        if (client == null) {
            reportError("Google Play Services недоступны на устройстве")
            return
        }
        resetToIdle(notify = false)
        isHost = true
        mySlot = 0
        roomState = RoomState.HOSTING
        mapId = 0
        status = null
        humans[0] = PlayerInfo(0, nickname)
        startAdvertising()
        notifyLobby()
    }

    fun startDiscovery() {
        if (client == null) {
            reportError("Google Play Services недоступны на устройстве")
            return
        }
        resetToIdle(notify = false)
        isHost = false
        roomState = RoomState.DISCOVERING
        status = null
        discovered.clear()
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        client.startDiscovery(SERVICE_ID, discoveryCallback, options)
            .addOnFailureListener { e ->
                if (roomState == RoomState.DISCOVERING) roomState = RoomState.IDLE
                reportError("Поиск не запустился: ${e.message}")
            }
        notifyLobby()
    }

    fun stopDiscovery() {
        client?.stopDiscovery()
        discovered.clear()
        if (!isHost && roomState == RoomState.DISCOVERING) {
            roomState = RoomState.IDLE
        }
        notifyLobby()
    }

    fun connectToHost(endpointId: String) {
        if (isHost || client == null) return
        hostEndpoint = endpointId
        roomState = RoomState.CONNECTING
        status = "Подключение…"
        client.stopDiscovery()
        notifyLobby()
        client.requestConnection(nickname, endpointId, connectionCallback)
            .addOnFailureListener { e -> reportError("Подключение не удалось: ${e.message}") }
    }

    fun selectMap(id: Int) {
        if (!isHost) return
        mapId = id.coerceIn(0, MapGenerator.MAP_NAMES.size - 1)
        broadcastRaw(Protocol.mapSelect(mapId))
        broadcastRoster()
    }

    fun startRound() {
        if (!isHost || (roomState != RoomState.HOSTING && roomState != RoomState.LOBBY)) return
        val players = ArrayList(humans.values.sortedBy { it.slot })
        if (players.isEmpty()) return
        if (players.size == 1) {
            for (i in 1..4) players.add(PlayerInfo(i, "Бот $i", 0, true))
        }
        val seed = System.nanoTime() xor (System.currentTimeMillis() shl 7)
        roomState = RoomState.IN_GAME
        lastWinner = null
        status = null
        client?.stopAdvertising()
        broadcastRaw(Protocol.startRound(mapId, seed, players))
        val round = HostRound(this, mapId, seed, players)
        hostRound = round
        round.start()
        listener?.onRoundStarted(mapId)
        notifyLobby()
    }

    /** Held input from the local twin-stick; called from the render thread. */
    fun sendInput(
        moveX: Float,
        moveY: Float,
        aimX: Float,
        aimY: Float,
        fire: Boolean,
        lights: Boolean,
        dropMine: Boolean,
    ) {
        if (roomState != RoomState.IN_GAME) return
        if (isHost) {
            hostRound?.world?.setInput(
                0,
                TankInput(
                    moveX = moveX,
                    moveY = moveY,
                    aimX = aimX,
                    aimY = aimY,
                    fire = fire,
                    lights = lights,
                    dropMine = dropMine,
                ),
            )
        } else {
            val host = hostEndpoint ?: return
            inputSeq++
            sendRaw(
                host,
                Protocol.input(inputSeq, moveX, moveY, aimX, aimY, fire, lights, dropMine),
            )
        }
    }

    /** Effects produced on the server/network since the last call, for the local renderer. */
    fun pollEffects(): List<EffectEvent> {
        if (effectQueue.isEmpty()) return emptyList()
        val out = ArrayList<EffectEvent>(8)
        while (out.size < 64) {
            val event = effectQueue.poll() ?: break
            out.add(event)
        }
        return out
    }

    internal fun pushEffects(events: List<EffectEvent>) {
        for (event in events) effectQueue.add(event)
    }

    fun currentFrame(): GameFrame? {
        if (isHost) return hostRound?.frame
        val round = clientRound ?: return null
        return round.frameAt(System.nanoTime() / 1_000_000L)
    }

    fun leaveGame() {
        leaveRoom()
    }

    fun leaveRoom() {
        if (isHost) {
            broadcastRaw(Protocol.roomClosed("Хост закрыл комнату"))
        }
        hostRound?.stop()
        hostRound = null
        clientRound = null
        roomState = RoomState.IDLE
        status = null
        notifyLobby()
        val generation = ++sessionGeneration
        mainHandler.postDelayed({
            if (generation == sessionGeneration) resetToIdle(notify = true)
        }, 250L)
    }

    fun reportError(message: String) {
        status = message
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listener?.onNetworkError(message)
            notifyLobby()
        } else {
            mainHandler.post {
                listener?.onNetworkError(message)
                notifyLobby()
            }
        }
    }

    /** Called by [HostRound] on the simulation thread when the round is finished. */
    internal fun onHostRoundFinished(winnerSlot: Int, winnerName: String?) {
        mainHandler.post {
            if (!isHost) return@post
            hostRound = null
            client?.stopAdvertising()
            if (winnerSlot >= 0) {
                humans[winnerSlot]?.let { humans[winnerSlot] = it.copy(wins = it.wins + 1) }
            }
            lastWinner = winnerName
            roomState = RoomState.LOBBY
            broadcastRoster()
            startAdvertising()
            listener?.onRoundEnded(winnerName)
            notifyLobby()
        }
    }

    internal fun broadcastRaw(bytes: ByteArray) {
        for (endpointId in ArrayList(slotByEndpoint.keys)) {
            sendRaw(endpointId, bytes)
        }
    }

    // --------------------------------------------------------------- internals

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        client?.startAdvertising(SERVICE_ID, nickname, connectionCallback, options)
            ?.addOnFailureListener { e ->
                if (roomState == RoomState.HOSTING) {
                    roomState = RoomState.IDLE
                    humans.clear()
                }
                reportError("Не удалось создать комнату: ${e.message}")
            }
    }

    private fun sendRaw(endpointId: String, bytes: ByteArray) {
        client?.sendPayload(endpointId, Payload.fromBytes(bytes))
    }

    private fun freeSlot(): Int? {
        for (slot in 1 until GameConfig.MAX_PLAYERS) {
            if (!humans.containsKey(slot)) return slot
        }
        return null
    }

    private fun resetToIdle(notify: Boolean = true) {
        sessionGeneration++
        isHost = false
        hostEndpoint = null
        hostRound?.stop()
        hostRound = null
        clientRound = null
        client?.stopAdvertising()
        client?.stopDiscovery()
        client?.stopAllEndpoints()
        humans.clear()
        endpointBySlot.clear()
        slotByEndpoint.clear()
        pendingNames.clear()
        discovered.clear()
        roomState = RoomState.IDLE
        mySlot = 0
        lastWinner = null
        status = null
        effectQueue.clear()
        if (notify) notifyLobby()
    }

    private fun notifyLobby() {
        val state = lobbySnapshot()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listener?.onLobbyChanged(state)
        } else {
            mainHandler.post { listener?.onLobbyChanged(state) }
        }
    }

    fun lobbySnapshot(): LobbyUiState = LobbyUiState(
        roomState = roomState,
        isHost = isHost,
        mySlot = mySlot,
        players = humans.values.sortedBy { it.slot },
        mapId = mapId,
        hosts = discovered.entries
            .filter { it.key != hostEndpoint }
            .map { HostInfo(it.key, it.value) },
        status = status,
    )

    private fun broadcastRoster() {
        val state = if (roomState == RoomState.IN_GAME) RoomState.IN_GAME else RoomState.LOBBY
        val message = Protocol.roster(state, mapId, humans.values.sortedBy { it.slot })
        broadcastRaw(message)
        notifyLobby()
    }

    // ----------------------------------------------------------- Nearby events

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            handleMessage(endpointId, bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            pendingNames[endpointId] = info.endpointName.trim().ifBlank { "Игрок" }.take(16)
            val c = client ?: return
            if (isHost && humans.size >= GameConfig.MAX_PLAYERS) {
                c.rejectConnection(endpointId)
                return
            }
            if (isHost && roomState == RoomState.IN_GAME) {
                c.rejectConnection(endpointId)
                return
            }
            c.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val c = client ?: return
            if (!result.status.isSuccess) {
                if (!isHost) {
                    resetToIdle(notify = false)
                    reportError("Не удалось подключиться: ${result.status.statusMessage ?: "ошибка"}")
                } else {
                    reportError("Не удалось подключить игрока: ${result.status.statusMessage ?: "ошибка"}")
                }
                return
            }
            if (isHost) {
                val slot = freeSlot()
                if (slot == null) {
                    c.disconnectFromEndpoint(endpointId)
                    return
                }
                slotByEndpoint[endpointId] = slot
                endpointBySlot[slot] = endpointId
                humans[slot] = PlayerInfo(slot, pendingNames[endpointId] ?: "Игрок")
                sendRaw(endpointId, Protocol.welcome(slot))
                broadcastRoster()
            } else {
                if (endpointId != hostEndpoint) return
                roomState = RoomState.LOBBY
                status = null
                sendRaw(endpointId, Protocol.hello(nickname))
                notifyLobby()
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (isHost) {
                val slot = slotByEndpoint.remove(endpointId)
                pendingNames.remove(endpointId)
                if (slot != null) {
                    endpointBySlot.remove(slot)
                    humans.remove(slot)
                    hostRound?.world?.requestRemove(slot)
                    broadcastRoster()
                }
            } else {
                if (endpointId != hostEndpoint) return
                resetToIdle(notify = false)
                reportError("Соединение с хостом потеряно")
            }
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            discovered[endpointId] = info.endpointName.trim().ifBlank { "Комната" }
            notifyLobby()
        }

        override fun onEndpointLost(endpointId: String) {
            discovered.remove(endpointId)
            notifyLobby()
        }
    }

    private fun handleMessage(endpointId: String, bytes: ByteArray) {
        val message = Protocol.parse(bytes) ?: return
        if (isHost) {
            when (message) {
                is NetMessage.Hello -> {
                    val slot = slotByEndpoint[endpointId] ?: return
                    humans[slot] = humans[slot]?.copy(name = message.name) ?: PlayerInfo(slot, message.name)
                    broadcastRoster()
                }
                is NetMessage.InputMsg -> {
                    val slot = slotByEndpoint[endpointId] ?: return
                    if (roomState == RoomState.IN_GAME) {
                        hostRound?.world?.setInput(
                            slot,
                            TankInput(
                                moveX = message.moveX,
                                moveY = message.moveY,
                                aimX = message.aimX,
                                aimY = message.aimY,
                                fire = message.fire,
                                lights = message.lights,
                                dropMine = message.dropMine,
                            ),
                        )
                    }
                }
                else -> Unit
            }
        } else {
            when (message) {
                is NetMessage.Welcome -> {
                    mySlot = message.slot
                    notifyLobby()
                }
                is NetMessage.Roster -> {
                    mapId = message.mapId
                    humans.clear()
                    message.players.forEach { humans[it.slot] = it }
                    when {
                        message.roomState == RoomState.IN_GAME -> roomState = RoomState.IN_GAME
                        roomState == RoomState.IN_GAME -> {
                            roomState = RoomState.LOBBY
                            clientRound = null
                            listener?.onRoundEnded(lastWinner)
                        }
                        roomState != RoomState.IDLE -> roomState = RoomState.LOBBY
                    }
                    notifyLobby()
                }
                is NetMessage.StartRound -> startClientRound(message)
                is NetMessage.SnapshotMsg -> {
                    if (message.snapshot.events.isNotEmpty()) pushEffects(message.snapshot.events)
                    clientRound?.onSnapshot(message.snapshot)
                }
                is NetMessage.RoundOver -> {
                    lastWinner = message.winnerName.ifBlank { null }
                    clientRound?.setRoundOver(lastWinner)
                    listener?.onRoundEnded(lastWinner)
                }
                is NetMessage.MapSelect -> {
                    mapId = message.mapId
                    notifyLobby()
                }
                is NetMessage.RoomClosed -> {
                    val reason = message.reason
                    resetToIdle(notify = false)
                    reportError(reason)
                }
                else -> Unit
            }
        }
    }

    private fun startClientRound(message: NetMessage.StartRound) {
        val map = MapGenerator.generate(message.mapId, message.seed)
        mapId = message.mapId
        clientRound = ClientRound(map, message.mapId, mySlot, message.players)
        message.players.forEach { humans[it.slot] = it }
        roomState = RoomState.IN_GAME
        lastWinner = null
        status = null
        client?.stopDiscovery()
        listener?.onRoundStarted(message.mapId)
        notifyLobby()
    }
}
