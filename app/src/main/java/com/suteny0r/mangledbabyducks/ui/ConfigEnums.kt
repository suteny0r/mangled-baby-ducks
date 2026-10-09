package com.suteny0r.mangledbabyducks.ui

import org.meshtastic.proto.ConfigProtos.Config
import java.math.BigInteger
import java.security.SecureRandom

// The words and option sets the config forms use, from FieldMetadataRegistry.swift (labels
// and descriptions per proto field and enum value), IntervalEnums.swift, DisplayEnums.swift,
// PositionConfigEnums.swift and DeviceRoles. Values are the proto's; only the strings and
// orderings live here.

// ---------------------------------------------------------------------------------------
// Intervals (UpdateInterval + IntervalConfiguration)

/** UpdateInterval.description for the fixed values; anything else is "Custom: n Seconds". */
fun intervalLabel(seconds: Int): String = when (seconds) {
    0 -> "Unset"
    1 -> "One Second"
    5 -> "Five Seconds"
    10 -> "Ten Seconds"
    15 -> "Fifteen Seconds"
    30 -> "Thirty Seconds"
    45 -> "Forty Five Seconds"
    60 -> "One Minute"
    120 -> "Two Minutes"
    300 -> "Five Minutes"
    600 -> "Ten Minutes"
    900 -> "Fifteen Minutes"
    1800 -> "Thirty Minutes"
    3600 -> "One Hour"
    7200 -> "Two Hours"
    10800 -> "Three Hours"
    14400 -> "Four Hours"
    18000 -> "Five Hours"
    21600 -> "Six Hours"
    43200 -> "Twelve Hours"
    64800 -> "Eighteen Hours"
    86400 -> "Twenty Four Hours"
    129600 -> "Thirty Six Hours"
    172800 -> "Forty Eight Hours"
    259200 -> "Seventy Two Hours"
    Int.MAX_VALUE -> "Never"
    else -> "Custom: ${seconds.toUInt()} Seconds"
}

/** IntervalConfiguration.allowedCases. */
object Intervals {
    private const val NEVER = Int.MAX_VALUE
    val broadcastMedium = listOf(3600, 7200, 10800, 14400, 18000, 21600, 43200, 64800, 86400, 129600, 172800, 259200, NEVER)
    val broadcastLong = listOf(10800, 14400, 18000, 21600, 43200, 64800, 86400, 129600, 172800, 259200, NEVER)
    val smartBroadcastMinimum = listOf(15, 30, 45, 60, 300, 600, 900, 1800, 3600)
    val waitBluetooth = listOf(0, 15, 30, 60, 120, 300, 600, 900, 1800)
}

/** GpsUpdateIntervals. */
val GPS_UPDATE_INTERVALS: List<Pair<Int, String>> = listOf(
    30 to "Thirty Seconds", 60 to "One Minute", 120 to "Two Minutes", 300 to "Five Minutes",
    600 to "Ten Minutes", 900 to "Fifteen Minutes", 1800 to "Thirty Minutes", 3600 to "One Hour",
    21600 to "Six Hours", 43200 to "Twelve Hours", 86400 to "Twenty Four Hours", Int.MAX_VALUE to "On Boot Only",
)

/** ScreenOnIntervals. */
val SCREEN_ON_INTERVALS: List<Pair<Int, String>> = listOf(
    15 to "Fifteen Seconds", 30 to "Thirty Seconds", 60 to "One Minute", 300 to "Five Minutes",
    600 to "Ten Minutes", 900 to "Fifteen Minutes", 1800 to "Thirty Minutes", 3600 to "One Hour",
    31536000 to "Always On",
)

/** ScreenCarouselIntervals. */
val SCREEN_CAROUSEL_INTERVALS: List<Pair<Int, String>> = listOf(
    0 to "Off", 15 to "Fifteen Seconds", 30 to "Thirty Seconds", 60 to "One Minute",
    300 to "Five Minutes", 600 to "Ten Minutes", 900 to "Fifteen Minutes",
)

// ---------------------------------------------------------------------------------------
// Enum value labels and descriptions (FieldMetadataRegistry)

