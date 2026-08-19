package com.suteny0r.mangledbabyducks.radio

import android.util.Log
import com.suteny0r.mangledbabyducks.db.ChannelEntity
import com.suteny0r.mangledbabyducks.db.MeshDatabase
import com.suteny0r.mangledbabyducks.db.MessageEntity
import com.suteny0r.mangledbabyducks.db.MyInfoEntity
import com.suteny0r.mangledbabyducks.db.NodeEntity
import com.suteny0r.mangledbabyducks.db.PositionEntity
import com.suteny0r.mangledbabyducks.db.TelemetryEntity
import com.suteny0r.mangledbabyducks.db.UserEntity
import org.meshtastic.proto.ChannelProtos
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.Portnums
import org.meshtastic.proto.TelemetryProtos

/**
 * Packet-to-database ingest. Port of the business logic in
 * Meshtastic/Helpers/MeshPackets.swift and Meshtastic/Persistence/UpdateSwiftData.swift.
 */
class PacketIngest(private val db: MeshDatabase) {

    /** Called when this radio's MyNodeInfo arrives; returns the local node num. */
    suspend fun myInfo(myInfo: MeshProtos.MyNodeInfo, bleName: String?): Long {
        val num = myInfo.myNodeNum.uint()
        var existing = db.myInfoDao().myInfoOnce()
        if (existing != null && existing.myNodeNum != num) {
            // Different radio than the one this DB belongs to: defensive reset,
            // mirroring handleMyInfo's cross-device store guard. The my_info row
            // must go too, or the single-row LIMIT 1 queries keep serving the
            // old radio's identity.
            db.nodeDao().clear()
            db.myInfoDao().clear()
            existing = null
        }
        db.channelDao().clear()
        db.myInfoDao().upsert(
            MyInfoEntity(
                myNodeNum = num,
                rebootCount = myInfo.rebootCount,
                minAppVersion = myInfo.minAppVersion,
                firmwareVersion = existing?.firmwareVersion,
                bleName = bleName,
            )
        )
        return num
    }

    suspend fun deviceMetadata(metadata: MeshProtos.DeviceMetadata) {
        val existing = db.myInfoDao().myInfoOnce() ?: return
        db.myInfoDao().upsert(existing.copy(firmwareVersion = metadata.firmwareVersion))
    }

    suspend fun nodeInfo(info: MeshProtos.NodeInfo) {
        val num = info.num.uint()
        if (num == 0L) return
        val now = System.currentTimeMillis()
        val existing = db.nodeDao().get(num)
        db.nodeDao().upsert(
            NodeEntity(
                num = num,
                channel = info.channel,
                snr = info.snr,
                rssi = existing?.rssi ?: 0,
                firstHeard = existing?.firstHeard ?: now,
                lastHeard = if (info.lastHeard != 0) info.lastHeard.uint() * 1000 else existing?.lastHeard,
                hopsAway = if (info.hasHopsAway()) info.hopsAway else existing?.hopsAway ?: -1,
                viaMqtt = info.viaMqtt,
                favorite = info.isFavorite || (existing?.favorite ?: false),
                ignored = existing?.ignored ?: false,
            )
        )
        if (info.hasUser()) upsertUser(num, info.user)
        if (info.hasPosition()) position(num, info.position, rxTime = info.lastHeard)
        if (info.hasDeviceMetrics()) {
            deviceMetrics(num, info.deviceMetrics, timeSec = info.lastHeard)
        }
    }

    private suspend fun upsertUser(num: Long, user: MeshProtos.User) {
        val existing = db.userDao().get(num)
        // First-wins public key policy (UserEntity.applyInboundPublicKey): a differing
        // inbound key is refused and flagged so the UI can warn.
        val inboundKey = user.publicKey.toByteArray().takeIf { it.isNotEmpty() }
        val storedKey = existing?.publicKey
        val (key, keyMatch) = when {
            inboundKey == null -> storedKey to (existing?.keyMatch ?: true)
            storedKey == null || storedKey.isEmpty() -> inboundKey to true
            storedKey.contentEquals(inboundKey) -> storedKey to true
            else -> storedKey to false
        }
        db.userDao().upsert(
            UserEntity(
                num = num,
                userId = user.id,
                longName = user.longName.ifEmpty { existing?.longName },
                shortName = user.shortName.ifEmpty { existing?.shortName },
                hwModel = user.hwModel.name,
                role = user.roleValue,
                isLicensed = user.isLicensed,
                publicKey = key,
                pkiEncrypted = key != null && key.isNotEmpty(),
                keyMatch = keyMatch,
                lastMessage = existing?.lastMessage,
            )
        )
    }

