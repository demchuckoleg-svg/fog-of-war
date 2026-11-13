package com.fogofwar.game

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import com.fogofwar.game.databinding.ActivityGameBinding
import com.fogofwar.game.net.LobbyUiState
import com.fogofwar.game.net.NearbyNet
import com.fogofwar.game.net.RoomState
import com.fogofwar.game.render.GameView

class GameActivity : AppCompatActivity(), NearbyNet.Listener {

    private lateinit var binding: ActivityGameBinding
    private val net: NearbyNet get() = (application as App).net
    private val prefs by lazy { getSharedPreferences("fogofwar", MODE_PRIVATE) }

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

        binding.exitButton.setOnClickListener { confirmLeave() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeave()
        })

        ViewCompat.setOnApplyWindowInsetsListener(binding.gameView) { view, insets ->
            val cut = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            (view as GameView).setSafeInsets(cut.left, cut.top, cut.right, cut.bottom)
            insets
        }

        if (net.roomState == RoomState.IN_GAME && (net.currentFrame()?.tick ?: 0L) <= 1L) {
            binding.gameView.startCountdown()
        }
    }

    private fun confirmLeave() {
        if (isFinishing) return
        if (!prefs.getBoolean("confirm_exit", true)) {
            leave()
            return
        }
        val host = net.isHost
        AlertDialog.Builder(this)
            .setTitle(if (host) "Закрыть комнату?" else "Выйти из боя?")
            .setMessage(
                if (host) {
                    "Раунд закончится для всех игроков, комната закроется."
                } else {
                    "Вы покинете комнату и вернётесь в меню."
                }
            )
            .setPositiveButton(if (host) "Закрыть" else "Выйти") { _, _ -> leave() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun leave() {
        net.leaveGame()
        finish()
    }

    override fun onResume() {
        super.onResume()
        net.attachListener(this)
        if (net.roomState != RoomState.IN_GAME) {
            finish()
            return
        }
        binding.gameView.resumeRendering()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Notification shade / system bars: stop driving instead of holding the last stick.
        if (!hasFocus) binding.gameView.releaseInput()
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
