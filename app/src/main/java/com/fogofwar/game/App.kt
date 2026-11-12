package com.fogofwar.game

import android.app.Application
import com.fogofwar.game.net.NearbyNet

class App : Application() {
    lateinit var net: NearbyNet
        private set

    override fun onCreate() {
        super.onCreate()
        net = NearbyNet(this)
    }
}
