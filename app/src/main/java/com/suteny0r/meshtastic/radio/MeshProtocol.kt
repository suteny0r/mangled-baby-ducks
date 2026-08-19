package com.suteny0r.meshtastic.radio

import java.util.UUID

/**
 * Protocol constants mirrored from Meshtastic-Apple
 * (Meshtastic/Accessory/Transports/Bluetooth Low Energy/BLEConnection.swift and
 * Meshtastic/Accessory/Accessory Manager/AccessoryManager.swift).
 */
object MeshProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
    val TORADIO_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
    val FROMRADIO_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
    val FROMNUM_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
    val LOGRADIO_UUID: UUID = UUID.fromString("5a3d6e49-06e6-4423-9944-e9de8cdf9547")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** want_config_id nonce for the config handshake. */
    const val NONCE_ONLY_CONFIG = 69420
    /** want_config_id nonce for the node DB dump. */
    const val NONCE_ONLY_DB = 69421

    /** TCP framing magic and default port. */
    const val MAGIC_0 = 0x94.toByte()
    const val MAGIC_1 = 0xC3.toByte()
    const val DEFAULT_TCP_PORT = 4403

    /** Broadcast destination (Constants.maximumNodeNum). */
    const val BROADCAST_NUM = 0xFFFFFFFFL
    const val MINIMUM_NODE_NUM = 4L

    /** Max UTF-8 payload bytes for a text message. */
    const val MAX_TEXT_BYTES = 200

    const val WRITE_ATTEMPT_LIMIT = 4
    const val HEARTBEAT_INTERVAL_MS = 15_000L
}

/** uint32 proto field (Java int) to unsigned Long. */
fun Int.uint(): Long = toLong() and 0xFFFFFFFFL