/** DeviceRoles.allCases: the app's order for the role picker; Repeater stays only while current. */
val DEVICE_ROLE_ORDER: List<Config.DeviceConfig.Role> = listOf(
    Config.DeviceConfig.Role.CLIENT, Config.DeviceConfig.Role.CLIENT_MUTE, Config.DeviceConfig.Role.CLIENT_HIDDEN,
    Config.DeviceConfig.Role.TRACKER, Config.DeviceConfig.Role.LOST_AND_FOUND, Config.DeviceConfig.Role.SENSOR,
    Config.DeviceConfig.Role.TAK, Config.DeviceConfig.Role.TAK_TRACKER, Config.DeviceConfig.Role.ROUTER,
    Config.DeviceConfig.Role.ROUTER_LATE, Config.DeviceConfig.Role.CLIENT_BASE,
)

fun roleLabel(role: Config.DeviceConfig.Role): String = when (role) {
    Config.DeviceConfig.Role.CLIENT -> "Client"
    Config.DeviceConfig.Role.CLIENT_MUTE -> "Client Mute"
    Config.DeviceConfig.Role.ROUTER -> "Router"
    Config.DeviceConfig.Role.ROUTER_CLIENT -> "Router Client"
    Config.DeviceConfig.Role.REPEATER -> "Repeater"
    Config.DeviceConfig.Role.TRACKER -> "Tracker"
    Config.DeviceConfig.Role.SENSOR -> "Sensor"
    Config.DeviceConfig.Role.TAK -> "TAK"
    Config.DeviceConfig.Role.CLIENT_HIDDEN -> "Client Hidden"
    Config.DeviceConfig.Role.LOST_AND_FOUND -> "Lost and Found"
    Config.DeviceConfig.Role.TAK_TRACKER -> "TAK Tracker"
    Config.DeviceConfig.Role.ROUTER_LATE -> "Router Late"
    Config.DeviceConfig.Role.CLIENT_BASE -> "Client Base"
    Config.DeviceConfig.Role.UNRECOGNIZED -> "Unknown"
}

fun roleDescription(role: Config.DeviceConfig.Role): String? = when (role) {
    Config.DeviceConfig.Role.CLIENT -> "App connected or stand alone messaging device."
    Config.DeviceConfig.Role.CLIENT_MUTE -> "Device that does not forward packets from other devices."
    Config.DeviceConfig.Role.ROUTER -> "Infrastructure node on a tower or mountain top only.  Not to be used for roofs or mobile nodes.  Needs exceptional coverage. Visible in Nodes list."
    Config.DeviceConfig.Role.REPEATER -> "Deprecated infrastructure role that creates gaps in the mesh rebroadcast chain. Switch this node to a Router-based role (Router or Router Late)."
    Config.DeviceConfig.Role.TRACKER -> "Broadcasts GPS position packets as priority."
    Config.DeviceConfig.Role.SENSOR -> "Broadcasts telemetry packets as priority."
    Config.DeviceConfig.Role.TAK -> "Optimized for ATAK system communication, reduces routine broadcasts."
    Config.DeviceConfig.Role.CLIENT_HIDDEN -> "Device that only broadcasts as needed for stealth or power savings."
    Config.DeviceConfig.Role.LOST_AND_FOUND -> "Broadcasts location as message to default channel regularly for to assist with device recovery."
    Config.DeviceConfig.Role.TAK_TRACKER -> "Enables automatic TAK PLI broadcasts and reduces routine broadcasts."
    Config.DeviceConfig.Role.ROUTER_LATE -> "Infrastructure node that always rebroadcasts packets once but only after all other modes. Visible in Nodes list. Not a good choice for rooftop nodes."
    Config.DeviceConfig.Role.CLIENT_BASE -> "Used for rooftop nodes to distribute messages more widely from multiple nearby client mute nodes."
    else -> null
}

fun roleIsDeprecated(role: Config.DeviceConfig.Role): Boolean =
    role == Config.DeviceConfig.Role.ROUTER_CLIENT || role == Config.DeviceConfig.Role.REPEATER

/** DeviceRolePicker.warned: the roles that confirm before they are chosen, and what the dialog says. */
fun roleWarning(role: Config.DeviceConfig.Role): String? = when (role) {
    Config.DeviceConfig.Role.ROUTER, Config.DeviceConfig.Role.ROUTER_LATE ->
        "The Router roles are only for high vantage locations like mountaintops and towers with few nearby nodes, not for use in urban areas. Improper use will hurt your local mesh."
    Config.DeviceConfig.Role.CLIENT_BASE ->
        "Switching to Client Base will clear this node's favorites. Client Base should only favorite other nodes you control. Improper use will hurt your local mesh."
    else -> null
}

