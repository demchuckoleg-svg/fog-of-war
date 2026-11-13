package com.fogofwar.game

import android.bluetooth.BluetoothManager
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.fogofwar.game.core.GameConfig
import com.fogofwar.game.core.MapGenerator
import com.fogofwar.game.databinding.ActivityRoomsBinding
import com.fogofwar.game.databinding.ItemRoomBinding
import com.fogofwar.game.net.HostInfo
import com.fogofwar.game.net.LobbyUiState
import com.fogofwar.game.net.NearbyNet
import com.fogofwar.game.net.Protocol
import com.fogofwar.game.net.RoomState

/**
 * Full-screen list of rooms discovered on the local network. Discovery lives only while
 * this screen is visible; the persistent lobby itself stays in [NearbyNet].
 */
class RoomsActivity : AppCompatActivity(), NearbyNet.Listener {

    private lateinit var binding: ActivityRoomsBinding
    private val net: NearbyNet get() = (application as App).net
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = RoomAdapter { connect(it) }
    private val searchHint = Runnable {
        searchedLong = true
        updateStatus(net.lobbySnapshot())
    }

    private var screenActive = false
    private var closing = false
    private var searchedLong = false
    private var connectingTo: String? = null
    private var lastRoomState = RoomState.IDLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoomsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.roomsList.layoutManager = LinearLayoutManager(this)
        binding.roomsList.adapter = adapter
        (binding.roomsList.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        binding.backButton.setOnClickListener { closeScreen() }
        binding.refreshButton.setOnClickListener { refresh() }
        onBackPressedDispatcher.addCallback(this) { closeScreen() }
    }

    override fun onResume() {
        super.onResume()
        screenActive = true
        closing = false
        searchedLong = false
        lastRoomState = net.roomState
        net.attachListener(this)

        // Joining succeeded while the screen was in the background: go straight to the lobby.
        if (net.roomState == RoomState.LOBBY ||
            net.roomState == RoomState.HOSTING ||
            net.roomState == RoomState.IN_GAME
        ) {
            finish()
            return
        }
        if (net.roomState == RoomState.IDLE) {
            net.startDiscovery()
        } else if (net.roomState == RoomState.DISCOVERING &&
            net.lobbySnapshot().hosts.isEmpty()
        ) {
            // A stale discovery session can look alive but deliver nothing; rebuild it.
            net.startDiscovery()
        }
        render(net.lobbySnapshot())
    }

    override fun onPause() {
        super.onPause()
        screenActive = false
        handler.removeCallbacks(searchHint)
        net.clearListener(this)
        if (net.roomState == RoomState.DISCOVERING) net.stopDiscovery()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    // ----------------------------------------------------------- NearbyNet.Listener

    override fun onLobbyChanged(state: LobbyUiState) {
        if (!screenActive || closing) return
        val previous = lastRoomState
        lastRoomState = state.roomState
        when (state.roomState) {
            RoomState.LOBBY -> {
                finish()
                return
            }
            RoomState.IDLE -> {
                connectingTo = null
                render(state)
                // A failed or timed-out join drops us back to idle; keep searching.
                if (previous == RoomState.CONNECTING) net.startDiscovery()
                return
            }
            else -> Unit
        }
        if (state.roomState != RoomState.CONNECTING) connectingTo = null
        render(state)
    }

    override fun onRoundStarted(mapId: Int) = Unit

    override fun onRoundEnded(winnerName: String?) = Unit

    override fun onNetworkError(message: String) {
        if (!screenActive) return
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- user actions

    private fun connect(host: HostInfo) {
        if (net.roomState != RoomState.DISCOVERING && net.roomState != RoomState.IDLE) return
        connectingTo = host.name
        net.connectToHost(host.endpointId)
        render(net.lobbySnapshot())
    }

    private fun refresh() {
        if (net.roomState == RoomState.CONNECTING) return
        connectingTo = null
        searchedLong = false
        lastRoomState = net.roomState
        net.startDiscovery()
        render(net.lobbySnapshot())
    }

    private fun closeScreen() {
        closing = true
        if (net.roomState == RoomState.CONNECTING) net.cancelConnect()
        finish()
    }

    // --------------------------------------------------------------- rendering

    private fun render(state: LobbyUiState) {
        val connecting = state.roomState == RoomState.CONNECTING
        adapter.enabled = !connecting
        adapter.submitList(state.hosts.sortedWith(ROOM_ORDER))

        binding.connectingPanel.visibility = if (connecting) View.VISIBLE else View.GONE
        binding.connectingText.text = connectingTo
            ?.let { getString(R.string.rooms_connecting, it) }
            ?: getString(R.string.status_connecting)
        binding.refreshButton.isEnabled = !connecting
        binding.emptyState.visibility =
            if (state.hosts.isEmpty() && !connecting) View.VISIBLE else View.GONE
        binding.emptyHint.text = getString(R.string.rooms_empty_hint_version, Protocol.VERSION)
        binding.emptyTitle.setText(
            if (state.roomState == RoomState.DISCOVERING) R.string.rooms_empty_searching
            else R.string.rooms_empty_idle
        )

        if (!state.hosts.isEmpty()) searchedLong = false
        updateStatus(state)

        handler.removeCallbacks(searchHint)
        if (state.roomState == RoomState.DISCOVERING && state.hosts.isEmpty() && !searchedLong) {
            handler.postDelayed(searchHint, SEARCH_HINT_DELAY_MS)
        }
    }

    private fun updateStatus(state: LobbyUiState) {
        val status = state.status
        val radioIssue = if (state.roomState == RoomState.DISCOVERING && state.hosts.isEmpty()) {
            radioIssue()
        } else {
            null
        }
        binding.statusText.text = when {
            state.roomState == RoomState.CONNECTING -> connectingTo
                ?.let { getString(R.string.rooms_connecting, it) }
                ?: getString(R.string.status_connecting)
            radioIssue != null -> radioIssue
            state.roomState == RoomState.DISCOVERING && state.hosts.isEmpty() ->
                getString(
                    if (searchedLong) R.string.rooms_status_nobody
                    else R.string.rooms_status_searching
                )
            state.roomState == RoomState.DISCOVERING ->
                getString(R.string.rooms_status_found, state.hosts.size)
            !status.isNullOrBlank() -> status
            else -> getString(R.string.rooms_status_idle)
        }
    }

    /** The most common reason Nearby finds nothing: a radio or location service is off. */
    private fun radioIssue(): String? {
        try {
            val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            if (manager?.adapter?.isEnabled == false) {
                return getString(R.string.rooms_need_bluetooth)
            }
        } catch (_: SecurityException) {
            return getString(R.string.status_bad_permissions)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val location = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val enabled = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    location?.isLocationEnabled == true
                } else {
                    location?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
                }
            } catch (_: Throwable) {
                true
            }
            if (!enabled) return getString(R.string.rooms_need_location)
        }
        return null
    }

