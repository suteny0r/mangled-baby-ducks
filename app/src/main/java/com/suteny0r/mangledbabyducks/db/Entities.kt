package com.suteny0r.mangledbabyducks.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * Room schema ported from the SwiftData models in Meshtastic-Apple/Meshtastic/Model.
 * Node num is the real key throughout (unsigned 32-bit stored as Long).
 */

@Entity(tableName = "nodes")
data class NodeEntity(
    @PrimaryKey val num: Long,
    val channel: Int = 0,
    val snr: Float = 0f,
    val rssi: Int = 0,
    val firstHeard: Long? = null,
    val lastHeard: Long? = null,
    val hopsAway: Int = -1,
    val viaMqtt: Boolean = false,
    val favorite: Boolean = false,
    val ignored: Boolean = false,
    /** Latched true once any packet from this node arrived xeddsaSigned (never cleared). */
    val hasXeddsaSigned: Boolean = false,
    /** Last NODE_STATUS_APP message text, if any. */
    val nodeStatus: String? = null,
    val firmwareVersion: String? = null,
)

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val num: Long,
    val userId: String? = null,
    val longName: String? = null,
    val shortName: String? = null,
    val hwModel: String? = null,
    val hwModelId: Int = 0,
    val hwDisplayName: String? = null,
    val role: Int = 0,
    val isLicensed: Boolean = false,
    val publicKey: ByteArray? = null,
    val pkiEncrypted: Boolean = false,
    /** First-wins key policy: a differing inbound key sets this false. */
    val keyMatch: Boolean = true,
    /** A differing inbound key that was refused (possible key-substitution attempt). */
    val newPublicKey: ByteArray? = null,
    val lastMessage: Long? = null,
    /** Local mute flag (NodeAlertsButton); not sent over the wire. */
    val mute: Boolean = false,
    /** No DMable channel found (all channels muted/disabled); shown by ShareContactQR gate. */
    val unmessagable: Boolean = false,
)

/** "!" + hex(8) node number, for display (iOS numString). */
fun nodeNumString(num: Long): String = "!%08x".format(num)

@Entity(
    tableName = "messages",
    indices = [Index("channel"), Index("fromNum"), Index("toNum"), Index("timestamp")],
)
data class MessageEntity(
    /** Wire packet id; unique — the radio echoes our own sends back. */
    @PrimaryKey val messageId: Long,
    val fromNum: Long,
    /** Null for channel/broadcast messages, set for DMs (the discriminator used everywhere). */
    val toNum: Long?,
    val channel: Int,
    val portNum: Int,
    val payload: String?,
    val timestamp: Long,
    val read: Boolean = false,
    val isEmoji: Boolean = false,
    val replyId: Long = 0,
    val receivedAck: Boolean = false,
    val realAck: Boolean = false,
    val ackError: Int = 0,
    val ackTimestamp: Long = 0,
    val ackSnr: Float = 0f,
    val snr: Float = 0f,
    val rssi: Int = 0,
)

@Entity(tableName = "channels")
data class ChannelEntity(
    /** The wire channel index. */
    @PrimaryKey val index: Int,
    val name: String? = null,
    val role: Int = 0,
    val psk: ByteArray? = null,
    val positionPrecision: Int = 32,
    val mute: Boolean = false,
)

@Entity(tableName = "my_info")
data class MyInfoEntity(
    @PrimaryKey val myNodeNum: Long,
    val rebootCount: Int = 0,
    val minAppVersion: Int = 0,
    val firmwareVersion: String? = null,
    val bleName: String? = null,
)

@Entity(
    tableName = "positions",
    indices = [Index(value = ["nodeNum", "time"])],
)
data class PositionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nodeNum: Long,
    val latitudeI: Int,
    val longitudeI: Int,
    val altitude: Int = 0,
    val satsInView: Int = 0,
    val speed: Int = 0,
    val heading: Int = 0,
    val seqNo: Int = 0,
    val precisionBits: Int = 32,
    val time: Long,
    val latest: Boolean = true,
) {
    val latitude: Double get() = latitudeI / 1e7
    val longitude: Double get() = longitudeI / 1e7
}

