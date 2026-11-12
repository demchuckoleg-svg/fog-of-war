package com.fogofwar.game

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.fogofwar.game.core.MapGenerator
import com.fogofwar.game.databinding.ActivityMainBinding
import com.fogofwar.game.net.LobbyUiState
import com.fogofwar.game.net.NearbyNet
import com.fogofwar.game.net.RoomState
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity(), NearbyNet.Listener {

    private lateinit var binding: ActivityMainBinding
    private val net: NearbyNet get() = (application as App).net
    private val prefs by lazy { getSharedPreferences("fogofwar", MODE_PRIVATE) }

    private var pendingAction: PendingAction = PendingAction.NONE
    private var suppressSpinner = false
    private var resumeDiscovery = false
    private var lastState: LobbyUiState = LobbyUiState()

    private enum class PendingAction { NONE, HOST, DISCOVER }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val action = pendingAction
        pendingAction = PendingAction.NONE
        if (result.values.all { it }) {
            when (action) {
                PendingAction.HOST -> net.hostRoom()
                PendingAction.DISCOVER -> net.startDiscovery()
                PendingAction.NONE -> Unit
            }
        } else {
            Toast.makeText(this, R.string.status_bad_permissions, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val savedName = prefs.getString("nickname", null) ?: "Игрок"
        binding.nicknameInput.setText(savedName)
        net.updateNickname(savedName)
        binding.nicknameInput.doAfterTextChanged { editable ->
            val name = editable?.toString().orEmpty()
            net.updateNickname(name)
            prefs.edit().putString("nickname", name).apply()
        }

        binding.createButton.setOnClickListener {
            ensurePermissions(PendingAction.HOST)
        }
        binding.discoverButton.setOnClickListener {
            if (net.roomState == RoomState.DISCOVERING) {
                net.stopDiscovery()
            } else {
                ensurePermissions(PendingAction.DISCOVER)
            }
        }
        binding.startButton.setOnClickListener { net.startRound() }
        binding.leaveButton.setOnClickListener { net.leaveRoom() }

        binding.mapSpinner.adapter = ArrayAdapter(
            this,
            R.layout.spinner_item,
            MapGenerator.MAP_NAMES,
        )
        binding.mapSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!suppressSpinner && net.isHost) net.selectMap(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        render(lastState)
    }

    override fun onResume() {
        super.onResume()
        net.attachListener(this)
        render(net.lobbySnapshot())
        if (net.roomState == RoomState.IN_GAME) {
            startActivity(Intent(this, GameActivity::class.java))
        } else if (resumeDiscovery) {
            resumeDiscovery = false
            if (net.roomState == RoomState.IDLE) net.startDiscovery()
        }
    }

    override fun onPause() {
        super.onPause()
        net.clearListener(this)
        if (net.roomState == RoomState.DISCOVERING) {
            resumeDiscovery = true
            net.stopDiscovery()
        }
    }

    private fun ensurePermissions(action: PendingAction) {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            when (action) {
                PendingAction.HOST -> net.hostRoom()
                PendingAction.DISCOVER -> net.startDiscovery()
                PendingAction.NONE -> Unit
            }
        } else {
            pendingAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requiredPermissions(): List<String> {
        val list = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return list
    }

    // --------------------------------------------------------------- rendering

    override fun onLobbyChanged(state: LobbyUiState) {
        lastState = state
        render(state)
    }

    override fun onRoundStarted(mapId: Int) {
        runOnUiThread {
            startActivity(Intent(this, GameActivity::class.java))
        }
    }

    override fun onRoundEnded(winnerName: String?) = Unit

    override fun onNetworkError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun render(state: LobbyUiState) {
        val inLobby = state.inLobby && state.roomState != RoomState.DISCOVERING
        binding.menuPanel.visibility = if (inLobby) View.GONE else View.VISIBLE
        binding.lobbyPanel.visibility = if (inLobby) View.VISIBLE else View.GONE

        binding.discoverButton.text = getString(
            if (state.roomState == RoomState.DISCOVERING) R.string.stop_search else R.string.join_room
        )

        binding.statusText.text = state.status ?: when (state.roomState) {
            RoomState.HOSTING -> getString(R.string.status_hosting)
            RoomState.DISCOVERING -> if (state.hosts.isEmpty()) getString(R.string.status_searching) else ""
            RoomState.CONNECTING -> getString(R.string.status_connecting)
            RoomState.IDLE -> getString(R.string.status_idle)
            else -> ""
        }

        binding.hostsContainer.removeAllViews()
        for (host in state.hosts) {
            val button = MaterialButton(this).apply {
                text = "Подключиться: ${host.name}"
                textSize = 16f
                cornerRadius = (12 * resources.displayMetrics.density).toInt()
                setOnClickListener { net.connectToHost(host.endpointId) }
            }
            val params = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (56 * resources.displayMetrics.density).toInt(),
            )
            params.topMargin = (8 * resources.displayMetrics.density).toInt()
            binding.hostsContainer.addView(button, params)
        }

        if (!inLobby) return

        binding.playersContainer.removeAllViews()
        for (player in state.players) {
            val row = TextView(this).apply {
                text = buildString {
                    append(if (player.isBot) "🤖 " else if (player.slot == 0) "👑 " else "• ")
                    append(player.name)
                    if (player.wins > 0) append("   (${player.wins} 🏆)")
                    if (player.slot == state.mySlot) append("  — вы")
                }
                textSize = 16f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
                setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            binding.playersContainer.addView(row)
        }

        binding.roomInfo.text = "${state.players.size}/${com.fogofwar.game.core.GameConfig.MAX_PLAYERS}"
        binding.mapPreview.displayedMapId = state.mapId

        val host = state.isHost
        binding.mapSpinner.visibility = if (host) View.VISIBLE else View.GONE
        binding.startButton.visibility = if (host) View.VISIBLE else View.GONE
        binding.startButton.text = getString(R.string.start_game)

        if (host && binding.mapSpinner.selectedItemPosition != state.mapId) {
            suppressSpinner = true
            binding.mapSpinner.setSelection(state.mapId, false)
            suppressSpinner = false
        }

        binding.lobbyHint.text = state.status ?: when {
            !host -> "Карта: ${MapGenerator.MAP_NAMES.getOrElse(state.mapId) { "?" }}. " +
                getString(R.string.waiting_for_host)
            state.players.size <= 1 -> getString(R.string.solo_hint)
            else -> "Игроков: ${state.players.size}. Можно начинать."
        }
    }
}
