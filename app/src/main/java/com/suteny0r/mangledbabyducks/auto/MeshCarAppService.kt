package com.suteny0r.mangledbabyducks.auto

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.suteny0r.mangledbabyducks.container
import kotlinx.coroutines.launch

/**
 * Android Auto entry point (Car App Library). The host binds this service when the phone
 * is plugged into a car and the user opens the app from the car launcher; it has no
 * counterpart in Meshtastic-Apple. Everything shown on the head unit is built from the
 * same Room flows and the same RadioManager the phone UI uses, so the car session is
 * just another observer of the one radio link.
 */
class MeshCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Debug builds also talk to the Desktop Head Unit, which is not on the allowlist.
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = MeshCarSession()
}

/** One head-unit session: reconnects the remembered radio and opens on the node list. */
class MeshCarSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        // The car can come up before the phone app has been opened this process; give the
        // remembered radio its one automatic attempt just as MainActivity would.
        lifecycleScope.launch { carContext.container.autoConnectIfRemembered(carContext) }
        // The session opens on the node list, which is what the driver actually wants to
        // see, with the menu kept underneath it so the list's BACK action still reaches
        // the rest of the app instead of ending the session. The push waits for ON_START:
        // the root returned here is only put on the stack once this call returns, and
        // lifecycleScope runs on Main.immediate, so pushing inline would land before it.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                owner.lifecycle.removeObserver(this)
                carContext.getCarService(ScreenManager::class.java).push(CarNodesScreen(carContext))
            }
        })
        return CarHomeScreen(carContext)
    }
}