fun rebroadcastLabel(mode: Config.DeviceConfig.RebroadcastMode): String = when (mode) {
    Config.DeviceConfig.RebroadcastMode.ALL -> "All"
    Config.DeviceConfig.RebroadcastMode.ALL_SKIP_DECODING -> "All Skip Decoding"
    Config.DeviceConfig.RebroadcastMode.LOCAL_ONLY -> "Local Only"
    Config.DeviceConfig.RebroadcastMode.KNOWN_ONLY -> "Known Only"
    Config.DeviceConfig.RebroadcastMode.NONE -> "None"
    Config.DeviceConfig.RebroadcastMode.CORE_PORTNUMS_ONLY -> "Core Portnums Only"
    Config.DeviceConfig.RebroadcastMode.UNRECOGNIZED -> "Unknown"
}

fun rebroadcastDescription(mode: Config.DeviceConfig.RebroadcastMode): String? = when (mode) {
    Config.DeviceConfig.RebroadcastMode.ALL -> "Rebroadcast any observed message, if it was on our private channel or from another channel with the same lora params."
    Config.DeviceConfig.RebroadcastMode.ALL_SKIP_DECODING -> "Same as behavior as ALL but skips packet decoding and simply rebroadcasts them. Only available in Repeater role. Setting this on any other roles will result in ALL behavior."
    Config.DeviceConfig.RebroadcastMode.LOCAL_ONLY -> "Ignores observed messages from foreign meshes that are open or those which it cannot decrypt. Only rebroadcasts message on the nodes local primary / secondary channels."
    Config.DeviceConfig.RebroadcastMode.KNOWN_ONLY -> "Ignores observed messages from foreign meshes like Local Only, but takes it step further by also ignoring messages from nodes not already in the node's known list."
    Config.DeviceConfig.RebroadcastMode.NONE -> "Only permitted for SENSOR, TRACKER and TAK_TRACKER roles, this will inhibit all rebroadcasts, not unlike CLIENT_MUTE role."
    Config.DeviceConfig.RebroadcastMode.CORE_PORTNUMS_ONLY -> "Only rebroadcasts packets from the core portnums: NodeInfo, Text, Position, Telemetry, and Routing."
    else -> null
}

fun pairingModeLabel(mode: Config.BluetoothConfig.PairingMode): String = when (mode) {
    Config.BluetoothConfig.PairingMode.RANDOM_PIN -> "Random Pin"
    Config.BluetoothConfig.PairingMode.FIXED_PIN -> "Fixed Pin"
    Config.BluetoothConfig.PairingMode.NO_PIN -> "No PIN (Just Works)"
    Config.BluetoothConfig.PairingMode.UNRECOGNIZED -> "Unknown"
}

fun compassOrientationLabel(o: Config.DisplayConfig.CompassOrientation): String = when (o) {
    Config.DisplayConfig.CompassOrientation.DEGREES_0 -> "0°"
    Config.DisplayConfig.CompassOrientation.DEGREES_90 -> "90°"
    Config.DisplayConfig.CompassOrientation.DEGREES_180 -> "180°"
    Config.DisplayConfig.CompassOrientation.DEGREES_270 -> "270°"
    Config.DisplayConfig.CompassOrientation.DEGREES_0_INVERTED -> "0° Inverted"
    Config.DisplayConfig.CompassOrientation.DEGREES_90_INVERTED -> "90° Inverted"
    Config.DisplayConfig.CompassOrientation.DEGREES_180_INVERTED -> "180° Inverted"
    Config.DisplayConfig.CompassOrientation.DEGREES_270_INVERTED -> "270° Inverted"
    Config.DisplayConfig.CompassOrientation.UNRECOGNIZED -> "Unknown"
}

fun displayModeLabel(m: Config.DisplayConfig.DisplayMode): String = when (m) {
    Config.DisplayConfig.DisplayMode.DEFAULT -> "Default 128x64 screen layout"
    Config.DisplayConfig.DisplayMode.TWOCOLOR -> "Optimized for 2 color displays"
    Config.DisplayConfig.DisplayMode.INVERTED -> "Inverted top bar for 2 Color display"
    Config.DisplayConfig.DisplayMode.COLOR -> "TFT Full Color Displays"
    Config.DisplayConfig.DisplayMode.UNRECOGNIZED -> "Unknown"
}

