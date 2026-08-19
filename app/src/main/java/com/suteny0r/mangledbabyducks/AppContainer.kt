package com.suteny0r.mangledbabyducks

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.suteny0r.mangledbabyducks.db.MeshDatabase
import com.suteny0r.mangledbabyducks.radio.BleScanner
import com.suteny0r.mangledbabyducks.radio.LocationSharer
import com.suteny0r.mangledbabyducks.radio.MessageNotifier
import com.suteny0r.mangledbabyducks.radio.PacketIngest
import com.suteny0r.mangledbabyducks.radio.RadioManager
import com.suteny0r.mangledbabyducks.ui.Router

private val Context.settingsDataStore by preferencesDataStore("settings")

/** DataStore keys for the remembered radio, used for reconnect on app start. */
object PrefKeys {
    val RADIO_TYPE = stringPreferencesKey("radio_type") // "ble" or "tcp"
    val RADIO_ADDRESS = stringPreferencesKey("radio_address") // MAC or host:port
    val RADIO_NAME = stringPreferencesKey("radio_name")
    val SHARE_LOCATION = booleanPreferencesKey("share_location")
}

/** Manual singleton graph; the app is small enough not to need a DI framework yet. */
class AppContainer(context: Context) {
    val database: MeshDatabase = MeshDatabase.build(context)
    val ingest = PacketIngest(database)
    val radioManager = RadioManager(database, ingest)
    val bleScanner = BleScanner(context)
    val messageNotifier = MessageNotifier(context, database, radioManager)
    val router = Router()
    val prefs = context.settingsDataStore
    val locationSharer = LocationSharer(context, radioManager, prefs)
}

val Context.container: AppContainer
    get() = (applicationContext as MeshtasticApplication).container
