package com.suteny0r.mangledbabyducks

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.suteny0r.mangledbabyducks.db.MeshDatabase
import com.suteny0r.mangledbabyducks.radio.BleScanner
import com.suteny0r.mangledbabyducks.radio.HardwareCatalog
import com.suteny0r.mangledbabyducks.radio.LanScanner
import com.suteny0r.mangledbabyducks.radio.LocationSharer
import com.suteny0r.mangledbabyducks.radio.MeshProtocol
import com.suteny0r.mangledbabyducks.radio.MessageNotifier
import com.suteny0r.mangledbabyducks.radio.PacketIngest
import com.suteny0r.mangledbabyducks.radio.PresenceProbe
import com.suteny0r.mangledbabyducks.radio.RadioConnection
import com.suteny0r.mangledbabyducks.radio.RadioManager
import com.suteny0r.mangledbabyducks.radio.RadioService
import com.suteny0r.mangledbabyducks.radio.TcpConnection
import com.suteny0r.mangledbabyducks.ui.Router
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.settingsDataStore by preferencesDataStore("settings")

/** DataStore keys for the remembered radio, used for reconnect on app start. */
object PrefKeys {
    // The auto-connect target: the radio the app reaches for on launch.
    val RADIO_TYPE = stringPreferencesKey("radio_type") // "ble" or "tcp"
    val RADIO_ADDRESS = stringPreferencesKey("radio_address") // MAC or host:port
    val RADIO_NAME = stringPreferencesKey("radio_name")

    /** Every radio this app has connected to, as a JSON array; see [knownRadios]. */
    val KNOWN_RADIOS = stringPreferencesKey("known_radios")

    val SHARE_LOCATION = booleanPreferencesKey("share_location")
}

/** How many radios to keep before the least recently used one is dropped. */
private const val MAX_KNOWN_RADIOS = 12

/** A radio the app has connected to, as stored in DataStore. */
data class RememberedRadio(
    val type: String,
    val address: String,
    val name: String?,
    val lastConnectedMs: Long = 0L,
) {
    /** What the UI calls this radio; the address is the fallback when no name was saved. */
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: address
}

/** The radio the app reaches for on launch, or null once auto-connect has been cleared. */
fun Preferences.rememberedRadio(): RememberedRadio? {
    val type = this[PrefKeys.RADIO_TYPE] ?: return null
    val address = this[PrefKeys.RADIO_ADDRESS] ?: return null
    return RememberedRadio(type, address, this[PrefKeys.RADIO_NAME])
}

/**
 * Every radio the app has connected to, most recent first, so the user can pick one
 * without scanning. Installs that predate the list contribute their single auto-connect
 * target, which is the migration path.
 */
fun Preferences.knownRadios(): List<RememberedRadio> {
    val stored = this[PrefKeys.KNOWN_RADIOS]
    val parsed = if (stored.isNullOrBlank()) {
        emptyList()
    } else {
        runCatching { decodeRadios(stored) }.getOrDefault(emptyList())
    }
    val target = rememberedRadio()
    val merged = if (target != null && parsed.none { it.address == target.address }) {
        parsed + target
    } else {
        parsed
    }
    return merged.sortedByDescending { it.lastConnectedMs }
}

private fun encodeRadios(radios: List<RememberedRadio>): String {
    val array = JSONArray()
    radios.forEach { radio ->
        array.put(
            JSONObject().apply {
                put("type", radio.type)
                put("address", radio.address)
                radio.name?.let { put("name", it) }
                put("lastConnected", radio.lastConnectedMs)
            }
        )
    }
    return array.toString()
}

private fun decodeRadios(json: String): List<RememberedRadio> {
    val array = JSONArray(json)
    return (0 until array.length()).mapNotNull { index ->
        val entry = array.optJSONObject(index) ?: return@mapNotNull null
        val type = entry.optString("type").ifEmpty { return@mapNotNull null }
        val address = entry.optString("address").ifEmpty { return@mapNotNull null }
        RememberedRadio(
            type = type,
            address = address,
            name = entry.optString("name").ifEmpty { null },
            lastConnectedMs = entry.optLong("lastConnected"),
        )
    }
}

/** Manual singleton graph; the app is small enough not to need a DI framework yet. */
class AppContainer(context: Context) {
    val database: MeshDatabase = MeshDatabase.build(context)
    val ingest = PacketIngest(database)
    val radioManager = RadioManager(database, ingest)
    val bleScanner = BleScanner(context)
    val lanScanner = LanScanner(context)
    val messageNotifier = MessageNotifier(context, database, radioManager)
    val router = Router()
    val prefs = context.settingsDataStore
    val locationSharer = LocationSharer(context, radioManager, prefs)
    /** Device catalog for the node detail hardware card; refreshes at most every 48 h. */
    val hardwareCatalog = HardwareCatalog(context).also { it.refresh() }

    suspend fun rememberedRadio(): RememberedRadio? = prefs.data.first().rememberedRadio()

    suspend fun knownRadios(): List<RememberedRadio> = prefs.data.first().knownRadios()

