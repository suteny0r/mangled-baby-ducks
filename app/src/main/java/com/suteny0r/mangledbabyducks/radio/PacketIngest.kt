package com.suteny0r.mangledbabyducks.radio

import android.util.Log
import com.suteny0r.mangledbabyducks.db.ChannelEntity
import com.suteny0r.mangledbabyducks.db.ConfigEntity
import com.suteny0r.mangledbabyducks.db.MeshDatabase
import com.suteny0r.mangledbabyducks.db.MessageEntity
import com.suteny0r.mangledbabyducks.db.MyInfoEntity
import com.suteny0r.mangledbabyducks.db.NodeBackupManager
import com.suteny0r.mangledbabyducks.db.NodeEntity
import com.suteny0r.mangledbabyducks.db.PositionEntity
import com.suteny0r.mangledbabyducks.db.TelemetryEntity
import com.suteny0r.mangledbabyducks.db.UserEntity
import com.suteny0r.mangledbabyducks.db.WaypointEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.meshtastic.proto.AdminProtos
import org.meshtastic.proto.ChannelProtos
import org.meshtastic.proto.ConfigProtos
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.ModuleConfigProtos
import org.meshtastic.proto.Portnums
import org.meshtastic.proto.TelemetryProtos

/**
 * Packet-to-database ingest. Port of the business logic in
 * Meshtastic/Helpers/MeshPackets.swift and Meshtastic/Persistence/UpdateSwiftData.swift.
 */
/** An rx_time older than this is a replay, not clock skew. */
private const val HISTORICAL_MS = 10 * 60 * 1000L

class PacketIngest(private val db: MeshDatabase, private val backups: NodeBackupManager? = null) {