@Entity(
    tableName = "telemetry",
    indices = [Index(value = ["nodeNum", "metricsType", "time"])],
)
data class TelemetryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nodeNum: Long,
    /** 0=device, 1=environment, 2=power, 3=air-quality, 4=local-stats (iOS metricsType). */
    val metricsType: Int,
    val time: Long,
    val batteryLevel: Int? = null,
    val voltage: Float? = null,
    val channelUtilization: Float? = null,
    val airUtilTx: Float? = null,
    val uptimeSeconds: Int? = null,
    val temperature: Float? = null,
    val relativeHumidity: Float? = null,
    val barometricPressure: Float? = null,
    val iaq: Int? = null,
    // EnvironmentMetrics extras
    val current: Float? = null,
    val weight: Float? = null,
    val distance: Float? = null,
    val windSpeed: Float? = null,
    val windGust: Float? = null,
    val windLull: Float? = null,
    val windDirection: Int? = null,
    val irLux: Float? = null,
    val lux: Float? = null,
    val whiteLux: Float? = null,
    val uvLux: Float? = null,
    val radiation: Float? = null,
    val rainfall1H: Float? = null,
    val rainfall24H: Float? = null,
    val soilTemperature: Float? = null,
    val soilMoisture: Int? = null,
    val gasResistance: Float? = null,
    // PowerMetrics
    val powerCh1Voltage: Float? = null,
    val powerCh1Current: Float? = null,
    val powerCh2Voltage: Float? = null,
    val powerCh2Current: Float? = null,
    val powerCh3Voltage: Float? = null,
    val powerCh3Current: Float? = null,
    // AirQualityMetrics (particulate matter, µg/m³)
    val pm10Standard: Int? = null,
    val pm25Standard: Int? = null,
    val pm100Standard: Int? = null,
    val pm10Environmental: Int? = null,
    val pm25Environmental: Int? = null,
    val pm100Environmental: Int? = null,
    // LocalStats (plain proto scalars, always present)
    val noiseFloor: Int? = null,
    val numPacketsTx: Int = 0,
    val numPacketsRx: Int = 0,
    val numPacketsRxBad: Int = 0,
    val numRxDupe: Int = 0,
    val numTxRelay: Int = 0,
    val numTxRelayCanceled: Int = 0,
    val numOnlineNodes: Int = 0,
    val numTotalNodes: Int = 0,
    val snr: Float? = null,
    val rssi: Int? = null,
)

/**
 * One radio config section, stored as raw proto bytes keyed by section name
 * ("lora", "device", … / "module.mqtt", …). Screens parse the proto on read, so
 * the schema never chases firmware fields.
 */
@Entity(tableName = "configs")
data class ConfigEntity(
    @PrimaryKey val type: String,
    val bytes: ByteArray,
    val updated: Long,
)

/** One traceroute run and its result (port of TraceRouteEntity, flattened). */
@Entity(tableName = "traceroutes", indices = [Index("toNum")])
data class TracerouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val toNum: Long,
    val time: Long,
    /** Whether a reply arrived; the route fields are empty until then. */
    val response: Boolean = false,
    /** Node nums visited towards the destination, comma-separated. */
    val routeTowards: String = "",
    /** SNR per hop towards (dB, scaled by 4 on the wire), comma-separated. */
    val snrTowards: String = "",
    val routeBack: String = "",
    val snrBack: String = "",
)

/** A mesh waypoint (port of WaypointEntity, core fields). */
@Entity(tableName = "waypoints")
data class WaypointEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val description: String,
    val icon: Int,
    val latitudeI: Int,
    val longitudeI: Int,
    val expire: Long,
    val lockedTo: Long,
    val createdBy: Long,
    val updated: Long,
) {
    val latitude: Double get() = latitudeI / 1e7
    val longitude: Double get() = longitudeI / 1e7
}

/** A node's current position, for resolving hops on a trace route to map coordinates. */
data class RoutePoint(
    val nodeNum: Long,
    val latitudeI: Int,
    val longitudeI: Int,
    val shortName: String?,
    val longName: String?,
) {
    val latitude: Double get() = latitudeI / 1e7
    val longitude: Double get() = longitudeI / 1e7
}

/** Node joined with its user identity and latest position — the list-row shape. */
data class NodeWithUser(
    @Embedded val node: NodeEntity,
    @Relation(parentColumn = "num", entityColumn = "num")
    val user: UserEntity?,
)

/** Latest position joined with user names — the map-marker shape. */
data class MapNode(
    val nodeNum: Long,
    val latitudeI: Int,
    val longitudeI: Int,
    val time: Long,
    val shortName: String?,
    val longName: String?,
) {
    val latitude: Double get() = latitudeI / 1e7
    val longitude: Double get() = longitudeI / 1e7
}
