package com.suteny0r.meshtastic.db

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
)

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val num: Long,
    val userId: String? = null,
    val longName: String? = null,
    val shortName: String? = null,
    val hwModel: String? = null,
    val role: Int = 0,
    val isLicensed: Boolean = false,
    val publicKey: ByteArray? = null,
    val pkiEncrypted: Boolean = false,
    /** First-wins key policy: a differing inbound key sets this false. */
    val keyMatch: Boolean = true,
    val lastMessage: Long? = null,
)

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
    /** 0=device, 1=environment (matches iOS metricsType discriminator). */
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
)

/** Node joined with its user identity and latest position — the list-row shape. */
data class NodeWithUser(
    @Embedded val node: NodeEntity,
    @Relation(parentColumn = "num", entityColumn = "num")
    val user: UserEntity?,
)