    /**
     * Record a successful connection: the radio becomes the auto-connect target and
     * joins the saved list, so it can be picked again without a scan.
     */
    suspend fun rememberRadio(type: String, address: String, name: String?) {
        prefs.edit { prefs ->
            prefs[PrefKeys.RADIO_TYPE] = type
            prefs[PrefKeys.RADIO_ADDRESS] = address
            if (name.isNullOrBlank()) prefs.remove(PrefKeys.RADIO_NAME) else prefs[PrefKeys.RADIO_NAME] = name
            val entry = RememberedRadio(type, address, name, System.currentTimeMillis())
            val others = prefs.knownRadios().filterNot { it.address == address }
            prefs[PrefKeys.KNOWN_RADIOS] = encodeRadios((listOf(entry) + others).take(MAX_KNOWN_RADIOS))
        }
    }

    /**
     * Reconnect to the last radio, mirroring iOS's preferredPeripheral. Shared by
     * MainActivity (onCreate, permission result, onResume) and the Android Auto session,
     * which can start while the phone app is not on screen. RadioManager spends exactly
     * one automatic attempt per process, so a radio that is off or out of range is not
     * chased in the background; the Connect screen (phone or car) offers Reconnect.
     */
    suspend fun autoConnectIfRemembered(context: Context) {
        val target = rememberedRadio() ?: return
        val factory = connectionFactory(target) ?: return
        if (target.type == "ble" &&
            (!hasBlePermission(context) || bleScanner.adapter?.isEnabled != true)
        ) {
            return
        }
        val radio = radioManager
        if (radio.isConnected || radio.isAttempting) return
        // A car session is bound by the Android Auto host rather than started from an
        // Activity; if the OS refuses the foreground service the link still gets tried.
        runCatching { RadioService.start(context, target.label) }
        radio.autoConnect(target.label, presenceProbe(target), factory)
        if (radio.isConnected) {
            // Keeps "last used" ordering in the saved list honest.
            rememberRadio(target.type, target.address, target.name)
        }
        // The service only earns its notification while a link is up or being made.
        if (!radio.isConnected && !radio.isAttempting) RadioService.stop(context)
    }

    /**
     * Explicit reconnect to a saved radio (the car's Reconnect button and the phone's
     * failed-state retry): bypasses the one-attempt auto-connect budget.
     */
    suspend fun connectKnown(context: Context, target: RememberedRadio) {
        val factory = connectionFactory(target) ?: return
        runCatching { RadioService.start(context, target.label) }
        runCatching { radioManager.connect(target.label, presenceProbe(target), factory) }
        if (radioManager.isConnected) rememberRadio(target.type, target.address, target.name)
    }

    /**
     * A deliberate Disconnect, from the phone's Connect tab or the car's Radio screen:
     * tear the link down, drop the foreground service, and stop auto-connecting on the
     * next launch. The radio stays in the saved list.
     */
    suspend fun disconnectRadio(context: Context) {
        radioManager.disconnect()
        RadioService.stop(context)
        clearAutoConnectTarget()
    }

    fun hasBlePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Presence check for a saved radio: BLE waits for the advertisement before touching
     * the GATT stack, TCP has nothing to scan for.
     */
    fun presenceProbe(radio: RememberedRadio): PresenceProbe? = when (radio.type) {
        "ble" -> PresenceProbe { timeoutMs -> bleScanner.isAdvertising(radio.address, timeoutMs) }
        else -> null
    }

    /** Connection builder for a remembered radio, or null for an unknown transport. */
    fun connectionFactory(radio: RememberedRadio): (() -> RadioConnection)? = when (radio.type) {
        "ble" -> ({ bleScanner.connection(radio.address) })
        "tcp" -> {
            val host = radio.address.substringBefore(':')
            val port = radio.address.substringAfter(':', "")
                .toIntOrNull() ?: MeshProtocol.DEFAULT_TCP_PORT
            ({ TcpConnection(host, port) })
        }
        else -> null
    }

    /**
     * Stop auto-connecting on launch without losing the radio from the saved list —
     * what a deliberate Disconnect means.
     */
    suspend fun clearAutoConnectTarget() {
        prefs.edit { prefs ->
            prefs.remove(PrefKeys.RADIO_TYPE)
            prefs.remove(PrefKeys.RADIO_ADDRESS)
            prefs.remove(PrefKeys.RADIO_NAME)
        }
    }

    /** Drop a radio from the saved list, and from auto-connect if it was the target. */
    suspend fun forgetRadio(address: String) {
        prefs.edit { prefs ->
            prefs[PrefKeys.KNOWN_RADIOS] =
                encodeRadios(prefs.knownRadios().filterNot { it.address == address })
            if (prefs[PrefKeys.RADIO_ADDRESS] == address) {
                prefs.remove(PrefKeys.RADIO_TYPE)
                prefs.remove(PrefKeys.RADIO_ADDRESS)
                prefs.remove(PrefKeys.RADIO_NAME)
            }
        }
    }
}

val Context.container: AppContainer
    get() = (applicationContext as MeshtasticApplication).container