fun displayUnitsLabel(u: Config.DisplayConfig.DisplayUnits): String = when (u) {
    Config.DisplayConfig.DisplayUnits.METRIC -> "Metric"
    Config.DisplayConfig.DisplayUnits.IMPERIAL -> "Imperial"
    Config.DisplayConfig.DisplayUnits.UNRECOGNIZED -> "Unknown"
}

/** OledTypes.allCases: the four the screen always offers (the two newest have no label upstream). */
val OLED_TYPES: List<Config.DisplayConfig.OledType> = listOf(
    Config.DisplayConfig.OledType.OLED_AUTO, Config.DisplayConfig.OledType.OLED_SSD1306,
    Config.DisplayConfig.OledType.OLED_SH1106, Config.DisplayConfig.OledType.OLED_SH1107,
)

fun oledLabel(t: Config.DisplayConfig.OledType): String = when (t) {
    Config.DisplayConfig.OledType.OLED_AUTO -> "Detect Automatically"
    Config.DisplayConfig.OledType.OLED_SSD1306 -> "SSD 1306"
    Config.DisplayConfig.OledType.OLED_SH1106 -> "SH 1106"
    Config.DisplayConfig.OledType.OLED_SH1107 -> "SH 1107"
    Config.DisplayConfig.OledType.OLED_SH1107_128_128 -> "SH 1107 128x128"
    else -> t.name.removePrefix("OLED_").replace('_', ' ')
}

fun addressModeLabel(m: Config.NetworkConfig.AddressMode): String = when (m) {
    Config.NetworkConfig.AddressMode.DHCP -> "DHCP"
    Config.NetworkConfig.AddressMode.STATIC -> "Static"
    Config.NetworkConfig.AddressMode.UNRECOGNIZED -> "Unknown"
}

fun gpsModeLabel(m: Config.PositionConfig.GpsMode): String = when (m) {
    Config.PositionConfig.GpsMode.DISABLED -> "Disabled"
    Config.PositionConfig.GpsMode.ENABLED -> "Enabled"
    Config.PositionConfig.GpsMode.NOT_PRESENT -> "Not Present"
    Config.PositionConfig.GpsMode.UNRECOGNIZED -> "Unknown"
}

/** PositionFlags bits, labels and descriptions, in the overlay's order. */
data class PositionFlagSpec(val bit: Int, val label: String, val description: String? = null, val requires: Int = 0)

val POSITION_FLAGS: List<PositionFlagSpec> = listOf(
    PositionFlagSpec(1, "Altitude", "Include an altitude value in position reports, when one is available."),
    PositionFlagSpec(2, "Altitude is Mean Sea Level", requires = 1),
    PositionFlagSpec(4, "Altitude Geoidal Separation", requires = 1),
    PositionFlagSpec(32, "Number of satellites"),
    PositionFlagSpec(64, "Sequence number"),
    PositionFlagSpec(128, "Timestamp"),
    PositionFlagSpec(256, "Vehicle heading"),
    PositionFlagSpec(512, "Vehicle speed"),
    PositionFlagSpec(8, "DOP", "Include the dilution of precision value. PDOP is used by default."),
    PositionFlagSpec(16, "HDOP / VDOP", "If DOP is set, send separate HDOP and VDOP values instead of PDOP.", requires = 8),
)

fun signaturePolicyLabel(p: Config.SecurityConfig.PacketSignaturePolicy): String = when (p) {
    Config.SecurityConfig.PacketSignaturePolicy.PACKET_SIGNATURE_POLICY_COMPATIBLE -> "Compatible - Accept Unsigned"
    Config.SecurityConfig.PacketSignaturePolicy.PACKET_SIGNATURE_POLICY_BALANCED -> "Balanced - Prefer Authenticated"
    Config.SecurityConfig.PacketSignaturePolicy.PACKET_SIGNATURE_POLICY_STRICT -> "Strict - Require Authentication"
    Config.SecurityConfig.PacketSignaturePolicy.UNRECOGNIZED -> "Unknown"
}