    suspend fun channel(channel: ChannelProtos.Channel) {
        db.channelDao().upsert(
            ChannelEntity(
                index = channel.index,
                name = channel.settings.name,
                role = channel.roleValue,
                psk = channel.settings.psk.toByteArray(),
                positionPrecision = channel.settings.moduleSettings.positionPrecision,
            )
        )
    }

    /**
     * Mirror of MeshPackets.updateAnyPacketFrom / firmware NodeDB::updateFrom: every
     * inbound MeshPacket freshens the sender's link stats before port dispatch.
     */
    suspend fun updateFromAnyPacket(packet: MeshProtos.MeshPacket, myNum: Long) {
        val from = packet.from.uint()
        if (from == 0L || from == myNum) return
        val isImplicitAck =
            packet.decoded.portnum == Portnums.PortNum.ROUTING_APP && packet.rxTime == 0
        val existing = db.nodeDao().get(from)
        val lastHeard = when {
            isImplicitAck -> existing?.lastHeard
            packet.rxTime != 0 -> packet.rxTime.uint() * 1000
            else -> System.currentTimeMillis()
        }
        val hopsAway = if (packet.hopStart != 0 && packet.hopLimit <= packet.hopStart) {
            packet.hopStart - packet.hopLimit
        } else {
            existing?.hopsAway ?: -1
        }
        db.nodeDao().upsert(
            (existing ?: NodeEntity(num = from, firstHeard = System.currentTimeMillis())).copy(
                snr = if (packet.rxSnr != 0f) packet.rxSnr else existing?.snr ?: 0f,
                rssi = if (packet.rxRssi != 0) packet.rxRssi else existing?.rssi ?: 0,
                viaMqtt = packet.viaMqtt,
                lastHeard = lastHeard,
                hopsAway = hopsAway,
            )
        )
    }

    /** TEXT_MESSAGE_APP inbound. Returns the stored message, or null when deduped/skipped. */
    suspend fun textMessage(packet: MeshProtos.MeshPacket, myNum: Long): MessageEntity? {
        val text = packet.decoded.payload.toStringUtf8()
        if (text.isEmpty()) return null
        val messageId = packet.id.uint()
        val from = packet.from.uint()
        val to = packet.to.uint()
        val isBroadcast = to == MeshProtocol.BROADCAST_NUM
        val isFromSelf = from == myNum
        val message = MessageEntity(
            messageId = messageId,
            fromNum = from,
            toNum = if (isBroadcast) null else to,
            channel = packet.channel,
            portNum = packet.decoded.portnumValue,
            payload = text,
            timestamp = if (packet.rxTime != 0) packet.rxTime.uint() * 1000 else System.currentTimeMillis(),
            read = isFromSelf,
            isEmoji = packet.decoded.emoji != 0,
            replyId = packet.decoded.replyId.uint(),
            snr = packet.rxSnr,
            rssi = packet.rxRssi,
        )
        // Dedupe on messageId: the radio echoes our own TX back and a second insert
        // would reset read/ack state and fire a phantom notification.
        val inserted = db.messageDao().insertIgnore(message)
        if (inserted == -1L) return null
        if (!isBroadcast && !isFromSelf) {
            db.userDao().touchLastMessage(from, message.timestamp)
        }
        return if (isFromSelf) null else message
    }

    /** ROUTING_APP: correlate an ack/nak back to the original message via requestId. */
    suspend fun routing(packet: MeshProtos.MeshPacket, myNum: Long) {
        val routing = runCatching {
            MeshProtos.Routing.parseFrom(packet.decoded.payload)
        }.getOrNull() ?: return
        val requestId = packet.decoded.requestId.uint()
        if (requestId == 0L) return
        val errorReason = routing.errorReason.number
        val realAck = packet.to.uint() != packet.from.uint()
        db.messageDao().applyAck(
            messageId = requestId,
            receivedAck = errorReason == 0,
            realAck = realAck && errorReason == 0,
            ackError = errorReason,
            ackSnr = packet.rxSnr,
            ackTimestamp = if (packet.rxTime != 0) packet.rxTime.uint() * 1000 else System.currentTimeMillis(),
        )
    }

