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
        const val SERVICE_ID = "com.fogofwar.game.nearby.v3"

        /**
         * Advertising restarts are the only way to update the room description, so batch
         * roster/map changes instead of flapping the advertisement on every event.
         */
        private const val ADVERTISING_DEBOUNCE_MS = 1_500L

        /** A short advertising restart looks like a lost endpoint; keep the row meanwhile. */
        private const val ENDPOINT_LOST_GRACE_MS = 3_000L
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
    var roomCode: String = ""
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
    private val endpointBySlot = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val slotByEndpoint = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val pendingNames = HashMap<String, String>()
    private val discovered = LinkedHashMap<String, HostInfo>()
    private val lostRemovals = HashMap<String, Runnable>()
    @Volatile
    private var hostEndpoint: String? = null
    private var status: String? = null
    private var inputSeq = 0
    private var sessionGeneration = 0
    private var lastRoundMapId = -1
    private var connectToken = 0
    private var discoveryRetries = 0
    private var discoveryRetry: Runnable? = null
    private var advertisingUpdate: Runnable? = null
    private var advertisedName: String? = null
    private val effectQueue = java.util.concurrent.ConcurrentLinkedQueue<EffectEvent>()

    // ------------------------------------------------------------- public API

    fun updateNickname(name: String) {
        nickname = name.trim().ifBlank { "Танк" }.take(16)
        humans[0]?.let { humans[0] = it.copy(name = nickname) }
        when {
            isHost && roomState != RoomState.IDLE -> {
                broadcastRoster()
                updateAdvertising()
            }
            !isHost && roomState == RoomState.LOBBY -> hostEndpoint?.let {
                sendRaw(it, Protocol.hello(nickname))
            }
        }
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
        lastRoundMapId = -1
        roomState = RoomState.HOSTING
        roomCode = RoomDescriptor.newCode()
        mapId = 0
        status = null
        humans[0] = PlayerInfo(0, nickname)
        updateAdvertising(immediate = true)
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
        clearDiscovered()
        discoveryRetries = 0
        launchDiscovery()
        scheduleDiscoveryRetry()
        notifyLobby()
    }

    private fun launchDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        client?.startDiscovery(SERVICE_ID, discoveryCallback, options)
            ?.addOnFailureListener { e ->
                if (roomState == RoomState.DISCOVERING) {
                    roomState = RoomState.IDLE
                    cancelDiscoveryRetry()
                }
                reportError("Поиск не запустился: ${e.message}")
            }
    }

    /** Nearby discovery sometimes goes quiet; retry a few times while nothing is found. */
    private fun scheduleDiscoveryRetry() {
        cancelDiscoveryRetry()
        if (discoveryRetries >= 3) return
        val runnable = Runnable {
            if (isHost || roomState != RoomState.DISCOVERING || discovered.isNotEmpty()) return@Runnable
            discoveryRetries++
            client?.stopDiscovery()
            launchDiscovery()
            scheduleDiscoveryRetry()
        }
        discoveryRetry = runnable
        mainHandler.postDelayed(runnable, 9_000L)
    }

    private fun cancelDiscoveryRetry() {
        discoveryRetry?.let { mainHandler.removeCallbacks(it) }
        discoveryRetry = null
    }

    fun stopDiscovery() {
        cancelDiscoveryRetry()
        client?.stopDiscovery()
        clearDiscovered()
        if (!isHost && roomState == RoomState.DISCOVERING) {
            roomState = RoomState.IDLE
        }
        notifyLobby()
    }

    fun connectToHost(endpointId: String) {
        if (isHost || client == null) return
        hostEndpoint = endpointId
        roomCode = discovered[endpointId]?.code.orEmpty()
        roomState = RoomState.CONNECTING
        status = "Подключение…"
        cancelDiscoveryRetry()
        client.stopDiscovery()
        notifyLobby()
        val token = ++connectToken
        mainHandler.postDelayed({
            if (token == connectToken && roomState == RoomState.CONNECTING) {
                resetToIdle(notify = false)
                reportError("Хост не отвечает. Попробуйте найти игру ещё раз.")
            }
        }, 15_000L)
        client.requestConnection(nickname, endpointId, connectionCallback)
            .addOnFailureListener { e ->
                if (token == connectToken) {
                    resetToIdle(notify = false)
                    reportError("Подключение не удалось: ${e.message}")
                }
            }
    }

    /** Abort a pending join when the player backs out of the rooms screen. */
    fun cancelConnect() {
        if (roomState == RoomState.CONNECTING) resetToIdle()
    }

    fun selectMap(id: Int) {
        if (!isHost) return
        mapId = id.coerceIn(0, MapGenerator.MAP_NAMES.size - 1)
        broadcastRaw(Protocol.mapSelect(mapId))
        broadcastRoster()
        updateAdvertising()
    }

    fun startRound() {
        if (!isHost || (roomState != RoomState.HOSTING && roomState != RoomState.LOBBY)) return
        val players = ArrayList(humans.values.sortedBy { it.slot })
        if (players.isEmpty()) return
        if (players.size == 1) {
            for (i in 1..4) players.add(PlayerInfo(i, "Бот $i", 0, true))
        }
        val roundMapId = nextRoundMapId()
        lastRoundMapId = roundMapId
        mapId = roundMapId
        val seed = GameConfig.MAP_SEED
        roomState = RoomState.IN_GAME
        lastWinner = null
        status = null
        cancelAdvertisingUpdate()
        client?.stopAdvertising()
        advertisedName = null
        broadcastRaw(Protocol.startRound(roundMapId, seed, players))
        val round = HostRound(this, roundMapId, seed, players)
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
        while (effectQueue.size > 160) effectQueue.poll()
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
            advertisedName = null
            if (winnerSlot >= 0) {
                humans[winnerSlot]?.let { humans[winnerSlot] = it.copy(wins = it.wins + 1) }
            }
            lastWinner = winnerName
            roomState = RoomState.LOBBY
            // Pre-pick the next map so the lobby preview shows exactly what will be played.
            mapId = nextRoundMapId()
            broadcastRoster()
            updateAdvertising(immediate = true)
            listener?.onRoundEnded(winnerName)
            notifyLobby()
        }
    }

    /** Round 1 uses the host's lobby choice; every next round is guaranteed to be a different map. */
    private fun nextRoundMapId(): Int {
        val count = MapGenerator.MAP_NAMES.size
        if (lastRoundMapId < 0) return mapId
        if (mapId != lastRoundMapId) return mapId
        val candidates = (0 until count).filter { it != lastRoundMapId }
        return candidates[kotlin.random.Random.nextInt(candidates.size)]
    }

    internal fun broadcastRaw(bytes: ByteArray) {
        for (endpointId in slotByEndpoint.keys.toList()) {
            sendRaw(endpointId, bytes)
        }
    }

    // --------------------------------------------------------------- internals

    /**
     * What other phones will see during discovery, or `null` when this room should not be
     * advertised (not hosting, round in progress, or the lobby is full).
     */
    private fun desiredAdvertisingName(): String? {
        if (!isHost) return null
        if (roomState != RoomState.HOSTING && roomState != RoomState.LOBBY) return null
        if (humans.size >= GameConfig.MAX_PLAYERS) return null
        return RoomDescriptor(
            name = nickname,
            mapId = mapId,
            players = humans.size.coerceAtLeast(1),
            inGame = false,
            code = roomCode,
        ).encode()
    }

    private fun updateAdvertising(immediate: Boolean = false) {
        cancelAdvertisingUpdate()
        if (immediate) {
            applyAdvertising()
            return
        }
        val runnable = Runnable {
            advertisingUpdate = null
            applyAdvertising()
        }
        advertisingUpdate = runnable
        mainHandler.postDelayed(runnable, ADVERTISING_DEBOUNCE_MS)
    }

    private fun cancelAdvertisingUpdate() {
        advertisingUpdate?.let(mainHandler::removeCallbacks)
        advertisingUpdate = null
    }

    private fun applyAdvertising() {
        val name = desiredAdvertisingName()
        if (name == advertisedName) return
        client?.stopAdvertising()
        advertisedName = null
        if (name != null) startAdvertising(name)
    }

    private fun startAdvertising(name: String, attempt: Int = 0) {
        if (desiredAdvertisingName() != name) return
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        advertisedName = name
        client?.startAdvertising(SERVICE_ID, name, connectionCallback, options)
            ?.addOnFailureListener { e ->
                advertisedName = null
                if (roomState == RoomState.IN_GAME || roomState == RoomState.IDLE) return@addOnFailureListener
                if (attempt < 2) {
                    client?.stopAdvertising()
                    mainHandler.postDelayed({
                        if (desiredAdvertisingName() != name) {
                            applyAdvertising()
                        } else {
                            startAdvertising(name, attempt + 1)
                        }
                    }, 1_200L)
                } else {
                    if (roomState == RoomState.HOSTING) {
                        roomState = RoomState.IDLE
                        humans.clear()
                    }
                    reportError("Не удалось создать комнату: ${e.message}")
                }
            }
    }

    private fun clearDiscovered() {
        for (removal in lostRemovals.values) mainHandler.removeCallbacks(removal)
        lostRemovals.clear()
        discovered.clear()
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
        connectToken++
        cancelDiscoveryRetry()
        cancelAdvertisingUpdate()
        isHost = false
        hostEndpoint = null
        hostRound?.stop()
        hostRound = null
        clientRound = null
        client?.stopAdvertising()
        advertisedName = null
        client?.stopDiscovery()
        client?.stopAllEndpoints()
        humans.clear()
        endpointBySlot.clear()
        slotByEndpoint.clear()
        pendingNames.clear()
        clearDiscovered()
        roomState = RoomState.IDLE
        mySlot = 0
        lastRoundMapId = -1
        lastWinner = null
        roomCode = ""
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
        hosts = discovered.values.filter { it.endpointId != hostEndpoint },
        roomCode = roomCode,
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
            connectToken++
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
                updateAdvertising()
            } else {
                // A join cancelled from the rooms screen can still complete later.
                if (endpointId != hostEndpoint) {
                    c.disconnectFromEndpoint(endpointId)
                    return
                }
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
                    updateAdvertising()
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
            lostRemovals.remove(endpointId)?.let(mainHandler::removeCallbacks)
            val host = HostInfo.fromEndpoint(endpointId, info.endpointName)
            if (discovered[endpointId] == host) return
            discovered[endpointId] = host
            cancelDiscoveryRetry()
            notifyLobby()
        }

        override fun onEndpointLost(endpointId: String) {
            if (!discovered.containsKey(endpointId)) return
            // Updating the advertised descriptor restarts advertising, which can look like
            // a lost endpoint for a moment. Keep the row for a grace period.
            lostRemovals.remove(endpointId)?.let(mainHandler::removeCallbacks)
            val removal = Runnable {
                lostRemovals.remove(endpointId)
                if (discovered.remove(endpointId) != null) notifyLobby()
            }
            lostRemovals[endpointId] = removal
            mainHandler.postDelayed(removal, ENDPOINT_LOST_GRACE_MS)
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
                    updateAdvertising()
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
