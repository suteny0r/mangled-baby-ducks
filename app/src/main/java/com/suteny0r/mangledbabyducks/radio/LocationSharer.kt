package com.suteny0r.mangledbabyducks.radio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.suteny0r.mangledbabyducks.PrefKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Feeds the phone's GPS to the mesh as this node's position while enabled and
 * connected — the Android take on iOS's provideLocation/LocationsHandler.
 */
class LocationSharer(
    private val context: Context,
    private val radioManager: RadioManager,
    prefs: DataStore<Preferences>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listening = false

    private val listener = LocationListener { location -> onFix(location) }

    init {
        scope.launch {
            combine(
                prefs.data.map { it[PrefKeys.SHARE_LOCATION] ?: false },
                radioManager.state,
            ) { enabled, state -> enabled && state is RadioState.Subscribed }
                .distinctUntilChanged()
                .collect { active -> if (active) start() else stop() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (listening) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Location sharing enabled but permission missing")
            return
        }
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> {
                Log.w(TAG, "No location provider available")
                return
            }
        }
        locationManager.requestLocationUpdates(
            provider,
            UPDATE_INTERVAL_MS,
            MIN_DISTANCE_M,
            listener,
            Looper.getMainLooper(),
        )
        listening = true
        Log.i(TAG, "Location sharing started ($provider)")
    }

    private fun stop() {
        if (!listening) return
        locationManager.removeUpdates(listener)
        listening = false
        Log.i(TAG, "Location sharing stopped")
    }

    private fun onFix(location: Location) {
        scope.launch(Dispatchers.IO) {
            radioManager.sendPhonePosition(
                latitudeI = (location.latitude * 1e7).toInt(),
                longitudeI = (location.longitude * 1e7).toInt(),
                altitude = location.altitude.toInt(),
            )
        }
    }

    companion object {
        private const val TAG = "LocationSharer"
        private const val UPDATE_INTERVAL_MS = 60_000L
        private const val MIN_DISTANCE_M = 25f
    }
}
