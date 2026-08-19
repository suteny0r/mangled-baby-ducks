package com.suteny0r.mangledbabyducks.radio

import android.util.Log
import com.google.protobuf.ByteString
import com.suteny0r.mangledbabyducks.db.MeshDatabase
import com.suteny0r.mangledbabyducks.db.MessageEntity
import com.suteny0r.mangledbabyducks.db.TracerouteEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.meshtastic.proto.AdminProtos
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.Portnums
import kotlin.random.Random

/** Connection lifecycle, port of AccessoryManagerState. */
sealed interface RadioState {
    data object Idle : RadioState
    data object Connecting : RadioState
    data object Communicating : RadioState
    data class RetrievingDatabase(val nodeCount: Int) : RadioState
    data object Subscribed : RadioState
    data class Reconnecting(val attempt: Int) : RadioState
    data class Failed(val reason: String) : RadioState
}

/**
 * The single radio gateway: owns the active connection, runs the wantConfig /
 * wantDatabase handshake, dispatches FromRadio into the ingest layer, and builds
 * outbound packets. Port of AccessoryManager + its extensions.
 */
class RadioManager(
    private val db: MeshDatabase,
    private val ingest: PacketIngest,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<RadioState>(RadioState.Idle)
    val state: StateFlow<RadioState> = _state.asStateFlow()

    private val _myNodeNum = MutableStateFlow(0L)
    val myNodeNum: StateFlow<Long> = _myNodeNum.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    /** Inbound messages worth notifying about (already stored). */
    val incomingMessages = MutableSharedFlow<MessageEntity>(extraBufferCapacity = 64)

    private val configCompleteIds = MutableSharedFlow<Long>(extraBufferCapacity = 16)

    private var connection: RadioConnection? = null
    private var eventJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var nodeCount = 0
    @Volatile private var lastRxMs = 0L

    // Kept so a dropped link can be re-established with a fresh connection object.
    private var connectionFactory: (() -> RadioConnection)? = null
    private var lastName: String? = null

    val isConnected: Boolean
        get() = _state.value is RadioState.Subscribed || _state.value is RadioState.RetrievingDatabase

    suspend fun connect(name: String?, factory: () -> RadioConnection) {
        disconnect()
        connectionFactory = factory
        lastName = name
        // Initial-connect retry, mirroring the iOS pipeline's maxRetries = 2 with a
        // 2 s delay — transient GATT 133 style failures self-heal instead of
        // surfacing to the user.
        repeat(CONNECT_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                _state.value = RadioState.Reconnecting(attempt)
                delay(RECONNECT_DELAY_MS)
            }
            try {
                establish(factory(), name)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Connect attempt ${attempt + 1}/$CONNECT_ATTEMPTS failed: ${e.message}")
            }
        }
        // state is already Failed from the last establish()
    }

    private suspend fun establish(conn: RadioConnection, name: String?) {
        _state.value = RadioState.Connecting
        _deviceName.value = name
        connection = conn
        try {
            eventJob = scope.launch { conn.events.collect { handleEvent(it) } }
            conn.connect()
            _state.value = RadioState.Communicating

            // Step 3: wantConfig handshake — the radio streams config then echoes the nonce.
            sendHeartbeat()
            awaitConfigComplete(MeshProtocol.NONCE_ONLY_CONFIG.toLong(), timeoutMs = 30_000) {
                send { it.setWantConfigId(MeshProtocol.NONCE_ONLY_CONFIG) }
                conn.startDrainPendingPackets()
            }

            // Step 5: node DB dump under the second nonce.
            nodeCount = 0
            _state.value = RadioState.RetrievingDatabase(0)
            awaitConfigComplete(MeshProtocol.NONCE_ONLY_DB.toLong(), timeoutMs = 120_000) {
                send { it.setWantConfigId(MeshProtocol.NONCE_ONLY_DB) }
                conn.startDrainPendingPackets()
            }

            // Step 7: set the radio's clock, then we are live.
            sendSetTime()
            _state.value = RadioState.Subscribed

            if (conn.requiresPeriodicHeartbeat) startPeriodicHeartbeat()
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed", e)
            _state.value = RadioState.Failed(e.message ?: "Connection failed")
            eventJob?.cancel()
            runCatching { conn.disconnect() }
            connection = null
            throw e
        }
    }

    private suspend fun awaitConfigComplete(nonce: Long, timeoutMs: Long, request: suspend () -> Unit) {
        withTimeout(timeoutMs) {
            val waiter = scope.launch { configCompleteIds.filter { it == nonce }.first() }
            request()
            waiter.join()
        }
    }

    suspend fun disconnect() {
        connectionFactory = null
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        eventJob?.cancel()
        connection?.let { runCatching { it.disconnect() } }
        connection = null
        _state.value = RadioState.Idle
        _deviceName.value = null
    }

    private suspend fun handleEvent(event: ConnectionEvent) {
        lastRxMs = System.currentTimeMillis()
        when (event) {
            is ConnectionEvent.Data -> processFromRadio(event.fromRadio)
            is ConnectionEvent.LogMessage -> Log.d(TAG, "radio: ${event.message}")
            is ConnectionEvent.RssiUpdate -> {}
            is ConnectionEvent.Disconnected -> {
                Log.w(TAG, "Link lost (reconnect=${event.shouldReconnect}): ${event.error}")
                if (event.shouldReconnect && connectionFactory != null) {
                    scheduleReconnect(event.error)
                } else {
                    _state.value = RadioState.Failed(event.error ?: "Disconnected")
                }
            }
        }
    }

    private suspend fun processFromRadio(fromRadio: MeshProtos.FromRadio) {
        when (fromRadio.payloadVariantCase) {
            MeshProtos.FromRadio.PayloadVariantCase.MY_INFO -> {
                _myNodeNum.value = ingest.myInfo(fromRadio.myInfo, _deviceName.value)
            }
            MeshProtos.FromRadio.PayloadVariantCase.NODE_INFO -> {
                ingest.nodeInfo(fromRadio.nodeInfo)
                nodeCount++
                if (_state.value is RadioState.RetrievingDatabase) {
                    _state.value = RadioState.RetrievingDatabase(nodeCount)
                }
            }
            MeshProtos.FromRadio.PayloadVariantCase.CHANNEL ->
                ingest.channel(fromRadio.channel)
            MeshProtos.FromRadio.PayloadVariantCase.CONFIG ->
                ingest.config(fromRadio.config)
            MeshProtos.FromRadio.PayloadVariantCase.MODULECONFIG ->
                ingest.moduleConfig(fromRadio.moduleConfig)
            MeshProtos.FromRadio.PayloadVariantCase.METADATA ->
                ingest.deviceMetadata(fromRadio.metadata)
            MeshProtos.FromRadio.PayloadVariantCase.CONFIG_COMPLETE_ID ->
                configCompleteIds.emit(fromRadio.configCompleteId.uint())
            MeshProtos.FromRadio.PayloadVariantCase.PACKET ->
                processMeshPacket(fromRadio.packet)
            MeshProtos.FromRadio.PayloadVariantCase.REBOOTED -> {
                if (_state.value is RadioState.Subscribed) {
                    Log.i(TAG, "Radio rebooted; re-requesting config")
                    send { it.setWantConfigId(MeshProtocol.NONCE_ONLY_CONFIG) }
                }
            }
            else -> Log.d(TAG, "Unhandled FromRadio: ${fromRadio.payloadVariantCase}")
        }
    }

    private suspend fun processMeshPacket(packet: MeshProtos.MeshPacket) {
        val myNum = _myNodeNum.value
        ingest.updateFromAnyPacket(packet, myNum)
        when (packet.decoded.portnum) {
            Portnums.PortNum.TEXT_MESSAGE_APP,
            Portnums.PortNum.ALERT_APP,
            Portnums.PortNum.DETECTION_SENSOR_APP -> {
                ingest.textMessage(packet, myNum)?.let { incomingMessages.emit(it) }
            }
            Portnums.PortNum.NODEINFO_APP -> ingest.userPacket(packet)
            Portnums.PortNum.POSITION_APP -> ingest.positionPacket(packet)
            Portnums.PortNum.TELEMETRY_APP -> ingest.telemetryPacket(packet)
            Portnums.PortNum.ROUTING_APP -> ingest.routing(packet, myNum)
            Portnums.PortNum.TRACEROUTE_APP -> ingest.traceroute(packet)
            Portnums.PortNum.WAYPOINT_APP -> ingest.waypointPacket(packet)
            else -> Log.d(TAG, "Unhandled port ${packet.decoded.portnum}")
        }
    }

    /**
     * Send a text message. Port of AccessoryManager.sendMessage: the MessageEntity is
     * stored optimistically before the radio write, acks arrive later via ROUTING_APP.
     */
    suspend fun sendTextMessage(
        text: String,
        toNum: Long? = null,
        channel: Int = 0,
        replyId: Long = 0,
        isEmoji: Boolean = false,
    ): Boolean {
        val myNum = _myNodeNum.value
        if (myNum == 0L) return false
        // Curly-quote normalization from the iOS sender.
        val normalized = text.replace('’', '\'').replace('“', '"').replace('”', '"')
        val payload = normalized.encodeToByteArray()
        if (payload.size > MeshProtocol.MAX_TEXT_BYTES) return false

        val messageId = Random.nextLong(255L, 0xFFFFFFFFL)
        db.messageDao().insertIgnore(
            MessageEntity(
                messageId = messageId,
                fromNum = myNum,
                toNum = toNum,
                channel = channel,
                portNum = Portnums.PortNum.TEXT_MESSAGE_APP_VALUE,
                payload = normalized,
                timestamp = System.currentTimeMillis(),
                read = true,
                isEmoji = isEmoji,
                replyId = replyId,
            )
        )
        if (toNum != null) db.userDao().touchLastMessage(toNum, System.currentTimeMillis())

        val data = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
            .setPayload(ByteString.copyFrom(payload))
            .setEmoji(if (isEmoji) 1 else 0)
            .apply { if (replyId > 0) setReplyId(replyId.toInt()) }
            .build()
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setId(messageId.toInt())
            .setTo((toNum ?: MeshProtocol.BROADCAST_NUM).toInt())
            .setChannel(channel)
            .setWantAck(true)
            .setPriority(MeshProtos.MeshPacket.Priority.RELIABLE)
            .setDecoded(data)
            .build()
        return runCatching {
            send { it.setPacket(packet) }
        }.isSuccess
    }

    /** Start a traceroute to a node; the reply lands via TRACEROUTE_APP ingest. */
    suspend fun sendTraceroute(destNum: Long): Boolean {
        if (_myNodeNum.value == 0L) return false
        db.tracerouteDao().insert(
            TracerouteEntity(toNum = destNum, time = System.currentTimeMillis())
        )
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.TRACEROUTE_APP)
            .setPayload(MeshProtos.RouteDiscovery.getDefaultInstance().toByteString())
            .setWantResponse(true)
            .build()
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setId(Random.nextLong(255L, 0xFFFFFFFFL).toInt())
            .setTo(destNum.toInt())
            .setWantAck(true)
            .setDecoded(data)
            .build()
        return runCatching { send { it.setPacket(packet) } }.isSuccess
    }

    /** Create or update a waypoint and share it on the given channel. */
    suspend fun sendWaypoint(
        name: String,
        description: String,
        latitudeI: Int,
        longitudeI: Int,
        channel: Int,
    ): Boolean {
        val myNum = _myNodeNum.value
        if (myNum == 0L) return false
        val id = Random.nextLong(255L, 0xFFFFFFFFL)
        val waypoint = MeshProtos.Waypoint.newBuilder()
            .setId(id.toInt())
            .setName(name.take(30))
            .setDescription(description.take(100))
            .setLatitudeI(latitudeI)
            .setLongitudeI(longitudeI)
            .build()
        db.waypointDao().upsert(
            com.suteny0r.mangledbabyducks.db.WaypointEntity(
                id = id,
                name = waypoint.name,
                description = waypoint.description,
                icon = 0,
                latitudeI = latitudeI,
                longitudeI = longitudeI,
                expire = 0,
                lockedTo = 0,
                createdBy = myNum,
                updated = System.currentTimeMillis(),
            )
        )
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.WAYPOINT_APP)
            .setPayload(waypoint.toByteString())
            .build()
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setId(Random.nextLong(255L, 0xFFFFFFFFL).toInt())
            .setTo(MeshProtocol.BROADCAST_NUM.toInt())
            .setChannel(channel)
            .setWantAck(true)
            .setDecoded(data)
            .build()
        return runCatching { send { it.setPacket(packet) } }.isSuccess
    }

    /**
     * Broadcast our own User record (including public key) on NODEINFO_APP so nearby
     * nodes learn or refresh this radio's identity — the mesh-native key exchange.
     * Port of the iOS Exchange User Info action, addressed to broadcast.
     */
    suspend fun broadcastNodeInfo(): Boolean {
        val myNum = _myNodeNum.value
        if (myNum == 0L) return false
        val me = db.userDao().get(myNum) ?: return false
        val user = MeshProtos.User.newBuilder()
            .setId(me.userId ?: "!%08x".format(myNum))
            .setLongName(me.longName ?: "")
            .setShortName(me.shortName ?: "")
            .apply { me.publicKey?.let { setPublicKey(ByteString.copyFrom(it)) } }
            .build()
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.NODEINFO_APP)
            .setPayload(user.toByteString())
            .build()
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setId(Random.nextLong(255L, 0xFFFFFFFFL).toInt())
            .setTo(MeshProtocol.BROADCAST_NUM.toInt())
            .setChannel(0)
            .setDecoded(data)
            .build()
        return runCatching { send { it.setPacket(packet) } }.isSuccess
    }

    /**
     * Rename this radio's owner via AdminMessage.set_owner, with an optimistic
     * local user update (the radio re-broadcasts NodeInfo on its own schedule).
     */
    suspend fun setOwner(longName: String, shortName: String): Boolean {
        val myNum = _myNodeNum.value
        if (myNum == 0L) return false
        val me = db.userDao().get(myNum)
        val owner = MeshProtos.User.newBuilder()
            .setLongName(longName.take(39))
            .setShortName(shortName.take(4))
            .build()
        val admin = AdminProtos.AdminMessage.newBuilder()
            .setSetOwner(owner)
            .build()
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.ADMIN_APP)
            .setPayload(admin.toByteString())
            .build()
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setId(Random.nextLong(255L, 0xFFFFFFFFL).toInt())
            .setTo(myNum.toInt())
            .setDecoded(data)
            .build()
        val sent = runCatching { send { it.setPacket(packet) } }.isSuccess
        if (sent && me != null) {
            db.userDao().upsert(me.copy(longName = longName.take(39), shortName = shortName.take(4)))
        }
        return sent
    }

    suspend fun sendHeartbeat() {
        // Nonce 1 is special-cased by some firmware; avoid it like the iOS sender does.
        val heartbeat = MeshProtos.Heartbeat.newBuilder()
            .setNonce(Random.nextLong(2L, 0xFFFFFFFFL).toInt())
            .build()
        send { it.setHeartbeat(heartbeat) }
    }

    private suspend fun sendSetTime() {
        val myNum = _myNodeNum.value
        if (myNum == 0L) return
        val admin = AdminProtos.AdminMessage.newBuilder()
            .setSetTimeOnly((System.currentTimeMillis() / 1000).toInt())
            .build()
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.ADMIN_APP)
            .setPayload(admin.toByteString())
            .build()
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setId(Random.nextLong(255L, 0xFFFFFFFFL).toInt())
            .setTo(myNum.toInt())
            .setDecoded(data)
            .build()
        runCatching { send { it.setPacket(packet) } }
            .onFailure { Log.w(TAG, "setTime failed", it) }
    }

    /**
     * Re-establish a dropped link with a fresh connection object. Mirrors the iOS
     * reconnect policy: only fired for link-loss causes worth retrying (the transport
     * decides that), never for a user-initiated disconnect.
     */
    private fun scheduleReconnect(reason: String?) {
        if (reconnectJob?.isActive == true) return
        val factory = connectionFactory ?: return
        reconnectJob = scope.launch {
            heartbeatJob?.cancel()
            eventJob?.cancel()
            connection?.let { runCatching { it.disconnect() } }
            connection = null
            repeat(MAX_RECONNECT_ATTEMPTS) { attempt ->
                _state.value = RadioState.Reconnecting(attempt + 1)
                delay(RECONNECT_DELAY_MS * (attempt + 1))
                try {
                    establish(factory(), lastName)
                    Log.i(TAG, "Reconnected after ${attempt + 1} attempt(s)")
                    return@launch
                } catch (e: Exception) {
                    Log.w(TAG, "Reconnect attempt ${attempt + 1} failed: ${e.message}")
                }
            }
            _state.value = RadioState.Failed(reason ?: "Connection lost")
        }
    }

    private fun startPeriodicHeartbeat() {
        heartbeatJob?.cancel()
        lastRxMs = System.currentTimeMillis()
        heartbeatJob = scope.launch {
            while (true) {
                delay(MeshProtocol.HEARTBEAT_INTERVAL_MS)
                runCatching { sendHeartbeat() }
                // Watchdog: a healthy radio answers heartbeats (QueueStatus) and
                // streams packets; total silence means the link is dead even if
                // the socket looks open.
                if (System.currentTimeMillis() - lastRxMs > HEARTBEAT_TIMEOUT_MS) {
                    Log.w(TAG, "No traffic for ${HEARTBEAT_TIMEOUT_MS / 1000}s; reconnecting")
                    scheduleReconnect("Heartbeat timeout")
                    return@launch
                }
            }
        }
    }

    private suspend fun send(build: (MeshProtos.ToRadio.Builder) -> MeshProtos.ToRadio.Builder) {
        val conn = connection ?: throw RadioException("Not connected")
        conn.send(build(MeshProtos.ToRadio.newBuilder()).build())
    }

    companion object {
        private const val TAG = "RadioManager"
        private const val CONNECT_ATTEMPTS = 3
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val HEARTBEAT_TIMEOUT_MS = 3 * MeshProtocol.HEARTBEAT_INTERVAL_MS
    }
}
