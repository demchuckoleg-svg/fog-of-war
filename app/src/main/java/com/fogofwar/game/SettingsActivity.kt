package com.fogofwar.game

import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.fogofwar.game.databinding.ActivitySettingsBinding
import com.google.android.material.materialswitch.MaterialSwitch

/** Local preferences that do not need to travel over the network. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { getSharedPreferences("fogofwar", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val scalePercent = (prefs.getFloat("stick_scale", 1f) * 100f).toInt().coerceIn(70, 140)
        binding.stickValue.text = getString(R.string.settings_percent, scalePercent)
        binding.stickSize.progress = scalePercent - 70
        binding.stickSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val percent = 70 + progress
                binding.stickValue.text = getString(R.string.settings_percent, percent)
                if (fromUser) {
                    prefs.edit().putFloat("stick_scale", percent / 100f).apply()
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        bindSwitch(binding.hapticsSwitch, "haptics", true)
        bindSwitch(binding.perfSwitch, "perf_overlay", false)
        bindSwitch(binding.hwSwitch, "hw_canvas", true)
        bindSwitch(binding.confirmSwitch, "confirm_exit", true)

        binding.backButton.setOnClickListener { finish() }
    }

    private fun bindSwitch(view: MaterialSwitch, key: String, default: Boolean) {
        view.isChecked = prefs.getBoolean(key, default)
        view.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
        }
    }
}
