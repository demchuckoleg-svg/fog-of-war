package com.fogofwar.game

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.fogofwar.game.core.GameConfig
import com.fogofwar.game.core.MapGenerator
import com.fogofwar.game.core.RoundRules
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
    private var suppressRules = false
    private var lastState: LobbyUiState = LobbyUiState()

    private enum class PendingAction { NONE, HOST, DISCOVER }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val action = pendingAction
        pendingAction = PendingAction.NONE
        if (result.values.all { it }) {
            ensureBluetoothOrPerform(action)
        } else {
            Toast.makeText(this, R.string.status_bad_permissions, Toast.LENGTH_LONG).show()
        }
    }

    private val bluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = pendingAction
        pendingAction = PendingAction.NONE
        if (result.resultCode == Activity.RESULT_OK) {
            perform(action)
        } else {
            Toast.makeText(this, R.string.status_bluetooth_required, Toast.LENGTH_LONG).show()
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
            ensurePermissions(PendingAction.DISCOVER)
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
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

        binding.modeSpinner.adapter = ArrayAdapter(
            this,
            R.layout.spinner_item,
            listOf(getString(R.string.rules_ffa), getString(R.string.rules_teams)),
        )
        binding.livesSpinner.adapter = ArrayAdapter(
            this,
            R.layout.spinner_item,
            listOf(
                getString(R.string.rules_lives_1),
                getString(R.string.rules_lives_3),
                getString(R.string.rules_lives_5),
            ),
        )
        binding.timeSpinner.adapter = ArrayAdapter(
            this,
            R.layout.spinner_item,
            listOf(
                getString(R.string.rules_time_0),
                getString(R.string.rules_time_3),
                getString(R.string.rules_time_5),
            ),
        )
        val rulesListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!suppressRules && net.isHost) net.updateRules(rulesFromUi())
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.modeSpinner.onItemSelectedListener = rulesListener
        binding.livesSpinner.onItemSelectedListener = rulesListener
        binding.timeSpinner.onItemSelectedListener = rulesListener
        binding.pickupsSwitch.setOnCheckedChangeListener { _, _ ->
            if (!suppressRules && net.isHost) net.updateRules(rulesFromUi())
        }
        binding.readyButton.setOnClickListener { net.setReady(!net.myReady) }
        render(lastState)
    }

    override fun onResume() {
        super.onResume()
        net.attachListener(this)
        render(net.lobbySnapshot())
        if (net.roomState == RoomState.IN_GAME) {
            startActivity(Intent(this, GameActivity::class.java))
        }
    }

    override fun onPause() {
        super.onPause()
        net.clearListener(this)
    }

    private fun openRooms() {
        startActivity(Intent(this, RoomsActivity::class.java))
    }

    private fun ensurePermissions(action: PendingAction) {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            ensureBluetoothOrPerform(action)
        } else {
            pendingAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun ensureBluetoothOrPerform(action: PendingAction) {
        if (action == PendingAction.NONE) return
        val adapter = try {
            (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        } catch (_: SecurityException) {
            null
        }
        if (adapter != null && !adapter.isEnabled) {
            pendingAction = action
            try {
                bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Throwable) {
                pendingAction = PendingAction.NONE
                Toast.makeText(this, R.string.status_bluetooth_required, Toast.LENGTH_LONG).show()
            }
        } else {
            perform(action)
        }
    }

    private fun perform(action: PendingAction) {
        when (action) {
            PendingAction.HOST -> net.hostRoom()
            PendingAction.DISCOVER -> openRooms()
            PendingAction.NONE -> Unit
        }
    }

    private fun requiredPermissions(): List<String> {
        val list = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            // Android 11 and 12/12L still use location for Wi-Fi-assisted discovery.
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rulesFromUi(): RoundRules = RoundRules(
        teams = binding.modeSpinner.selectedItemPosition == 1,
        lives = when (binding.livesSpinner.selectedItemPosition) {
            1 -> 3
            2 -> 5
            else -> 1
        },
        timeLimitSec = when (binding.timeSpinner.selectedItemPosition) {
            1 -> 180
            2 -> 300
            else -> 0
        },
        pickups = binding.pickupsSwitch.isChecked,
    )

    private fun applyRulesToUi(rules: RoundRules) {
        suppressRules = true
        binding.modeSpinner.setSelection(if (rules.teams) 1 else 0, false)
        binding.livesSpinner.setSelection(
            when (rules.lives) {
                3 -> 1
                5 -> 2
                else -> 0
            },
            false,
        )
        binding.timeSpinner.setSelection(
            when (rules.timeLimitSec) {
                180 -> 1
                300 -> 2
                else -> 0
            },
            false,
        )
        binding.pickupsSwitch.isChecked = rules.pickups
        suppressRules = false
    }

    private fun rulesSummary(rules: RoundRules): String {
        val mode = getString(if (rules.teams) R.string.rules_teams else R.string.rules_ffa)
        val lives = when (rules.lives) {
            3 -> getString(R.string.rules_lives_3)
            5 -> getString(R.string.rules_lives_5)
            else -> getString(R.string.rules_lives_1)
        }
        val time = when (rules.timeLimitSec) {
            180 -> getString(R.string.rules_time_3)
            300 -> getString(R.string.rules_time_5)
            else -> getString(R.string.rules_time_0)
        }
        return getString(
            if (rules.pickups) R.string.rules_summary_pickups else R.string.rules_summary,
            mode,
            lives,
            time,
        )
    }

    private fun render(state: LobbyUiState) {
        binding.statsText.text = getString(R.string.stats_lifetime, net.lifetimeWins, net.lifetimeGames)
        val inLobby = state.inLobby && state.roomState != RoomState.DISCOVERING
        binding.menuPanel.visibility = if (inLobby) View.GONE else View.VISIBLE
        binding.lobbyPanel.visibility = if (inLobby) View.VISIBLE else View.GONE

        binding.statusText.text = state.status ?: when (state.roomState) {
            RoomState.HOSTING -> getString(R.string.status_hosting)
            RoomState.DISCOVERING -> getString(R.string.status_searching)
            RoomState.CONNECTING -> getString(R.string.status_connecting)
            RoomState.IDLE -> getString(R.string.status_idle)
            else -> ""
        }

        if (!inLobby) return

        binding.playersContainer.removeAllViews()
        for (player in state.players) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            val label = TextView(this).apply {
                text = buildString {
                    append(if (player.isBot) "🤖 " else if (player.slot == 0) "👑 " else "• ")
                    append(player.name)
                    if (state.rules.teams) append("   [${if (player.team == 0) "A" else "B"}]")
                    if (player.ready) append("   ✓")
                    if (player.wins > 0) append("   (${player.wins} 🏆)")
                    if (player.slot == state.mySlot) append("  — вы")
                }
                textSize = 16f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (state.isHost && player.slot != 0 && !player.isBot) {
                val kick = MaterialButton(this).apply {
                    text = getString(R.string.kick)
                    textSize = 12f
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(10), 0, dp(10), 0)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.danger))
                    setOnClickListener { net.kick(player.slot) }
                }
                row.addView(kick, LinearLayout.LayoutParams(dp(64), dp(36)))
            }
            binding.playersContainer.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        binding.roomInfo.text = "${state.players.size}/${com.fogofwar.game.core.GameConfig.MAX_PLAYERS}"
        binding.roomCodeText.text = getString(R.string.room_code, state.roomCode)
        binding.roomCodeText.visibility = if (state.roomCode.isBlank()) View.GONE else View.VISIBLE
        binding.mapPreview.displayedMapId = state.mapId
        binding.mapDescription.text =
            MapGenerator.MAP_DESCRIPTIONS.getOrElse(state.mapId) { "" }

        val host = state.isHost
        binding.mapSpinner.visibility = if (host) View.VISIBLE else View.GONE
        binding.startButton.visibility = if (host) View.VISIBLE else View.GONE
        binding.startButton.text = getString(R.string.start_game)
        binding.rulesPanel.visibility = if (host) View.VISIBLE else View.GONE
        binding.rulesText.text = rulesSummary(state.rules)
        if (host) applyRulesToUi(state.rules)
        binding.readyButton.visibility = if (host) View.GONE else View.VISIBLE
        binding.readyButton.text =
            getString(if (net.myReady) R.string.not_ready else R.string.ready)

        if (host && binding.mapSpinner.selectedItemPosition != state.mapId) {
            suppressSpinner = true
            binding.mapSpinner.setSelection(state.mapId, false)
            suppressSpinner = false
        }

        binding.lobbyHint.text = state.status ?: when {
            !host -> "Карта: ${MapGenerator.MAP_NAMES.getOrElse(state.mapId) { "?" }}. " +
                getString(R.string.waiting_for_host)
            state.players.size <= 1 -> getString(R.string.solo_hint)
            else -> "Готовы: ${state.players.count { it.ready }}/${state.players.size}. Можно начинать."
        }
    }
}
