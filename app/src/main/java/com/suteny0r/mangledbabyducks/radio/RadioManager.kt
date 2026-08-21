package com.suteny0r.mangledbabyducks.radio

import android.util.Log
import com.google.protobuf.ByteString
import com.suteny0r.mangledbabyducks.db.MeshDatabase
import com.suteny0r.mangledbabyducks.db.MessageEntity
import com.suteny0r.mangledbabyducks.db.TracerouteEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.meshtastic.proto.AdminProtos
import org.meshtastic.proto.AppOnlyProtos
import org.meshtastic.proto.ChannelProtos
import org.meshtastic.proto.ConfigProtos
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.Portnums
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** Connection lifecycle, port of AccessoryManagerState. */
sealed interface RadioState {
    data object Idle : RadioState
    /** Waiting for the radio's advertisement before touching the GATT stack. */
    data class Searching(val attempt: Int = 1, val of: Int = 1) : RadioState
    /** Opening a link that was never up: carries which try of how many this is. */
    data class Connecting(val attempt: Int = 1, val of: Int = 1) : RadioState
    data object Communicating : RadioState
    data class RetrievingDatabase(val nodeCount: Int) : RadioState
    data object Subscribed : RadioState
    /** Re-opening a link that had been live, so "connection lost" is accurate. */
    data class Reconnecting(val attempt: Int) : RadioState
    data class Failed(val reason: String) : RadioState
}

/**
 * Checks whether the target radio is currently reachable before a connect attempt is
 * made. Supplied by the BLE transport (a scan for the radio's advertisement); null for
 * transports where a blind attempt costs nothing, such as TCP.
 */
