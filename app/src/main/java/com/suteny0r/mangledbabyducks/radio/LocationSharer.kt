package com.suteny0r.mangledbabyducks.radio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The Android take on iOS's LocationsHandler + provideLocation, with one deliberate
 * difference: this app does not run the phone's GPS in the background.
 *
 * A node's position normally comes from the radio. The phone contributes in three ways,
 * each gated separately:
 *
 * - [lastFix], passive: whatever location the phone is already maintaining on its own
 *   (the system's last known fix, plus the passive provider while the app is on screen).
 *   This never switches a receiver on. It is the reference point for distance and bearing
 *   when the radio has no position.
 * - [currentFix], one shot: a fix taken now, for the composer's map link when the radio
 *   has no position. The receiver is on for that fix and off again.
 * - The opt-in "Share phone location" switch, off by default: each fix goes to the mesh
 *   as this node's position, for a radio without GPS hardware, and only while connected.
 *
 * An earlier version requested GPS updates for the life of the process regardless of the
 * switch. With the `location` foreground service keeping that alive behind a dark screen,
 * the GNSS receiver ran around the clock and the app showed up as a quarter of the
 * phone's battery in a day.
 */
class LocationSharer(
    private val context: Context,
    private val radioManager: RadioManager,
    prefs: DataStore<Preferences>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var sharingActive = false
    private var passiveActive = false
    private var lastRefreshMs = 0L

    data class GpsFix(val latitudeI: Int, val longitudeI: Int, val altitude: Int)

    /** The phone's own idea of where it is; null without permission or before any fix. */
    val lastFix = MutableStateFlow<GpsFix?>(null)

    private val passiveListener = FixListener { lastFix.value = it.toFix() }

    private val sharingListener = FixListener { location ->
        val fix = location.toFix()
        lastFix.value = fix
        scope.launch(Dispatchers.IO) {
            radioManager.sendPhonePosition(fix.latitudeI, fix.longitudeI, fix.altitude)
        }
    }

    init {
        refreshLastKnown()
        scope.launch {
            combine(
                prefs.data.map { it[PrefKeys.SHARE_LOCATION] ?: false },
                radioManager.state,
            ) { enabled, state -> enabled && state is RadioState.Subscribed }
                .distinctUntilChanged()
                .collect { active ->
                    if (active) startSharing() else stopSharing()
                    Log.i(TAG, if (active) "Location sharing on" else "Location sharing off")
                }
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Re-read the fix the system already holds. A binder call, no receiver involved; safe
     * to call often, and throttled anyway because the car screens call it per template.
     */
    @SuppressLint("MissingPermission")
    fun refreshLastKnown() {
        if (!hasPermission()) return
        val now = SystemClock.elapsedRealtime()
        if (lastRefreshMs != 0L && now - lastRefreshMs < REFRESH_FLOOR_MS) return
        lastRefreshMs = now
        newestLastKnown()?.let { lastFix.value = it.toFix() }
    }

    @SuppressLint("MissingPermission")
    private fun newestLastKnown(): Location? =
        runCatching { locationManager.allProviders }.getOrDefault(emptyList())
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }

    /**
     * The app is on screen: follow the passive provider, which only relays fixes that
     * something else on the phone asked for. MainActivity pairs this with [onBackground].
     */
    @SuppressLint("MissingPermission")
    fun onForeground() {
        refreshLastKnown()
        if (passiveActive || !hasPermission()) return
        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.PASSIVE_PROVIDER,
                UPDATE_INTERVAL_MS,
                MIN_DISTANCE_M,
                passiveListener,
                Looper.getMainLooper(),
            )
            passiveActive = true
        }.onFailure { Log.w(TAG, "Passive provider unavailable: ${it.message}") }
    }

    fun onBackground() {
        if (!passiveActive) return
        runCatching { locationManager.removeUpdates(passiveListener) }
        passiveActive = false
    }

    @SuppressLint("MissingPermission")
    private fun startSharing() {
        if (sharingActive || !hasPermission()) return
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
            sharingListener,
            Looper.getMainLooper(),
        )
        sharingActive = true
        Log.i(TAG, "Location tracking started ($provider)")
    }

    private fun stopSharing() {
        if (!sharingActive) return
        runCatching { locationManager.removeUpdates(sharingListener) }
        sharingActive = false
        Log.i(TAG, "Location tracking stopped")
    }

    /**
     * One phone fix, taken now, or null when there is no permission, no provider, or no
     * fix inside the time allowed. A fix the system already holds is reused when it is
     * under [FRESH_MS] old. Otherwise GPS is tried first, and the network provider is the
     * fallback for indoors, where GPS can search for minutes and find nothing.
     */
    @SuppressLint("MissingPermission")
    suspend fun currentFix(): GpsFix? = withContext(Dispatchers.Main) {
        if (!hasPermission()) return@withContext null
        newestLastKnown()
            ?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < FRESH_MS * 1_000_000 }
            ?.let { return@withContext it.toFix().also { fix -> lastFix.value = fix } }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
        for (provider in providers) {
            val timeout =
                if (provider == LocationManager.GPS_PROVIDER) GPS_FIX_TIMEOUT_MS else NETWORK_FIX_TIMEOUT_MS
            val location = withTimeoutOrNull(timeout) { singleFix(provider) }
            if (location != null) {
                Log.i(TAG, "One-shot fix from $provider")
                return@withContext location.toFix().also { lastFix.value = it }
            }
            Log.i(TAG, "No fix from $provider within ${timeout / 1000} s")
        }
        null
    }

    /** Listen on [provider] until its first fix, then stop; cancellation stops it too. */
    @SuppressLint("MissingPermission")
    private suspend fun singleFix(provider: String): Location =
        suspendCancellableCoroutine { cont ->
            lateinit var once: FixListener
            once = FixListener { location ->
                runCatching { locationManager.removeUpdates(once) }
                if (cont.isActive) cont.resume(location)
            }
            cont.invokeOnCancellation { runCatching { locationManager.removeUpdates(once) } }
            locationManager.requestLocationUpdates(provider, 0L, 0f, once, Looper.getMainLooper())
        }

    private fun Location.toFix() = GpsFix(
        latitudeI = (latitude * 1e7).toInt(),
        longitudeI = (longitude * 1e7).toInt(),
        altitude = altitude.toInt(),
    )

    /**
     * A full [LocationListener], not a lambda: below API 30 the provider and status
     * callbacks are abstract, and the system calls them.
     */
    private class FixListener(private val onFix: (Location) -> Unit) : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    companion object {
        private const val TAG = "LocationSharer"
        private const val UPDATE_INTERVAL_MS = 60_000L
        private const val MIN_DISTANCE_M = 25f
        private const val REFRESH_FLOOR_MS = 10_000L
        /** A fix the system already holds counts as current up to this age. */
        private const val FRESH_MS = 60_000L
        private const val GPS_FIX_TIMEOUT_MS = 20_000L
        private const val NETWORK_FIX_TIMEOUT_MS = 5_000L
    }
}