    /** NODEINFO_APP: a User broadcast from another node. */
    suspend fun userPacket(packet: MeshProtos.MeshPacket) {
        val user = runCatching {
            MeshProtos.User.parseFrom(packet.decoded.payload)
        }.getOrNull() ?: return
        upsertUser(packet.from.uint(), user)
    }

    /** POSITION_APP. */
    suspend fun positionPacket(packet: MeshProtos.MeshPacket) {
        val pos = runCatching {
            MeshProtos.Position.parseFrom(packet.decoded.payload)
        }.getOrNull() ?: return
        position(packet.from.uint(), pos, packet.rxTime)
    }

    private suspend fun position(nodeNum: Long, pos: MeshProtos.Position, rxTime: Int) {
        // Reject null island, matching hasValidCoordinates.
        if (pos.latitudeI == 0 && pos.longitudeI == 0) return
        val timeSec = when {
            pos.timestamp != 0 -> pos.timestamp.uint()
            pos.time != 0 -> pos.time.uint()
            rxTime != 0 -> rxTime.uint()
            else -> System.currentTimeMillis() / 1000
        }
        db.positionDao().clearLatest(nodeNum)
        db.positionDao().insert(
            PositionEntity(
                nodeNum = nodeNum,
                latitudeI = pos.latitudeI,
                longitudeI = pos.longitudeI,
                altitude = pos.altitude,
                satsInView = pos.satsInView,
                speed = pos.groundSpeed,
                heading = if (pos.groundTrack <= 360) pos.groundTrack else 0,
                seqNo = pos.seqNumber,
                precisionBits = minOf(pos.precisionBits, 32),
                time = timeSec * 1000,
                latest = true,
            )
        )
    }

    /** TELEMETRY_APP. */
    suspend fun telemetryPacket(packet: MeshProtos.MeshPacket) {
        val telemetry = runCatching {
            TelemetryProtos.Telemetry.parseFrom(packet.decoded.payload)
        }.getOrNull() ?: return
        val nodeNum = packet.from.uint()
        val timeSec = if (telemetry.time != 0) telemetry.time.uint()
        else if (packet.rxTime != 0) packet.rxTime.uint()
        else System.currentTimeMillis() / 1000
        when (telemetry.variantCase) {
            TelemetryProtos.Telemetry.VariantCase.DEVICE_METRICS ->
                deviceMetrics(nodeNum, telemetry.deviceMetrics, timeSec.toInt())
            TelemetryProtos.Telemetry.VariantCase.ENVIRONMENT_METRICS -> {
                val m = telemetry.environmentMetrics
                db.telemetryDao().insert(
                    TelemetryEntity(
                        nodeNum = nodeNum,
                        metricsType = 1,
                        time = timeSec * 1000,
                        temperature = if (m.hasTemperature()) m.temperature else null,
                        relativeHumidity = if (m.hasRelativeHumidity()) m.relativeHumidity else null,
                        barometricPressure = if (m.hasBarometricPressure()) m.barometricPressure else null,
                        iaq = if (m.hasIaq()) m.iaq else null,
                    )
                )
            }
            else -> Log.d(TAG, "Unhandled telemetry variant ${telemetry.variantCase}")
        }
    }

    private suspend fun deviceMetrics(nodeNum: Long, m: TelemetryProtos.DeviceMetrics, timeSec: Int) {
        val time = if (timeSec != 0) timeSec.uint() * 1000 else System.currentTimeMillis()
        db.telemetryDao().insert(
            TelemetryEntity(
                nodeNum = nodeNum,
                metricsType = 0,
                time = time,
                batteryLevel = if (m.hasBatteryLevel()) m.batteryLevel else null,
                voltage = if (m.hasVoltage()) m.voltage else null,
                channelUtilization = if (m.hasChannelUtilization()) m.channelUtilization else null,
                airUtilTx = if (m.hasAirUtilTx()) m.airUtilTx else null,
                uptimeSeconds = if (m.hasUptimeSeconds()) m.uptimeSeconds else null,
            )
        )
    }

    companion object {
        private const val TAG = "PacketIngest"
    }
}