    /** Called when this radio's MyNodeInfo arrives; returns the local node num. */
    suspend fun myInfo(myInfo: MeshProtos.MyNodeInfo, bleName: String?, radioAddress: String?, deviceId: String? = null): Long {
        val num = myInfo.myNodeNum.uint()
        // Clear a stray node-number backup on connect: this radio's snapshot moves onto
        // its device id, so the 2.8 renumbering cannot orphan it.
        backups?.adoptLegacyBackups(deviceId, num, radioAddress)
        var existing = db.myInfoDao().myInfoOnce()
        if (existing != null && existing.myNodeNum != num) {
            // handleMyInfo's defensiveResetIfForeignDatabase: this connect landed on another
            // radio's store without going through the switch flow (auto-reconnect to a
            // never-seen radio, interrupted switch). Back the previous radio up exactly as
            // the switch would have, then clear the whole store before anything for the
            // new radio is ingested; nodes carry no owner column, so a merge is a bleed.
            Log.w(TAG, "Connected to node $num but the store belongs to ${existing.myNodeNum}; backing up and resetting")
            val previous = existing.myNodeNum
            // No device id in hand for the previous radio; performBackup keeps whatever key
            // its last backup used.
            backups?.createBackup(previous, null, db.userDao().get(previous)?.longName ?: existing.bleName)
            withContext(Dispatchers.IO) { db.clearAllTables() }
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
                radioAddress = radioAddress ?: existing?.radioAddress,
            )
        )
        return num
    }

    /** Store one LocalConfig section as raw proto bytes, keyed by section name. */
    suspend fun config(config: ConfigProtos.Config) {
        val type = "config." + config.payloadVariantCase.name.lowercase()
        db.configDao().upsert(ConfigEntity(type, config.toByteArray(), System.currentTimeMillis()))
    }

    suspend fun moduleConfig(moduleConfig: ModuleConfigProtos.ModuleConfig) {
        val type = "module." + moduleConfig.payloadVariantCase.name.lowercase()
        db.configDao().upsert(ConfigEntity(type, moduleConfig.toByteArray(), System.currentTimeMillis()))
    }

    /**
     * Inbound ADMIN_APP replies to a remote admin request (PacketIngest.adminResponse is
     * dispatched from RadioManager for every ADMIN_APP packet whose sender is not our own
     * node). A remote node's DeviceMetadata lands on that node's row (its firmware
     * version is what the node detail shows); ModuleConfig rows land on the config
     * table, keyed the same way the handshake does.
     */
    suspend fun adminResponse(packet: MeshProtos.MeshPacket) {
        val myNum = db.myInfoDao().myInfoOnce()?.myNodeNum
        val from = packet.from.uint()
        if (myNum != null && from == myNum) return
        try {
            val admin = AdminProtos.AdminMessage.parseFrom(packet.decoded.payload)
            when (admin.payloadVariantCase) {
                AdminProtos.AdminMessage.PayloadVariantCase.GET_DEVICE_METADATA_RESPONSE -> {
                    val version = admin.getGetDeviceMetadataResponse().firmwareVersion.takeIf { it.isNotBlank() }
                    db.nodeDao().get(from)?.let { db.nodeDao().upsert(it.copy(firmwareVersion = version)) }
                }
                AdminProtos.AdminMessage.PayloadVariantCase.GET_MODULE_CONFIG_RESPONSE -> {
                    moduleConfig(admin.getGetModuleConfigResponse())
                }
                else -> Log.d(TAG, "Admin response ignored: ${admin.payloadVariantCase}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse ADMIN_APP response", e)
        }
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
                hasXeddsaSigned = (existing?.hasXeddsaSigned ?: false) || info.hasXeddsaSigned,
                nodeStatus = existing?.nodeStatus,
                firmwareVersion = existing?.firmwareVersion,
            )
        )
        if (info.hasUser()) upsertUser(num, info.user)
        if (info.hasPosition()) position(num, info.position, rxTime = info.lastHeard)
        if (info.hasDeviceMetrics()) {
            deviceMetrics(num, info.deviceMetrics, timeSec = info.lastHeard)
        }
    }

    private suspend fun upsertUser(num: Long, user: MeshProtos.User) {
        // A user must never exist without its node (see NodeDao.orphanUserNums): a bare
        // User broadcast on NODEINFO_APP is upsertNodeInfoPacket's "Mesh broadcast sends a
        // User protobuf" branch, which on iOS runs against a node row it created first.
        if (db.nodeDao().get(num) == null) db.nodeDao().upsert(NodeEntity(num = num))
        val existing = db.userDao().get(num)
        // First-wins public key policy (UserEntity.applyInboundPublicKey): a differing
        // inbound key is refused, flagged so the UI can warn, and recorded for review.
        val inboundKey = user.publicKey.toByteArray().takeIf { it.isNotEmpty() }
        val storedKey = existing?.publicKey
        val (key, keyMatch, newKey) = when {
            inboundKey == null -> Triple(storedKey, existing?.keyMatch ?: true, existing?.newPublicKey)
            storedKey == null || storedKey.isEmpty() -> Triple(inboundKey, true, existing?.newPublicKey)
            storedKey.contentEquals(inboundKey) -> Triple(storedKey, true, existing?.newPublicKey)
            else -> Triple(storedKey, false, inboundKey)
        }
        // Explicit wire flag wins; otherwise derive from role (unmessagableFromUser).
        val role = user.roleValue
        val unmessagable =
            if (user.hasIsUnmessagable()) user.isUnmessagable else role in UNMESSAGABLE_ROLES
        db.userDao().upsert(
            UserEntity(
                num = num,
                userId = user.id,
                longName = user.longName.ifEmpty { existing?.longName },
                shortName = user.shortName.ifEmpty { existing?.shortName },
                hwModel = user.hwModel.name.uppercase(),
                hwModelId = user.hwModelValue,
                role = role,
                isLicensed = user.isLicensed,
                publicKey = key,
                pkiEncrypted = key != null && key.isNotEmpty(),
                keyMatch = keyMatch,
                newPublicKey = newKey,
                lastMessage = existing?.lastMessage,
                mute = existing?.mute ?: false,
                unmessagable = unmessagable,
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
                uplinkEnabled = channel.settings.uplinkEnabled,
                downlinkEnabled = channel.settings.downlinkEnabled,
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
                hasXeddsaSigned = (existing?.hasXeddsaSigned ?: false) || packet.xeddsaSigned,
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
        // MeshPackets.swift:1437: a message counts as encrypted only when the sender is a
        // PKI user we already hold a key for. (The Swift line reads
        // `fromUser?.pkiEncrypted ?? false && packet.pkiEncrypted`, where && binds tighter
        // than ??, so its packet term is dead; the packet is tested here as intended.)
        val pkiEncrypted = packet.pkiEncrypted && db.userDao().get(from)?.pkiEncrypted == true
        val message = MessageEntity(
            messageId = messageId,
            fromNum = from,
            toNum = if (isBroadcast) null else to,
            channel = packet.channel,
            portNum = packet.decoded.portnumValue,
            payload = text,
            timestamp = arrivalTime(packet.rxTime),
            read = isFromSelf,
            isEmoji = packet.decoded.emoji != 0,
            replyId = packet.decoded.replyId.uint(),
            snr = packet.rxSnr,
            rssi = packet.rxRssi,
            pkiEncrypted = pkiEncrypted,
            // Firmware only signs broadcasts; gate on our own broadcast test as well so a
            // stray or spoofed flag can never put the verified shield on a DM.
            xeddsaSigned = packet.xeddsaSigned && isBroadcast,
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

    /** An ack or nak correlated to one of our sends; errorReason 0 is a clean ack. */
    data class AckResult(val messageId: Long, val errorReason: Int)

    /**
     * ROUTING_APP: correlate an ack/nak back to the original message via requestId.
     * Returns what was applied so the caller can react to specific naks.
     */
    suspend fun routing(packet: MeshProtos.MeshPacket, myNum: Long): AckResult? {
        val routing = runCatching {
            MeshProtos.Routing.parseFrom(packet.decoded.payload)
        }.getOrNull() ?: return null
        val requestId = packet.decoded.requestId.uint()
        if (requestId == 0L) return null
        val errorReason = routing.errorReason.number
        // A routing packet the radio addresses to itself is its own implicit ack, not the
        // far end's; iOS marks realACK on any other DM reply, whatever the error, and lets
        // the receivedACK test in deliveryStatus decide whether it is shown.
        val realAck = packet.to.uint() != packet.from.uint() &&
            db.messageDao().destinationOf(requestId) != null
        db.messageDao().applyAck(
            messageId = requestId,
            receivedAck = errorReason == 0,
            realAck = realAck,
            ackError = errorReason,
            ackSnr = packet.rxSnr,
            ackTimestamp = if (packet.rxTime != 0) packet.rxTime.uint() * 1000 else System.currentTimeMillis(),
            relayNode = packet.relayNode.uint(),
        )
        return AckResult(requestId, errorReason)
    }

    /** NODEINFO_APP: a User broadcast from another node. */
    suspend fun userPacket(packet: MeshProtos.MeshPacket) {
        val user = runCatching {
            MeshProtos.User.parseFrom(packet.decoded.payload)
        }.getOrNull() ?: return
        upsertUser(packet.from.uint(), user)
    }

    /**
     * NODE_STATUS_APP: the node's free-form status text. Port of
     * UpdateSwiftData.upsertNodeStatusPacket; empty status clears the field.
     */
    suspend fun nodeStatusPacket(packet: MeshProtos.MeshPacket) {
        val num = packet.from.uint()
        if (num == 0L) return
        val status = runCatching {
            MeshProtos.StatusMessage.parseFrom(packet.decoded.payload)
        }.getOrNull()?.status ?: return
        val existing = db.nodeDao().get(num) ?: return
        db.nodeDao().upsert(existing.copy(nodeStatus = status.ifEmpty { null }))
    }

    /**
     * Port of `Position.hasValidCoordinates` (MeshPackets.swift): both axes must be
     * non-zero (a position with one axis unset would not render), and the Apple Park
     * simulator placeholder is refused. One more case iOS does not cover: a (0, 0) fix
     * that went through the firmware's precision reduction. That keeps the top
     * `precision_bits` bits and adds half a cell, so null island comes out as
     * (2^(31-p), 2^(31-p)), for example (262144, 262144) at 13 bits. It is still no
     * location, and it drags the map to the Gulf of Guinea.
     */
    private fun MeshProtos.Position.hasValidCoordinates(): Boolean {
        if (latitudeI == 0 || longitudeI == 0) return false
        if (latitudeI == 373346000 && longitudeI == -1220090000) return false
        val reducedNullIsland = latitudeI == longitudeI && latitudeI > 0 &&
            (latitudeI and (latitudeI - 1)) == 0 && latitudeI <= (1 shl 24)
        return !reducedNullIsland
    }

    /**
     * When a message "happened" for thread ordering. iOS uses the radio's rx_time, but
     * that is the radio's clock at one-second resolution, and our own sends are stamped
     * with the phone's clock: any skew between the two interleaves a fresh incoming
     * message among older ones. A live packet reaches the phone within moments of the
     * radio hearing it, so the phone's clock is the arrival time. The radio's time is kept
     * only when it is clearly historical (a store-and-forward replay), so those keep
     * their original place.
     */
    private fun arrivalTime(rxTimeSec: Int): Long {
        val now = System.currentTimeMillis()
        val rx = rxTimeSec.uint() * 1000
        return if (rx != 0L && rx < now - HISTORICAL_MS) rx else now
    }

    /** POSITION_APP. */
    suspend fun positionPacket(packet: MeshProtos.MeshPacket) {
        val pos = runCatching {
            MeshProtos.Position.parseFrom(packet.decoded.payload)
        }.getOrNull() ?: return
        position(packet.from.uint(), pos, packet.rxTime)
    }

    private suspend fun position(nodeNum: Long, pos: MeshProtos.Position, rxTime: Int) {
        if (!pos.hasValidCoordinates()) return
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

    /** TRACEROUTE_APP: a RouteDiscovery reply for a traceroute we sent. */
    suspend fun traceroute(packet: MeshProtos.MeshPacket) {
        val discovery = runCatching {
            MeshProtos.RouteDiscovery.parseFrom(packet.decoded.payload)
        }.getOrNull() ?: return
        val fromNum = packet.from.uint()
        val pending = db.tracerouteDao().latestPending(fromNum) ?: return
        db.tracerouteDao().applyResponse(
            id = pending.id,
            routeTowards = discovery.routeList.joinToString(",") { it.uint().toString() },
            snrTowards = discovery.snrTowardsList.joinToString(","),
            routeBack = discovery.routeBackList.joinToString(",") { it.uint().toString() },
            snrBack = discovery.snrBackList.joinToString(","),
        )
    }

    /** WAYPOINT_APP: shared map markers; a deleted waypoint arrives with empty name. */
    suspend fun waypointPacket(packet: MeshProtos.MeshPacket) {
        val wp = runCatching {
            MeshProtos.Waypoint.parseFrom(packet.decoded.payload)
        }.getOrNull() ?: return
        if (wp.name.isEmpty()) {
            db.waypointDao().delete(wp.id.uint())
            return
        }
        db.waypointDao().upsert(
            WaypointEntity(
                id = wp.id.uint(),
                name = wp.name,
                description = wp.description,
                icon = wp.icon,
                latitudeI = wp.latitudeI,
                longitudeI = wp.longitudeI,
                expire = wp.expire.uint(),
                lockedTo = wp.lockedTo.uint(),
                createdBy = packet.from.uint(),
                updated = System.currentTimeMillis(),
            )
        )
    }

    /** TELEMETRY_APP; handles device, environment, power, air-quality, and local-stats variants. */
    suspend fun telemetryPacket(packet: MeshProtos.MeshPacket) {
        val telemetry = runCatching {
            TelemetryProtos.Telemetry.parseFrom(packet.decoded.payload)
        }.getOrNull() ?: return
        val nodeNum = packet.from.uint()
        val timeSec = if (telemetry.time != 0) telemetry.time.uint()
        else if (packet.rxTime != 0) packet.rxTime.uint()
        else System.currentTimeMillis() / 1000
        val base = TelemetryEntity(
            nodeNum = nodeNum,
            metricsType = 0,
            time = if (timeSec != 0L) timeSec * 1000 else System.currentTimeMillis(),
            snr = if (packet.rxSnr != 0f) packet.rxSnr else null,
            rssi = if (packet.rxRssi != 0) packet.rxRssi else null,
        )
        when (telemetry.variantCase) {
            TelemetryProtos.Telemetry.VariantCase.DEVICE_METRICS -> {
                val m = telemetry.deviceMetrics
                db.telemetryDao().insert(
                    base.copy(
                        batteryLevel = if (m.hasBatteryLevel()) m.batteryLevel else null,
                        voltage = if (m.hasVoltage()) m.voltage else null,
                        channelUtilization = if (m.hasChannelUtilization()) m.channelUtilization else null,
                        airUtilTx = if (m.hasAirUtilTx()) m.airUtilTx else null,
                        uptimeSeconds = if (m.hasUptimeSeconds()) m.uptimeSeconds else null,
                    )
                )
            }
            TelemetryProtos.Telemetry.VariantCase.ENVIRONMENT_METRICS -> {
                val m = telemetry.environmentMetrics
                db.telemetryDao().insert(
                    base.copy(
                        metricsType = 1,
                        temperature = if (m.hasTemperature()) m.temperature else null,
                        relativeHumidity = if (m.hasRelativeHumidity()) m.relativeHumidity else null,
                        barometricPressure = if (m.hasBarometricPressure()) m.barometricPressure else null,
                        gasResistance = if (m.hasGasResistance()) m.gasResistance else null,
                        voltage = if (m.hasVoltage()) m.voltage else null,
                        current = if (m.hasCurrent()) m.current else null,
                        iaq = if (m.hasIaq()) m.iaq else null,
                        distance = if (m.hasDistance()) m.distance else null,
                        lux = if (m.hasLux()) m.lux else null,
                        whiteLux = if (m.hasWhiteLux()) m.whiteLux else null,
                        irLux = if (m.hasIrLux()) m.irLux else null,
                        uvLux = if (m.hasUvLux()) m.uvLux else null,
                        windDirection = if (m.hasWindDirection()) m.windDirection else null,
                        windSpeed = if (m.hasWindSpeed()) m.windSpeed else null,
                        weight = if (m.hasWeight()) m.weight else null,
                        windGust = if (m.hasWindGust()) m.windGust else null,
                        windLull = if (m.hasWindLull()) m.windLull else null,
                        radiation = if (m.hasRadiation()) m.radiation else null,
                        rainfall1H = if (m.hasRainfall1H()) m.rainfall1H else null,
                        rainfall24H = if (m.hasRainfall24H()) m.rainfall24H else null,
                        soilMoisture = if (m.hasSoilMoisture()) m.soilMoisture else null,
                        soilTemperature = if (m.hasSoilTemperature()) m.soilTemperature else null,
                    )
                )
            }
            TelemetryProtos.Telemetry.VariantCase.POWER_METRICS -> {
                val m = telemetry.powerMetrics
                db.telemetryDao().insert(
                    base.copy(
                        metricsType = 2,
                        powerCh1Voltage = if (m.hasCh1Voltage()) m.ch1Voltage else null,
                        powerCh1Current = if (m.hasCh1Current()) m.ch1Current else null,
                        powerCh2Voltage = if (m.hasCh2Voltage()) m.ch2Voltage else null,
                        powerCh2Current = if (m.hasCh2Current()) m.ch2Current else null,
                        powerCh3Voltage = if (m.hasCh3Voltage()) m.ch3Voltage else null,
                        powerCh3Current = if (m.hasCh3Current()) m.ch3Current else null,
                    )
                )
            }
            TelemetryProtos.Telemetry.VariantCase.AIR_QUALITY_METRICS -> {
                val m = telemetry.airQualityMetrics
                db.telemetryDao().insert(
                    base.copy(
                        metricsType = 3,
                        pm10Standard = if (m.hasPm10Standard()) m.pm10Standard else null,
                        pm25Standard = if (m.hasPm25Standard()) m.pm25Standard else null,
                        pm100Standard = if (m.hasPm100Standard()) m.pm100Standard else null,
                        pm10Environmental = if (m.hasPm10Environmental()) m.pm10Environmental else null,
                        pm25Environmental = if (m.hasPm25Environmental()) m.pm25Environmental else null,
                        pm100Environmental = if (m.hasPm100Environmental()) m.pm100Environmental else null,
                    )
                )
            }
            TelemetryProtos.Telemetry.VariantCase.LOCAL_STATS -> {
                val m = telemetry.localStats
                db.telemetryDao().insert(
                    base.copy(
                        metricsType = 4,
                        uptimeSeconds = m.uptimeSeconds,
                        channelUtilization = m.channelUtilization,
                        airUtilTx = m.airUtilTx,
                        numPacketsTx = m.numPacketsTx,
                        numPacketsRx = m.numPacketsRx,
                        numPacketsRxBad = m.numPacketsRxBad,
                        numRxDupe = m.numRxDupe,
                        numTxRelay = m.numTxRelay,
                        numTxRelayCanceled = m.numTxRelayCanceled,
                        numOnlineNodes = m.numOnlineNodes,
                        numTotalNodes = m.numTotalNodes,
                        noiseFloor = m.noiseFloor,
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

        /** Roles that cannot receive DMs (unmessagableFromUser fallback). */
        private val UNMESSAGABLE_ROLES = setOf(2, 4, 5, 6, 7, 10, 11)
    }
}