fun interface PresenceProbe {
    suspend fun isVisible(timeoutMs: Long): Boolean
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

    // Exactly one attempt loop may run at a time. Two loops used to be able to drive
    // establish() in parallel (the initial-connect retry plus an event-driven
    // reconnect), each registering its own GATT client against the same radio; that
    // is what produced the startup connect/disconnect storm and the stalled-forever
    // state, so the lock and the generation counter below are load-bearing.
    private val attemptLock = Mutex()

    /** Bumped by every new connect/disconnect request; an older attempt loop aborts when it changes. */
    @Volatile private var requestGeneration = 0

    /** Only a link that finished the handshake may drive an automatic reconnect. */
    @Volatile private var sessionWentLive = false

    /** Presence probe for the current target, reused by the reconnect loop. */
    @Volatile private var lastPresence: PresenceProbe? = null

    /** Completed when the link drops mid-handshake so establish() aborts instead of waiting out its timeout. */
    private var linkLost: CompletableDeferred<String>? = null

    /** Set once the process has spent its single automatic connect attempt. */
    private val autoConnectSpent = AtomicBoolean(false)

    // Kept so a dropped link can be re-established with a fresh connection object.
    private var connectionFactory: (() -> RadioConnection)? = null
    private var lastName: String? = null

    val isConnected: Boolean
        get() = _state.value is RadioState.Subscribed || _state.value is RadioState.RetrievingDatabase

    /** True while a connect or reconnect attempt loop owns the radio. */
    val isAttempting: Boolean
        get() = attemptLock.isLocked

    /**
     * One automatic connect per process. Callers such as MainActivity fire from
     * onCreate, the permission result and onResume, so the guard is a CAS rather than
     * a state check: once the automatic attempt has been spent, a radio that is off
     * or out of range is not retried behind the user's back — they reconnect from the
     * Connect screen. Returns false when the attempt was not started.
     */
    suspend fun autoConnect(
        name: String?,
        presence: PresenceProbe? = null,
        factory: () -> RadioConnection,
    ): Boolean {
        if (isConnected || isAttempting) return false
        if (!autoConnectSpent.compareAndSet(false, true)) return false
        connect(name, presence, factory)
        return true
    }

    // `factory` is deliberately last: every call site passes it as a trailing lambda.
    suspend fun connect(
        name: String?,
        presence: PresenceProbe? = null,
        factory: () -> RadioConnection,
    ) {
        val gen = beginRequest()
        connectionFactory = factory
        lastName = name
        lastPresence = presence
        // Name the target before the first attempt: the UI has to be able to say
        // which radio it is reaching for, not just "Connecting…".
        _deviceName.value = name
        _state.value = RadioState.Connecting(1, CONNECT_ATTEMPTS)
        // Initial-connect retry, mirroring the iOS pipeline's maxRetries = 2 with a
        // 2 s delay — transient GATT 133 style failures self-heal instead of
        // surfacing to the user.
        attemptLock.withLock {
            runAttempts(
                gen = gen,
                factory = factory,
                name = name,
                attempts = CONNECT_ATTEMPTS,
                delayFor = { attempt -> if (attempt == 0) 0L else RECONNECT_DELAY_MS },
                progressFor = { attempt -> RadioState.Connecting(attempt + 1, CONNECT_ATTEMPTS) },
                searchingFor = { attempt -> RadioState.Searching(attempt + 1, CONNECT_ATTEMPTS) },
                presenceFor = { presence },
                // A radio that is not advertising will not answer a GATT connect either,
                // so say so instead of retrying into three 5 s timeouts.
                absentIsTerminal = true,
                exhaustedReason = "Could not reach ${name ?: "the radio"}",
            )
        }
    }

    /**
     * Supersede whatever session or attempt loop is running and tear the link down.
     * Returns the new request generation; any loop holding an older one stops at its
     * next checkpoint instead of racing this request for the radio.
     */
    private suspend fun beginRequest(): Int {
        val gen = ++requestGeneration
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        eventJob?.cancel()
        sessionWentLive = false
        linkLost?.complete("Superseded")
        connection?.let { runCatching { it.disconnect() } }
        connection = null
        return gen
    }

    /**
     * Sequential attempt loop: the only place establish() is called from. Aborts as
     * soon as a newer request arrives, and lands on a terminal Failed state when the
     * attempts run out, so the UI never sits in Reconnecting forever.
     */
    private suspend fun runAttempts(
        gen: Int,
        factory: () -> RadioConnection,
        name: String?,
        attempts: Int,
        delayFor: (Int) -> Long,
        progressFor: (Int) -> RadioState,
        searchingFor: (Int) -> RadioState,
        presenceFor: (Int) -> PresenceProbe?,
        absentIsTerminal: Boolean,
        exhaustedReason: String,
    ) {
        for (attempt in 0 until attempts) {
            if (gen != requestGeneration) return
            val wait = delayFor(attempt)
            if (wait > 0) {
                _state.value = progressFor(attempt)
                delay(wait)
                if (gen != requestGeneration) return
            }
            // Scan first: a GATT connect to a radio that is not advertising costs a ~5 s
            // timeout and comes back as status 133, which reads like a broken app.
            val presence = presenceFor(attempt)
            if (presence != null) {
                _state.value = searchingFor(attempt)
                val visible = presence.isVisible(PRESENCE_TIMEOUT_MS)
                if (gen != requestGeneration) return
                if (!visible) {
                    Log.w(TAG, "${name ?: "Radio"} not advertising (attempt ${attempt + 1}/$attempts)")
                    if (absentIsTerminal) {
                        _state.value = RadioState.Failed("${name ?: "The radio"} is not in range")
                        return
                    }
                    continue
                }
            }
            _state.value = progressFor(attempt)
            try {
                establish(gen, factory(), name)
                if (attempt > 0) Log.i(TAG, "Connected after ${attempt + 1} attempt(s)")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Attempt ${attempt + 1}/$attempts failed: ${e.message}")
            }
        }
        if (gen == requestGeneration) _state.value = RadioState.Failed(exhaustedReason)
    }

    private suspend fun establish(gen: Int, conn: RadioConnection, name: String?) {
        // The attempt state is owned by runAttempts; this only names the target.
        _deviceName.value = name
        eventJob?.cancel()
        val lost = CompletableDeferred<String>()
        linkLost = lost
        connection = conn
        try {
            // The collector is tagged with its own connection: a dying predecessor
            // must not be able to tear down or reconnect the live session.
            eventJob = scope.launch { conn.events.collect { handleEvent(conn, it) } }
            conn.connect()
            if (gen != requestGeneration) throw RadioException("Superseded by a newer request")
            _state.value = RadioState.Communicating

            // Step 3: wantConfig handshake — the radio streams config then echoes the nonce.
            sendHeartbeat()
            awaitConfigComplete(MeshProtocol.NONCE_ONLY_CONFIG.toLong(), timeoutMs = 30_000, lost = lost) {
                send { it.setWantConfigId(MeshProtocol.NONCE_ONLY_CONFIG) }
                conn.startDrainPendingPackets()
            }

            // Step 5: node DB dump under the second nonce.
            nodeCount = 0
            _state.value = RadioState.RetrievingDatabase(0)
            awaitConfigComplete(MeshProtocol.NONCE_ONLY_DB.toLong(), timeoutMs = 120_000, lost = lost) {
                send { it.setWantConfigId(MeshProtocol.NONCE_ONLY_DB) }
                conn.startDrainPendingPackets()
            }

            // Step 7: set the radio's clock, then we are live. A Disconnect issued
            // mid-handshake must not end with a session declaring itself live.
            if (gen != requestGeneration) throw RadioException("Superseded by a newer request")
            sendSetTime()
            sessionWentLive = true
            _state.value = RadioState.Subscribed

            // Retention: positions (non-latest) and telemetry older than 30 days.
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            runCatching {
                db.positionDao().prune(cutoff)
                db.telemetryDao().prune(cutoff)
            }

            if (conn.requiresPeriodicHeartbeat) startPeriodicHeartbeat()
        } catch (e: Exception) {
            Log.w(TAG, "Establish failed: ${e.message}")
            eventJob?.cancel()
            runCatching { conn.disconnect() }
            if (connection === conn) connection = null
            if (linkLost === lost) linkLost = null
            throw e
        }
    }

    /**
     * Wait for the radio to echo a want_config nonce. The waiter subscribes
     * UNDISPATCHED so a fast reply cannot land before the collector exists, and a
     * link drop aborts the wait instead of burning the whole timeout (a 120 s stall
     * on the node-DB step read as a hang).
     */
    private suspend fun awaitConfigComplete(
        nonce: Long,
        timeoutMs: Long,
        lost: CompletableDeferred<String>,
        request: suspend () -> Unit,
    ) {
        withTimeout(timeoutMs) {
            coroutineScope {
                val nonceSeen = async(start = CoroutineStart.UNDISPATCHED) {
                    configCompleteIds.filter { it == nonce }.first()
                }
                val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
                    throw RadioException("Link lost during handshake: ${lost.await()}")
                }
                request()
                nonceSeen.await()
                watchdog.cancel()
            }
        }
    }

    suspend fun disconnect() {
        beginRequest()
        connectionFactory = null
        _state.value = RadioState.Idle
        _deviceName.value = null
    }

    private suspend fun handleEvent(source: RadioConnection, event: ConnectionEvent) {
        // A superseded connection keeps emitting while it dies; its events must not
        // touch the live session or start a second reconnect driver.
        if (source !== connection) return
        lastRxMs = System.currentTimeMillis()
        when (event) {
            is ConnectionEvent.Data -> processFromRadio(event.fromRadio)
            is ConnectionEvent.LogMessage -> Log.d(TAG, "radio: ${event.message}")
            is ConnectionEvent.RssiUpdate -> {}
            is ConnectionEvent.Disconnected -> {
                Log.w(TAG, "Link lost (reconnect=${event.shouldReconnect}): ${event.error}")
                if (!sessionWentLive) {
                    // Still inside establish(): the attempt loop owns the retry, so
                    // just unblock it rather than starting a competing loop.
                    linkLost?.complete(event.error ?: "Disconnected")
                    return
                }
                sessionWentLive = false
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

    private suspend fun sendAdmin(build: (AdminProtos.AdminMessage.Builder) -> AdminProtos.AdminMessage.Builder): Boolean {
        val myNum = _myNodeNum.value
        if (myNum == 0L) return false
        val admin = build(AdminProtos.AdminMessage.newBuilder()).build()
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.ADMIN_APP)
            .setPayload(admin.toByteString())
            .build()
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setId(Random.nextLong(255L, 0xFFFFFFFFL).toInt())
            .setTo(myNum.toInt())
            .setDecoded(data)
            .build()
        return runCatching { send { it.setPacket(packet) } }.isSuccess
    }

    /**
     * Write one LocalConfig section wrapped in an edit transaction (the radio defers
     * its save-and-reboot until commit). The radio typically reboots afterwards;
     * auto-reconnect and the REBOOTED handler pick things back up.
     */
    suspend fun setConfig(config: ConfigProtos.Config): Boolean {
        if (!sendAdmin { it.setBeginEditSettings(true) }) return false
        if (!sendAdmin { it.setSetConfig(config) }) return false
        return sendAdmin { it.setCommitEditSettings(true) }
    }

    /**
     * Mark a node as a favorite on the radio. No edit transaction: this is a single
     * admin field, matching the Swift original (AccessoryManager+ToRadio.swift).
     */
    suspend fun setFavorite(nodeNum: Long, favorite: Boolean): Boolean {
        val num = nodeNum.toInt()
        return if (favorite) {
            sendAdmin { it.setSetFavoriteNode(num) }
        } else {
            sendAdmin { it.setRemoveFavoriteNode(num) }
        }
    }

    /**
     * Mark a node as ignored on the radio (incoming traffic from it is dropped).
     * No edit transaction, matching the Swift original.
     */
    suspend fun setIgnored(nodeNum: Long, ignored: Boolean): Boolean {
        val num = nodeNum.toInt()
        return if (ignored) {
            sendAdmin { it.setSetIgnoredNode(num) }
        } else {
            sendAdmin { it.setRemoveIgnoredNode(num) }
        }
    }

    /** Replace the channel table and LoRa config from a shared ChannelSet URL. */
    suspend fun applyChannelSet(set: AppOnlyProtos.ChannelSet): Boolean {
        if (!sendAdmin { it.setBeginEditSettings(true) }) return false
        set.settingsList.forEachIndexed { index, settings ->
            val channel = ChannelProtos.Channel.newBuilder()
                .setIndex(index)
                .setSettings(settings)
                .setRole(
                    if (index == 0) ChannelProtos.Channel.Role.PRIMARY
                    else ChannelProtos.Channel.Role.SECONDARY
                )
                .build()
            if (!sendAdmin { it.setSetChannel(channel) }) return false
        }
        if (set.hasLoraConfig()) {
            val config = ConfigProtos.Config.newBuilder().setLora(set.loraConfig).build()
            if (!sendAdmin { it.setSetConfig(config) }) return false
        }
        return sendAdmin { it.setCommitEditSettings(true) }
    }

    /** Broadcast the phone's GPS fix as this node's position. */
    suspend fun sendPhonePosition(latitudeI: Int, longitudeI: Int, altitude: Int): Boolean {
        val myNum = _myNodeNum.value
        if (myNum == 0L) return false
        val position = MeshProtos.Position.newBuilder()
            .setLatitudeI(latitudeI)
            .setLongitudeI(longitudeI)
            .setAltitude(altitude)
            .setTime((System.currentTimeMillis() / 1000).toInt())
            .setLocationSource(MeshProtos.Position.LocSource.LOC_EXTERNAL)
            .build()
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.POSITION_APP)
            .setPayload(position.toByteString())
            .build()
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setId(Random.nextLong(255L, 0xFFFFFFFFL).toInt())
            .setTo(MeshProtocol.BROADCAST_NUM.toInt())
            .setDecoded(data)
            .build()
        return runCatching { send { it.setPacket(packet) } }.isSuccess
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
        if (reconnectJob?.isActive == true || attemptLock.isLocked) return
        val factory = connectionFactory ?: return
        val name = lastName
        // Adopt the current generation rather than bumping it: a user-initiated
        // connect() supersedes this loop, never the other way round.
        val gen = requestGeneration
        reconnectJob = scope.launch {
            heartbeatJob?.cancel()
            eventJob?.cancel()
            connection?.let { runCatching { it.disconnect() } }
            connection = null
            attemptLock.withLock {
                runAttempts(
                    gen = gen,
                    factory = factory,
                    name = name,
                    attempts = MAX_RECONNECT_ATTEMPTS,
                    delayFor = { attempt -> RECONNECT_DELAY_MS * (attempt + 1) },
                    progressFor = { attempt -> RadioState.Reconnecting(attempt + 1) },
                    searchingFor = { attempt -> RadioState.Searching(attempt + 1, MAX_RECONNECT_ATTEMPTS) },
                    // Skip the scan on the first try: the radio was live a moment ago, and
                    // a reboot after a config write comes back within seconds. Later tries
                    // wait for the advertisement instead of hammering a radio that is down.
                    presenceFor = { attempt -> if (attempt == 0) null else lastPresence },
                    absentIsTerminal = false,
                    exhaustedReason = reason ?: "Connection lost",
                )
            }
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
        /** How long to wait for a known radio's advertisement before giving up on it. */
        private const val PRESENCE_TIMEOUT_MS = 6_000L
        private const val HEARTBEAT_TIMEOUT_MS = 3 * MeshProtocol.HEARTBEAT_INTERVAL_MS
        private const val RETENTION_MS = 30L * 24 * 3600_000
    }
}