fun signaturePolicyDescription(p: Config.SecurityConfig.PacketSignaturePolicy): String = when (p) {
    Config.SecurityConfig.PacketSignaturePolicy.PACKET_SIGNATURE_POLICY_COMPATIBLE -> "Accept unsigned traffic for maximum compatibility. A signature that can be checked and is wrong still drops the packet."
    Config.SecurityConfig.PacketSignaturePolicy.PACKET_SIGNATURE_POLICY_BALANCED -> "Prefer authenticated packets, but still accept unsigned traffic from nodes not known to sign."
    Config.SecurityConfig.PacketSignaturePolicy.PACKET_SIGNATURE_POLICY_STRICT -> "Accept only packets with a verified signature or successful PKI decryption. Packets from older nodes may be ignored."
    Config.SecurityConfig.PacketSignaturePolicy.UNRECOGNIZED -> ""
}

// ---------------------------------------------------------------------------------------
// Keys (SecurityKey, generatePrivateKey, generatePublicKeyDisplay)

object SecurityKey {
    const val BYTES = 32
    const val SLOTS = 3

    /** Blank means unset; anything else must decode to exactly 32 bytes. */
    fun isValid(text: String): Boolean =
        text.isEmpty() || runCatching { android.util.Base64.decode(text, android.util.Base64.DEFAULT).size == BYTES }.getOrDefault(false)

    fun data(text: String): ByteArray =
        runCatching { android.util.Base64.decode(text, android.util.Base64.DEFAULT) }.getOrNull()?.takeIf { it.size == BYTES } ?: ByteArray(0)

    fun text(data: ByteArray): String =
        if (data.isEmpty()) "" else android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)

    /** A fresh Curve25519 private key, clamped as the specification requires. */
    fun generatePrivateKey(): ByteArray {
        val k = ByteArray(BYTES)
        SecureRandom().nextBytes(k)
        return X25519.clamp(k)
    }

    /** The public key the radio would derive from this private key, or null when it is not 32 bytes. */
    fun publicKeyFor(privateKey: ByteArray): ByteArray? =
        if (privateKey.size == BYTES) X25519.publicKey(privateKey) else null
}

/**
 * RFC 7748 X25519 over BigInteger. One scalar multiplication per key regeneration, so
 * speed does not matter and no crypto library is needed; the firmware uses the same curve.
 */
object X25519 {
    private val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)

    fun clamp(k: ByteArray): ByteArray {
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
        return k
    }

    fun publicKey(privateKey: ByteArray): ByteArray {
        val base = ByteArray(32).also { it[0] = 9 }
        return scalarMult(clamp(privateKey.copyOf()), base)
    }

    private fun decodeLittleEndian(b: ByteArray): BigInteger {
        val reversed = ByteArray(b.size + 1)
        for (i in b.indices) reversed[b.size - i] = b[i]
        return BigInteger(reversed)
    }

    private fun encodeLittleEndian(v: BigInteger): ByteArray {
        val out = ByteArray(32)
        val bytes = v.toByteArray()
        var i = 0
        var j = bytes.size - 1
        while (i < 32 && j >= 0) { out[i] = bytes[j]; i++; j-- }
        return out
    }

    fun scalarMult(k: ByteArray, u: ByteArray): ByteArray {
        val scalar = decodeLittleEndian(k)
        val uMasked = u.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() }
        val x1 = decodeLittleEndian(uMasked).mod(P)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = if (scalar.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
            swap = kt
            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            val daPlusCb = da.add(cb).mod(P)
            val daMinusCb = da.subtract(cb).mod(P)
            x3 = daPlusCb.multiply(daPlusCb).mod(P)
            z3 = x1.multiply(daMinusCb.multiply(daMinusCb).mod(P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P)
        }
        if (swap == 1) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
        val result = x2.multiply(z2.modPow(P.subtract(BigInteger.TWO), P)).mod(P)
        return encodeLittleEndian(result)
    }
}

// ---------------------------------------------------------------------------------------
// IPv4 (IPv4Address)

object IPv4 {
    private val dotted = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")

    /** A required field: present and well formed. */
    fun isRequiredValid(text: String): Boolean = dotted.matches(text.trim())

    /** An optional field: blank, or well formed. */
    fun isValid(text: String): Boolean = text.isBlank() || dotted.matches(text.trim())
}
