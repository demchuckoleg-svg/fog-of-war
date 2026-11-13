package com.fogofwar.game.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fogofwar.game.core.EffectEvent
import com.fogofwar.game.core.GameConfig
import com.fogofwar.game.core.KillEvent
import com.fogofwar.game.core.MapGenerator
import com.fogofwar.game.core.RoundRules
import com.fogofwar.game.core.TankInput
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.util.concurrent.atomic.AtomicLong

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
        const val SERVICE_ID = "com.fogofwar.game.nearby.v4"
        const val VERSION_MISMATCH_MESSAGE =
            "Версия приложения не совпадает. Обновите APK у всех игроков."

        /**
         * Advertising restarts are the only way to update the room description, so batch
         * roster/map changes instead of flapping the advertisement on every event.
         */
        private const val ADVERTISING_DEBOUNCE_MS = 1_500L

        /** A short advertising restart looks like a lost endpoint; keep the row meanwhile. */
        private const val ENDPOINT_LOST_GRACE_MS = 3_000L

        /** Client gives up when the host stops sending snapshots (frozen app, dead link). */
        private const val SNAPSHOT_TIMEOUT_MS = 5_000L
    }

    private val client: ConnectionsClient? = try {
        Nearby.getConnectionsClient(context.applicationContext)
    } catch (t: Throwable) {
        Log.e(TAG, "Play Services unavailable", t)
        null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.applicationContext.getSharedPreferences("fogofwar", Context.MODE_PRIVATE)

    /** Lifetime local profile; each device records its own rounds. */
    val lifetimeGames: Int get() = prefs.getInt("stats_games", 0)
    val lifetimeWins: Int get() = prefs.getInt("stats_wins", 0)

    private fun recordRoundStarted() {
        prefs.edit().putInt("stats_games", lifetimeGames + 1).apply()
    }

    private fun recordRoundWon() {
        prefs.edit().putInt("stats_wins", lifetimeWins + 1).apply()
    }

    var nickname: String = "Танк"
    @Volatile
    var isHost: Boolean = false
        private set

    /** Diagnostics for the perf overlay; bytes counted per send/receive call. */
    val bytesSent = AtomicLong()
    val bytesReceived = AtomicLong()

    @Volatile
    var rules: RoundRules = RoundRules.CLASSIC
        private set

    @Volatile
    var myReady: Boolean = false
        private set

    private val pendingKills = java.util.concurrent.ConcurrentLinkedQueue<KillSnap>()
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
    private val lastInputSeq = HashMap<Int, Int>()
    private val pendingNames = HashMap<String, String>()
    private val rejectedEndpoints = HashSet<String>()
    private val discovered = LinkedHashMap<String, HostInfo>()
    private val lostRemovals = HashMap<String, Runnable>()
    private val lastFoundAt = HashMap<String, Long>()
    @Volatile
    private var hostEndpoint: String? = null
    @Volatile
    private var status: String? = null
    private var inputSeq = 0
    private var sessionGeneration = 0
    private var lastRoundMapId = -1
    private var connectToken = 0
    private var discoveryRetries = 0
    private var discoveryRetry: Runnable? = null
    private var advertisingUpdate: Runnable? = null
    private var advertisingRetry: Runnable? = null
    private var snapshotWatchdog: Runnable? = null
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
        humans[0] = PlayerInfo(0, nickname, ready = true)
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

    /** Host changes teams/lives/time/pickups; the roster carries them to every client. */
    fun updateRules(newRules: RoundRules) {
        if (!isHost) return
        rules = newRules.sanitized()
        for ((slot, player) in humans) {
            humans[slot] = player.copy(team = if (rules.teams) slot % 2 else 0)
        }
        broadcastRoster()
    }

    /** Client readiness; the host can still start at any moment. */
    fun setReady(ready: Boolean) {
        myReady = ready
        if (isHost) {
            humans[0]?.let { humans[0] = it.copy(ready = ready) }
            notifyLobby()
        } else {
            hostEndpoint?.let { sendRaw(it, Protocol.setReady(ready)) }
            notifyLobby()
        }
    }

    /** Host-only: removes a player from the lobby and closes their connection. */
    fun kick(slot: Int) {
        if (!isHost || slot == 0) return
        val endpoint = endpointBySlot[slot] ?: return
        sendRaw(endpoint, Protocol.kick(slot, "Хост исключил вас"))
        humans.remove(slot)
        slotByEndpoint.remove(endpoint)
        endpointBySlot.remove(slot)
        lastInputSeq.remove(slot)
        hostRound?.world?.requestRemove(slot)
        broadcastRoster()
        updateAdvertising()
        // Give the kick payload a moment to flush before closing the endpoint.
        mainHandler.postDelayed({
            client?.disconnectFromEndpoint(endpoint)
        }, 300L)
    }

    internal fun pushKills(kills: List<KillEvent>) {
        for (kill in kills) pendingKills.add(KillSnap(kill.killer, kill.victim))
    }

    /** Deaths accumulated since the last render frame, for the kill feed. */
    fun pollKills(): List<KillSnap> {
        if (pendingKills.isEmpty()) return emptyList()
        val out = ArrayList<KillSnap>()
        while (true) {
            val kill = pendingKills.poll() ?: break
            out.add(kill)
        }
        return out
    }

    fun startRound() {
        if (!isHost || (roomState != RoomState.HOSTING && roomState != RoomState.LOBBY)) return
        val players = ArrayList(humans.values.sortedBy { it.slot })
        if (players.isEmpty()) return
        for (i in players.indices) {
            val team = if (rules.teams) players[i].slot % 2 else 0
            players[i] = players[i].copy(team = team)
            humans[players[i].slot] = players[i]
        }
        if (players.size == 1) {
            for (i in 1..4) {
                val team = if (rules.teams) i % 2 else 0
                players.add(PlayerInfo(i, "Бот $i", 0, true, team = team))
            }
        }
        val roundMapId = nextRoundMapId()
        lastRoundMapId = roundMapId
        mapId = roundMapId
        val seed = GameConfig.MAP_SEED
        roomState = RoomState.IN_GAME
        lastWinner = null
        status = null
        pendingKills.clear()
        recordRoundStarted()
        cancelAdvertisingUpdate()
        client?.stopAdvertising()
        advertisedName = null
        broadcastRaw(Protocol.startRound(roundMapId, seed, players, rules))
        val round = HostRound(this, roundMapId, seed, players, rules)
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
        throwGrenade: Boolean,
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
                    throwGrenade = throwGrenade,
                ),
            )
        } else {
            val host = hostEndpoint ?: return
            inputSeq++
            sendRaw(
                host,
                Protocol.input(inputSeq, moveX, moveY, aimX, aimY, fire, lights, throwGrenade),
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
    internal fun onHostRoundFinished(round: HostRound, winnerSlot: Int, winnerName: String?) {
        mainHandler.post {
            if (!isHost || hostRound !== round) return@post
            hostRound = null
            client?.stopAdvertising()
            advertisedName = null
            val winnerTeam = round.world.winnerTeam
            if (winnerTeam >= 0) {
                for ((slot, player) in humans.entries.toList()) {
                    if (player.team == winnerTeam) humans[slot] = player.copy(wins = player.wins + 1)
                }
            } else if (winnerSlot >= 0) {
                humans[winnerSlot]?.let { humans[winnerSlot] = it.copy(wins = it.wins + 1) }
            }
            val hostWon = if (winnerTeam >= 0) {
                humans[0]?.team == winnerTeam
            } else {
                winnerSlot == 0
            }
            if (hostWon) recordRoundWon()
            for ((slot, player) in humans.entries.toList()) {
                humans[slot] = player.copy(ready = slot == 0)
            }
            myReady = false
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
        advertisingRetry?.let(mainHandler::removeCallbacks)
        advertisingRetry = null
    }

    private fun applyAdvertising() {
        val name = desiredAdvertisingName()
        if (name == advertisedName) return
        advertisedName = null
        val connection = client ?: return
        // stopAdvertising is asynchronous and returns void; give it a moment before restarting.
        connection.stopAdvertising()
        if (name == null) return
        val restart = Runnable {
            advertisingUpdate = null
            if (desiredAdvertisingName() == name) startAdvertising(name)
        }
        advertisingUpdate = restart
        mainHandler.postDelayed(restart, 150L)
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
                    val retry = Runnable {
                        advertisingRetry = null
                        if (desiredAdvertisingName() != name) {
                            applyAdvertising()
                        } else {
                            startAdvertising(name, attempt + 1)
                        }
                    }
                    advertisingRetry = retry
                    mainHandler.postDelayed(retry, 1_200L)
                } else {
                    // Keep the room (and already connected players) alive; just report it.
                    reportError("Не удалось создать комнату: ${e.message}")
                }
            }
    }

    private fun clearDiscovered() {
        for (removal in lostRemovals.values) mainHandler.removeCallbacks(removal)
        lostRemovals.clear()
        lastFoundAt.clear()
        discovered.clear()
    }

    /**
     * A frozen host keeps the connection alive but stops sending snapshots. Without this the
     * client would render one stale frame forever with no way to understand what happened.
     */
    private fun startSnapshotWatchdog() {
        cancelSnapshotWatchdog()
        val runnable = object : Runnable {
            override fun run() {
                if (isHost || roomState != RoomState.IN_GAME) return
                val round = clientRound ?: return
                if (System.nanoTime() / 1_000_000L - round.lastSnapshotAt > SNAPSHOT_TIMEOUT_MS) {
                    resetToIdle(notify = false)
                    reportError("Связь с хостом потеряна")
                    return
                }
                mainHandler.postDelayed(this, 1_000L)
            }
        }
        snapshotWatchdog = runnable
        mainHandler.postDelayed(runnable, 1_000L)
    }

    private fun cancelSnapshotWatchdog() {
        snapshotWatchdog?.let(mainHandler::removeCallbacks)
        snapshotWatchdog = null
    }

    private fun sendRaw(endpointId: String, bytes: ByteArray) {
        bytesSent.addAndGet(bytes.size.toLong())
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
        cancelSnapshotWatchdog()
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
        rejectedEndpoints.clear()
        lastInputSeq.clear()
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
        rules = rules,
    )

    private fun broadcastRoster() {
        val state = if (roomState == RoomState.IN_GAME) RoomState.IN_GAME else RoomState.LOBBY
        val message = Protocol.roster(state, mapId, humans.values.sortedBy { it.slot }, rules)
        broadcastRaw(message)
        notifyLobby()
    }

    // ----------------------------------------------------------- Nearby events

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            bytesReceived.addAndGet(bytes.size.toLong())
            handleMessage(endpointId, bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            pendingNames[endpointId] = info.endpointName.trim().ifBlank { "Игрок" }.take(16)
            val c = client ?: return
            if (isHost && humans.size >= GameConfig.MAX_PLAYERS) {
                pendingNames.remove(endpointId)
                c.rejectConnection(endpointId)
                return
            }
            if (isHost && roomState == RoomState.IN_GAME) {
                pendingNames.remove(endpointId)
                c.rejectConnection(endpointId)
                return
            }
            c.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val c = client ?: return
            connectToken++
            if (!result.status.isSuccess) {
                rejectedEndpoints.remove(endpointId)
                if (!isHost) {
                    resetToIdle(notify = false)
                    val reason = if (result.status.statusCode == ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED) {
                        "Комната заполнена или идёт бой"
                    } else {
                        result.status.statusMessage ?: "ошибка"
                    }
                    reportError("Не удалось подключиться: $reason")
                } else {
                    reportError("Не удалось подключить игрока: ${result.status.statusMessage ?: "ошибка"}")
                }
                return
            }
            if (isHost) {
                if (rejectedEndpoints.remove(endpointId)) {
                    c.disconnectFromEndpoint(endpointId)
                    return
                }
                if (roomState == RoomState.IN_GAME) {
                    sendRaw(endpointId, Protocol.roomClosed("Идёт бой, зайдите после раунда"))
                    pendingNames.remove(endpointId)
                    c.disconnectFromEndpoint(endpointId)
                    return
                }
                val slot = freeSlot()
                if (slot == null) {
                    sendRaw(endpointId, Protocol.roomClosed("Комната заполнена"))
                    pendingNames.remove(endpointId)
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
            rejectedEndpoints.remove(endpointId)
            pendingNames.remove(endpointId)
            if (isHost) {
                val slot = slotByEndpoint.remove(endpointId)
                if (slot != null) {
                    endpointBySlot.remove(slot)
                    humans.remove(slot)
                    lastInputSeq.remove(slot)
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
            lastFoundAt[endpointId] = android.os.SystemClock.elapsedRealtime()
            val host = HostInfo.fromEndpoint(endpointId, info.endpointName)
            // Updating the descriptor restarts advertising, which may produce a new endpoint id
            // for the same room. Keep exactly one row per room code.
            if (host.code.isNotBlank()) {
                val stale = discovered.entries
                    .filter { it.key != endpointId && it.value.code == host.code }
                    .map { it.key }
                for (key in stale) {
                    discovered.remove(key)
                    lastFoundAt.remove(key)
                }
            }
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
            val lostAt = android.os.SystemClock.elapsedRealtime()
            val removal = Runnable {
                lostRemovals.remove(endpointId)
                // A newer advertisement of the same endpoint arrived while we waited.
                if ((lastFoundAt[endpointId] ?: 0L) > lostAt) return@Runnable
                lastFoundAt.remove(endpointId)
                if (discovered.remove(endpointId) != null) {
                    notifyLobby()
                    // The retry chain is cancelled on the first find; re-arm it after the last row.
                    if (discovered.isEmpty() && !isHost && roomState == RoomState.DISCOVERING) {
                        discoveryRetries = 0
                        scheduleDiscoveryRetry()
                    }
                }
            }
            lostRemovals[endpointId] = removal
            mainHandler.postDelayed(removal, ENDPOINT_LOST_GRACE_MS)
        }
    }

    private fun handleMessage(endpointId: String, bytes: ByteArray) {
        val message = Protocol.parse(bytes) ?: run {
            Log.w(TAG, "unparsable payload: ${bytes.size}B, type=${bytes.firstOrNull()}")
            return
        }
        if (isHost) {
            when (message) {
                is NetMessage.Hello -> {
                    if (message.version != Protocol.VERSION) {
                        rejectEndpoint(endpointId, VERSION_MISMATCH_MESSAGE)
                        return
                    }
                    val slot = slotByEndpoint[endpointId] ?: return
                    val existing = humans[slot]
                    humans[slot] = existing?.copy(name = message.name) ?: PlayerInfo(
                        slot = slot,
                        name = message.name,
                        team = if (rules.teams) slot % 2 else 0,
                    )
                    // A join accepted just before StartRound may not have received it.
                    if (roomState == RoomState.IN_GAME) {
                        hostRound?.let { sendRaw(endpointId, it.startMessage()) }
                    }
                    broadcastRoster()
                    updateAdvertising()
                }
                is NetMessage.InputMsg -> {
                    val slot = slotByEndpoint[endpointId] ?: return
                    val previousSeq = lastInputSeq[slot]
                    if (previousSeq != null && message.seq <= previousSeq) return
                    lastInputSeq[slot] = message.seq
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
                                throwGrenade = message.throwGrenade,
                            ),
                        )
                    }
                }
                is NetMessage.SetReady -> {
                    val slot = slotByEndpoint[endpointId] ?: return
                    humans[slot]?.let { humans[slot] = it.copy(ready = message.ready) }
                    broadcastRoster()
                }
                is NetMessage.Kick -> Unit
                else -> Unit
            }
        } else {
            // Only the host we joined may drive this client.
            if (endpointId != hostEndpoint) return
            when (message) {
                is NetMessage.Welcome -> {
                    if (message.version != Protocol.VERSION) {
                        resetToIdle(notify = false)
                        reportError(VERSION_MISMATCH_MESSAGE)
                        return
                    }
                    mySlot = message.slot
                    notifyLobby()
                }
                is NetMessage.Roster -> {
                    mapId = message.mapId
                    rules = message.rules
                    humans.clear()
                    message.players.forEach { humans[it.slot] = it }
                    myReady = humans[mySlot]?.ready ?: false
                    when {
                        message.roomState == RoomState.IN_GAME -> roomState = RoomState.IN_GAME
                        roomState == RoomState.IN_GAME -> {
                            roomState = RoomState.LOBBY
                            clientRound = null
                            myReady = false
                            listener?.onRoundEnded(lastWinner)
                        }
                        roomState != RoomState.IDLE -> roomState = RoomState.LOBBY
                    }
                    notifyLobby()
                }
                is NetMessage.StartRound -> startClientRound(message)
                is NetMessage.SnapshotMsg -> {
                    if (message.snapshot.events.isNotEmpty()) pushEffects(message.snapshot.events)
                    if (message.snapshot.kills.isNotEmpty()) {
                        for (kill in message.snapshot.kills) pendingKills.add(kill)
                    }
                    clientRound?.onSnapshot(message.snapshot)
                }
                is NetMessage.TileSync -> clientRound?.onTileChanges(message.changes)
                is NetMessage.RoundOver -> {
                    lastWinner = message.winnerName.ifBlank { null }
                    clientRound?.setRoundOver(
                        message.winnerSlot,
                        message.winnerTeam,
                        lastWinner,
                        message.results,
                    )
                    val iWon = if (message.winnerTeam >= 0) {
                        humans[mySlot]?.team == message.winnerTeam
                    } else {
                        message.winnerSlot == mySlot
                    }
                    if (iWon) recordRoundWon()
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
                is NetMessage.Kick -> {
                    if (message.slot == mySlot) {
                        resetToIdle(notify = false)
                        reportError(message.reason.ifBlank { "Вас исключили из комнаты" })
                    }
                }
                else -> Unit
            }
        }
    }

    /** Drops an endpoint that speaks an incompatible protocol version. */
    private fun rejectEndpoint(endpointId: String, reason: String) {
        rejectedEndpoints.add(endpointId)
        sendRaw(endpointId, Protocol.roomClosed(reason))
        val slot = slotByEndpoint.remove(endpointId)
        if (slot != null) {
            endpointBySlot.remove(slot)
            humans.remove(slot)
            lastInputSeq.remove(slot)
            hostRound?.world?.requestRemove(slot)
        }
        pendingNames.remove(endpointId)
        client?.disconnectFromEndpoint(endpointId)
        broadcastRoster()
        updateAdvertising()
    }

    private fun startClientRound(message: NetMessage.StartRound) {
        if (clientRound != null && roomState == RoomState.IN_GAME && mapId == message.mapId) return
        val map = MapGenerator.generate(message.mapId, message.seed)
        mapId = message.mapId
        rules = message.rules
        pendingKills.clear()
        clientRound = ClientRound(map, message.mapId, mySlot, message.players, rules)
        message.players.forEach { humans[it.slot] = it }
        roomState = RoomState.IN_GAME
        lastWinner = null
        status = null
        recordRoundStarted()
        client?.stopDiscovery()
        startSnapshotWatchdog()
        listener?.onRoundStarted(message.mapId)
        notifyLobby()
    }
}
