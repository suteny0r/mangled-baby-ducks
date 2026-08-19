package com.suteny0r.meshtastic

import android.app.Application

class MeshtasticApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
