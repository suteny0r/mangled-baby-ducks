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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Android take on iOS's LocationsHandler + provideLocation. Two jobs, gated
 * separately:
 *
 * - Track the phone's location whenever permission is granted, so [lastFix] is there for
 *   anything that needs "where am I": distance and bearing on the node lists, "Exchange
 *   Positions", the car map's anchor. iOS's LocationsHandler runs as soon as permission
 *   exists; without this the lists had no reference point until sharing was turned on or
 *   the radio broadcast its own position.
 * - Send each fix to the mesh as this node's position only while sharing is enabled and
 *   the radio is connected.
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
    /** Sharing enabled and the radio live: fixes go to the mesh. */
    private var sharing = false

    /** Last fix seen, from the tracker above; null until permission is granted and a fix lands. */
    data class GpsFix(val latitudeI: Int, val longitudeI: Int, val altitude: Int)
    val lastFix = MutableStateFlow<GpsFix?>(null)

    private val listener = LocationListener { location -> onFix(location) }

    init {
        ensureTracking()
        scope.launch {
            combine(
                prefs.data.map { it[PrefKeys.SHARE_LOCATION] ?: false },
                radioManager.state,
            ) { enabled, state -> enabled && state is RadioState.Subscribed }
                .distinctUntilChanged()
                .collect { active ->
                    sharing = active
                    // Permission may have been granted since start-up; every state change
                    // is a cheap moment to pick it up.
                    ensureTracking()
                    Log.i(TAG, if (active) "Location sharing on" else "Location sharing off")
                }
        }
    }

    /**
     * Start listening if permission allows and we are not already. Safe to call often;
     * MainActivity's resume calls it so a permission granted in Settings takes effect.
     */
    @SuppressLint("MissingPermission")
    fun ensureTracking() {
        if (listening) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // Seed from the last known fix so the lists have a reference point before the
        // first update arrives.
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { lastFix.value = it.toFix() }
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
        Log.i(TAG, "Location tracking started ($provider)")
    }

    private fun Location.toFix() = GpsFix(
        latitudeI = (latitude * 1e7).toInt(),
        longitudeI = (longitude * 1e7).toInt(),
        altitude = altitude.toInt(),
    )

    private fun onFix(location: Location) {
        val fix = location.toFix()
        lastFix.value = fix
        if (!sharing) return
        scope.launch(Dispatchers.IO) {
            radioManager.sendPhonePosition(fix.latitudeI, fix.longitudeI, fix.altitude)
        }
    }

    companion object {
        private const val TAG = "LocationSharer"
        private const val UPDATE_INTERVAL_MS = 60_000L
        private const val MIN_DISTANCE_M = 25f
    }
}
