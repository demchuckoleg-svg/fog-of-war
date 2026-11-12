package com.fogofwar.game

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.fogofwar.game.databinding.ActivityGameBinding
import com.fogofwar.game.net.LobbyUiState
import com.fogofwar.game.net.NearbyNet
import com.fogofwar.game.net.RoomState

class GameActivity : AppCompatActivity(), NearbyNet.Listener {

    private lateinit var binding: ActivityGameBinding
    private val net: NearbyNet get() = (application as App).net

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.exitButton.setOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
    }

    private fun leave() {
        net.leaveGame()
        finish()
    }

    override fun onResume() {
        super.onResume()
        net.setListener(this)
        binding.gameView.resumeRendering()
        if (net.roomState != RoomState.IN_GAME) finish()
    }

    override fun onPause() {
        super.onPause()
        net.clearListener(this)
        binding.gameView.pauseRendering()
    }

    override fun onLobbyChanged(state: LobbyUiState) {
        if (state.roomState != RoomState.IN_GAME && !isFinishing) {
            finish()
        }
    }

    override fun onRoundStarted(mapId: Int) = Unit

    override fun onRoundEnded(winnerName: String?) = Unit

    override fun onNetworkError(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }
}
