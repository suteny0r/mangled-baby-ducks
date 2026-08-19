package com.suteny0r.meshtastic

import android.content.Context
import com.suteny0r.meshtastic.db.MeshDatabase
import com.suteny0r.meshtastic.radio.BleScanner
import com.suteny0r.meshtastic.radio.MessageNotifier
import com.suteny0r.meshtastic.radio.PacketIngest
import com.suteny0r.meshtastic.radio.RadioManager

/** Manual singleton graph; the app is small enough not to need a DI framework yet. */
class AppContainer(context: Context) {
    val database: MeshDatabase = MeshDatabase.build(context)
    val ingest = PacketIngest(database)
    val radioManager = RadioManager(database, ingest)
    val bleScanner = BleScanner(context)
    val messageNotifier = MessageNotifier(context, database, radioManager)
}

val Context.container: AppContainer
    get() = (applicationContext as MeshtasticApplication).container
