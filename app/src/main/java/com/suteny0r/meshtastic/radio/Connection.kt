package com.suteny0r.meshtastic.radio

import kotlinx.coroutines.flow.Flow
import org.meshtastic.proto.MeshProtos

/** Events emitted by an active radio connection (port of ConnectionEvent). */
sealed interface ConnectionEvent {
    data class Data(val fromRadio: MeshProtos.FromRadio) : ConnectionEvent
    data class LogMessage(val message: String) : ConnectionEvent
    data class RssiUpdate(val rssi: Int) : ConnectionEvent
    data class Disconnected(val shouldReconnect: Boolean, val error: String? = null) : ConnectionEvent
}

/** A live link to a radio (port of the Connection actor protocol). */
interface RadioConnection {
    val events: Flow<ConnectionEvent>

    /** Establish the link; returns once the link is ready for traffic. */
    suspend fun connect()

    /** Serialize and send one ToRadio message. */
    suspend fun send(toRadio: MeshProtos.ToRadio)

    /** Trigger a drain of any packets queued on the radio. */
    suspend fun startDrainPendingPackets()

    suspend fun disconnect()

    /** True for transports that need an app-driven heartbeat (TCP). */
    val requiresPeriodicHeartbeat: Boolean
}

/** A radio discovered during scanning. */
data class DiscoveredDevice(
    val id: String,
    val name: String,
    val rssi: Int,
    val lastSeenMs: Long,
)