    private class RoomAdapter(
        private val onClick: (HostInfo) -> Unit,
    ) : ListAdapter<HostInfo, RoomAdapter.RoomViewHolder>(DIFF) {

        var enabled: Boolean = true
            set(value) {
                if (field == value) return
                field = value
                notifyItemRangeChanged(0, itemCount)
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoomViewHolder =
            RoomViewHolder(
                ItemRoomBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )

        override fun onBindViewHolder(holder: RoomViewHolder, position: Int) {
            holder.bind(getItem(position), enabled, onClick)
        }

        class RoomViewHolder(
            private val binding: ItemRoomBinding,
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(host: HostInfo, enabled: Boolean, onClick: (HostInfo) -> Unit) {
                binding.roomName.text = host.name
                binding.roomCode.text = host.code
                binding.roomCode.visibility = if (host.code.isBlank()) View.GONE else View.VISIBLE
                binding.roomDetails.text = describe(host)
                binding.root.isEnabled = enabled
                binding.root.alpha = if (enabled) 1f else 0.55f
                binding.root.setOnClickListener { if (enabled) onClick(host) }
            }

            private fun describe(host: HostInfo): String {
                val context = binding.root.context
                val parts = ArrayList<String>(3)
                val mapName = host.mapId?.let { MapGenerator.MAP_NAMES.getOrNull(it) }
                if (mapName != null) parts.add(context.getString(R.string.rooms_map, mapName))
                if (host.players > 0) {
                    parts.add(
                        context.getString(
                            R.string.rooms_players,
                            host.players,
                            GameConfig.MAX_PLAYERS,
                        )
                    )
                }
                parts.add(
                    context.getString(
                        if (host.inGame) R.string.rooms_state_game else R.string.rooms_state_lobby
                    )
                )
                return parts.joinToString(" · ")
            }
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<HostInfo>() {
                override fun areItemsTheSame(oldItem: HostInfo, newItem: HostInfo): Boolean =
                    oldItem.endpointId == newItem.endpointId

                override fun areContentsTheSame(oldItem: HostInfo, newItem: HostInfo): Boolean =
                    oldItem == newItem
            }
        }
    }

    companion object {
        private const val SEARCH_HINT_DELAY_MS = 8_000L

        /** Lobbies first, then rooms with more free seats, then by name. */
        private val ROOM_ORDER = compareBy<HostInfo> { it.inGame }
            .thenBy { it.players }
            .thenBy { it.name.lowercase() }
            .thenBy { it.code }
    }
}
