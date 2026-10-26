package com.suteny0r.mangledbabyducks.radio

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

    /**
     * Not in the Swift app, which simply refuses the 201st byte: cut a long message into
     * packet-sized pieces to be sent one after another.
     *
     * Breaks land on whitespace, so a word is never split and the whitespace that was
     * broken on is dropped rather than sent as a leading space. The one exception is an
     * unbroken run longer than a whole packet (a long URL): it is cut on a code point
     * boundary, because the alternative is a message that can never be sent at all.
     *
     * Sizing is UTF-8 bytes, not characters; the limit is what the radio accepts.
     */
    fun splitForWire(text: String, limit: Int = MAX_TEXT_BYTES): List<String> {
        var rest = text.trim()
        if (rest.isEmpty()) return emptyList()
        if (rest.encodeToByteArray().size <= limit) return listOf(rest)

        val parts = mutableListOf<String>()
        while (rest.isNotEmpty()) {
            if (rest.encodeToByteArray().size <= limit) {
                parts += rest
                break
            }
            // Every char costs at least one byte, so `limit` chars is never short of the
            // true cut; walk down from there instead of from the whole remaining length,
            // which would re-encode a long message once per character.
            var cut = minOf(rest.length, limit)
            if (cut < rest.length && Character.isLowSurrogate(rest[cut])) cut--
            while (cut > 0 && rest.substring(0, cut).encodeToByteArray().size > limit) {
                cut = rest.offsetByCodePoints(cut, -1)
            }
            var brk = cut
            while (brk > 0 && !rest[brk - 1].isWhitespace()) brk--
            if (brk == 0) {
                parts += rest.substring(0, cut)
                rest = rest.substring(cut)
            } else {
                parts += rest.substring(0, brk).trimEnd()
                rest = rest.substring(brk).trimStart()
            }
        }
        return parts.filter { it.isNotEmpty() }
    }
}

/** uint32 proto field (Java int) to unsigned Long. */
fun Int.uint(): Long = toLong() and 0xFFFFFFFFL
