package com.suteny0r.mangledbabyducks.radio

import android.util.Log
import com.google.protobuf.ByteString
import com.suteny0r.mangledbabyducks.db.MeshDatabase
import com.suteny0r.mangledbabyducks.db.MessageEntity
import com.suteny0r.mangledbabyducks.db.NodeEntity
import com.suteny0r.mangledbabyducks.db.TracerouteEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.meshtastic.proto.AdminProtos
import org.meshtastic.proto.AppOnlyProtos
import org.meshtastic.proto.ChannelProtos
import org.meshtastic.proto.ConfigProtos
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.ModuleConfigProtos
import org.meshtastic.proto.Portnums
import org.meshtastic.proto.StoreAndForwardProtos
import org.meshtastic.proto.TelemetryProtos
import java.util.concurrent.ConcurrentHashMap
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
interface PresenceProbe {
    suspend fun isVisible(timeoutMs: Long): Boolean

    /**
     * Suspends until the radio is reachable again, however long that takes. Called once the
     * attempt loop has given up, so that a radio which simply went out of range is picked
     * up when it returns instead of staying disconnected until the user intervenes.
     * Implementations must be cheap enough to leave running indefinitely.
     */
    suspend fun awaitReturn()
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

    /**
     * Whether the stored identity (my_info, our own user row, myNodeNum) belongs to the
     * radio of the CURRENT session. False from the moment a connect starts until that
     * radio's MyNodeInfo has been ingested, because until then every one of those still
     * describes the radio we were talking to before, and the Connect box was naming it.
     */
    private val _identityReady = MutableStateFlow(false)
    val identityReady: StateFlow<Boolean> = _identityReady.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    /**
     * AccessoryManager.loRaRegionPresets: the 2.8 firmware's region -> legal presets map,
     * sent during the want_config handshake. Empty on older firmware. Decoded by the
     * LoRa form; kept raw here so this file needs no UI types.
     */
    private val _regionPresets = MutableStateFlow<MeshProtos.LoRaRegionPresetMap?>(null)
    val regionPresets: StateFlow<MeshProtos.LoRaRegionPresetMap?> = _regionPresets.asStateFlow()

    /**
     * The connected radio's DeviceMetadata (hasWifi, hasEthernet, has_xeddsa, pio_env),
     * which the config forms gate rows on. In memory only: the firmware sends it on every
     * handshake, so it needs no schema and is null until a radio has connected.
     */
    private val _deviceMetadata = MutableStateFlow<MeshProtos.DeviceMetadata?>(null)
    val deviceMetadata: StateFlow<MeshProtos.DeviceMetadata?> = _deviceMetadata.asStateFlow()

    /**
     * MyNodeInfo.device_id of the connected radio as lowercase hex, which keys its backup.
     * Null until a handshake has delivered one, and for radios that report none.
     */
    private val _deviceId = MutableStateFlow<String?>(null)
    val deviceId: StateFlow<String?> = _deviceId.asStateFlow()

    /**
     * AccessoryManager.appliesLoRaConfigWithoutReboot: firmware 2.8 applies every LoRa
     * change live (firmware #9962). Read from the live metadata only, and conservative: no
     * version means assume it may reboot.
     */
    val appliesLoRaConfigWithoutReboot: Boolean
        get() {
            val v = _deviceMetadata.value?.firmwareVersion?.takeIf { it.isNotBlank() } ?: return false
            val have = v.substringBefore('-').substringBefore('+').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            val major = have.getOrElse(0) { 0 }
            val minor = have.getOrElse(1) { 0 }
            return major > 2 || (major == 2 && minor >= 8)
        }

    /**
     * AccessoryManager.refreshNodeDatabaseAfterLoRaChange: on 2.8 a LoRa save does not
     * reboot, so nothing re-reads the config, and the radio's answers about which nodes
     * it hears on the new settings change too. Re-request the config, then the node
     * database once the modem has settled.
     */
    fun refreshAfterLoRaChange() {
        if (_state.value !is RadioState.Subscribed) return
        scope.launch {
            runCatching { send { it.setWantConfigId(MeshProtocol.NONCE_ONLY_CONFIG) } }
            kotlinx.coroutines.delay(2_000)
            if (_state.value is RadioState.Subscribed) {
                // A full dump follows, so arm the same reconcile collector as step 5 of
                // the handshake; the new modem settings change who the radio hears.
                dumpStartedMs = System.currentTimeMillis()
                dumpNodeNums = ConcurrentHashMap.newKeySet()
                runCatching { send { it.setWantConfigId(MeshProtocol.NONCE_ONLY_DB) } }
            }
        }
    }

    /**
     * AccessoryManager.packetsSent / packetsReceived: one tick per ToRadio written and
     * per FromRadio read, for the RX/TX activity lights in the header.
     */
    private val _packetsSent = MutableStateFlow(0)
    val packetsSent: StateFlow<Int> = _packetsSent.asStateFlow()
    private val _packetsReceived = MutableStateFlow(0)
    val packetsReceived: StateFlow<Int> = _packetsReceived.asStateFlow()

    /** Latest BLE link RSSI (Device.rssi on iOS); null off BLE or when the link is down. */
    private val _linkRssi = MutableStateFlow<Int?>(null)
    val linkRssi: StateFlow<Int?> = _linkRssi.asStateFlow()

    /**
     * How many nodes the radio's NodeDB holds: MyNodeInfo.nodedb_count at each handshake,
     * then LocalStats.num_total_nodes from the radio's own telemetry. The platform's node
     * cap itself is not on the wire, but on a mesh larger than the cap the NodeDB sits
     * pinned at it, so this is the effective limit the store mirrors. 0 until something
     * reports one (pre-2.6 firmware never does). Owned by PacketIngest, which both
     * enforces the cap and sees the telemetry.
     */
    val nodeDbCount: StateFlow<Int> get() = ingest.nodeDbCount

    /** Inbound messages worth notifying about (already stored). */
    val incomingMessages = MutableSharedFlow<MessageEntity>(extraBufferCapacity = 64)

    private val configCompleteIds = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    /** When the last NodeInfo of the dump landed, to time the radio's trailing CONFIG_COMPLETE. */
    private var lastNodeInfoAt = 0L
    /**
     * Node nums streamed by an in-flight node DB dump, collected for the store reconcile
     * (PacketIngest.reconcileNodeDb) that its CONFIG_COMPLETE triggers; null outside a dump.
     */
    @Volatile private var dumpNodeNums: MutableSet<Long>? = null
    @Volatile private var dumpStartedMs = 0L

    private var connection: RadioConnection? = null
    private var eventJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    /** Low-power watch for a radio that went out of range; see [watchForReturn]. */
    private var presenceWatchJob: Job? = null
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
    private var lastAddress: String? = null
    private var lastName: String? = null

    val isConnected: Boolean
        get() = _state.value is RadioState.Subscribed || _state.value is RadioState.RetrievingDatabase

    /** True while a connect or reconnect attempt loop owns the radio. */
    val isAttempting: Boolean
        get() = attemptLock.isLocked

    /**
     * The LAN host the app is linked to or reaching for, else null. Anything that opens its
     * own socket to a radio has to skip it: the firmware serves one TCP client, so a second
     * connect takes the slot and the live session gets an EOF.
     *
     * It reads [lastAddress], which [connect] sets before the first attempt, so it covers
     * the handshake as well as a finished link. That is the window that matters: the LAN
     * sweep stole the slot mid-handshake and cost the user a whole failed attempt.
     *
     * A BLE address is a MAC and comes back null rather than a leading octet pair.
     */
    val activeHost: String?
        get() {
            if (!isConnected && !isAttempting) return null
            val host = lastAddress?.substringBeforeLast(':') ?: return null
            return host.takeIf { !it.contains(':') }
        }

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
        address: String? = null,
        factory: () -> RadioConnection,
    ): Boolean {
        if (isConnected || isAttempting) return false
        if (!autoConnectSpent.compareAndSet(false, true)) return false
        connect(name, presence, address, factory)
        return true
    }

    // `factory` is deliberately last: every call site passes it as a trailing lambda.
    /**
     * [address] is what the store records as the radio's identity (my_info.radioAddress).
     *
     * Runs on Default, never on the caller's dispatcher: every caller is a ViewModel on
     * Main, and the handshake's nonce waiter resumes on the dispatcher it was started on.
     * With the main thread busy after a node dump, the DB nonce arrived on an IO worker and
     * the waiter did not get to run for 35 s, which showed as "Retrieving nodes N" sitting
     * at its final count long after the radio had finished. iOS's connect pipeline is off
     * the main actor for the same reason.
     */
    suspend fun connect(
        name: String?,
        presence: PresenceProbe? = null,
        address: String? = null,
        factory: () -> RadioConnection,
    ) = withContext(Dispatchers.Default) {
        val gen = beginRequest()
        _identityReady.value = false
        connectionFactory = factory
        lastName = name
        lastAddress = address
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
                // The scan is a fast path, not a gate. It used to be terminal here, on the
                // reasoning that a radio which is not advertising will not answer a GATT
                // connect either, and that reasoning is wrong for a bonded peer: a Heltec
                // sitting beside the phone, powered up and on the mesh, was invisible to
                // every scan while the app reported "is not in range" and never tried. A
                // connect the user asked for is worth a 5 s timeout to find out.
                absentIsTerminal = false,
                attemptWhenAbsent = true,
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
        presenceWatchJob?.cancel()
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
     *
     * The finally clause is what actually guarantees that. An exception escaping the
     * loop used to leave the UI parked on the progress state this function had just
     * set, with the attempt coroutine dead and nothing left to fire a reconnect: the
     * app then showed "Retrying (attempt 1)" until it was force stopped. Landing on
     * Failed is what puts the Reconnect and Forget buttons back.
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
        /**
         * Whether to go ahead with the GATT connect when the scan found nothing. True for
         * a connect the user asked for: a bonded radio can be perfectly connectable while
         * invisible to a scan, because peripherals commonly switch to directed advertising
         * at their bonded peer, and ESP32 radios do. Observed with a Heltec sitting next to
         * the phone, powered up and on the mesh, that the app refused to even try. False
         * for the background loops, where the scan is there to avoid burning 5 s timeouts
         * behind the user's back.
         */
        attemptWhenAbsent: Boolean = false,
        exhaustedReason: String,
    ) {
        var settled = false
        try {
            for (attempt in 0 until attempts) {
                if (gen != requestGeneration) { settled = true; return }
                val wait = delayFor(attempt)
                if (wait > 0) {
                    _state.value = progressFor(attempt)
                    delay(wait)
                    if (gen != requestGeneration) { settled = true; return }
                }
                // Scan first: a GATT connect to a radio that is not advertising costs a ~5 s
                // timeout and comes back as status 133, which reads like a broken app.
                val presence = presenceFor(attempt)
                if (presence != null) {
                    _state.value = searchingFor(attempt)
                    val visible = presence.isVisible(PRESENCE_TIMEOUT_MS)
                    if (gen != requestGeneration) { settled = true; return }
                    if (!visible) {
                        Log.w(TAG, "${name ?: "Radio"} not advertising (attempt ${attempt + 1}/$attempts)")
                        if (absentIsTerminal) {
                            _state.value = RadioState.Failed("${name ?: "The radio"} is not in range")
                            settled = true
                            return
                        }
                        if (!attemptWhenAbsent) continue
                        Log.i(TAG, "Connecting anyway; a bonded radio can be invisible to a scan")
                    }
                }
                _state.value = progressFor(attempt)
                try {
                    establish(gen, factory(), name)
                    if (attempt > 0) Log.i(TAG, "Connected after ${attempt + 1} attempt(s)")
                    settled = true
                    return
                } catch (e: TimeoutCancellationException) {
                    // withTimeout throws a CancellationException subtype, but this one is
                    // local to the attempt: the connect, MTU, discovery, bonding and
                    // handshake windows all land here. Rethrowing it as if the caller had
                    // been cancelled killed the loop on the first slow attempt, which is
                    // the whole "Retrying (attempt 1)" wedge. It is an attempt failure.
                    Log.w(TAG, "Attempt ${attempt + 1}/$attempts timed out: ${e.message}")
                } catch (e: CancellationException) {
                    settled = true
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Attempt ${attempt + 1}/$attempts failed: ${e.message}")
                }
            }
            if (gen == requestGeneration) _state.value = RadioState.Failed(exhaustedReason)
            settled = true
        } finally {
            // Anything else escaping (including this coroutine being cancelled while a
            // progress state is showing) must still leave the card actionable.
            if (!settled && gen == requestGeneration && _state.value !is RadioState.Failed) {
                _state.value = RadioState.Failed(exhaustedReason)
            }
        }
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

            // A new session's node cap comes from its own MyNodeInfo; the previous
            // radio's must not evict against a store just restored for this one.
            ingest.resetNodeDbMirror()

            // Step 3: wantConfig handshake — the radio streams config then echoes the nonce.
            sendHeartbeat()
            // The drain is a loop that reads FROMRADIO until a zero-length read, and it is
            // launched, not awaited: the waiter below only checks the nonce once request()
            // returns, and after a node dump the radio keeps streaming queued packets for
            // half a minute, so an awaited drain parked the handshake at "Retrieving nodes N"
            // long after CONFIG_COMPLETE had arrived. The doorbell path launches it the same way.
            awaitConfigComplete(MeshProtocol.NONCE_ONLY_CONFIG.toLong(), timeoutMs = 30_000, lost = lost) {
                send { it.setWantConfigId(MeshProtocol.NONCE_ONLY_CONFIG) }
                scope.launch { conn.startDrainPendingPackets() }
            }

            // Step 5: node DB dump under the second nonce. The dump is the radio's whole
            // NodeDB, so its node nums are collected and the completion handler reconciles
            // the store against them (see CONFIG_COMPLETE_ID in processFromRadio).
            nodeCount = 0
            lastNodeInfoAt = 0L
            dumpStartedMs = System.currentTimeMillis()
            dumpNodeNums = ConcurrentHashMap.newKeySet()
            _state.value = RadioState.RetrievingDatabase(0)
            awaitConfigComplete(MeshProtocol.NONCE_ONLY_DB.toLong(), timeoutMs = 120_000, lost = lost) {
                send { it.setWantConfigId(MeshProtocol.NONCE_ONLY_DB) }
                scope.launch { conn.startDrainPendingPackets() }
            }

            // Step 7: set the radio's clock, then we are live. A Disconnect issued
            // mid-handshake must not end with a session declaring itself live.
            if (gen != requestGeneration) throw RadioException("Superseded by a newer request")
            val setTimeStarted = System.currentTimeMillis()
            sendSetTime()
            Log.d(TAG, "setTime took ${System.currentTimeMillis() - setTimeStarted} ms; Subscribed")
            sessionWentLive = true
            _state.value = RadioState.Subscribed

            // Retention: positions (non-latest) and telemetry older than 30 days.
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            runCatching {
                db.positionDao().prune(cutoff)
                db.telemetryDao().prune(cutoff)
                // History whose node row is gone (radio switch, removed node) is noise.
                db.positionDao().pruneOrphans()
                db.telemetryDao().pruneOrphans()
                // Repair the other direction: a user with no node row is invisible in the
                // node list and its detail screen renders empty. Older builds could leave
                // these behind, and the row costs nothing to recreate.
                db.nodeDao().orphanUserNums().forEach { db.nodeDao().upsert(NodeEntity(num = it)) }
            }

            if (conn.requiresPeriodicHeartbeat) startPeriodicHeartbeat()
        } catch (e: Exception) {
            Log.w(TAG, "Establish failed: ${e.message}")
            dumpNodeNums = null
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
                Log.d(TAG, "Nonce $nonce seen by the handshake")
                watchdog.cancel()
            }
        }
    }

    suspend fun disconnect() {
        beginRequest()
        connectionFactory = null
        _state.value = RadioState.Idle
        _identityReady.value = false
        _deviceName.value = null
        _linkRssi.value = null
    }

    private suspend fun handleEvent(source: RadioConnection, event: ConnectionEvent) {
        // A superseded connection keeps emitting while it dies; its events must not
        // touch the live session or start a second reconnect driver.
        if (source !== connection) return
        lastRxMs = System.currentTimeMillis()
        when (event) {
            is ConnectionEvent.Data -> {
                _packetsReceived.value++
                processFromRadio(event.fromRadio)
            }
            is ConnectionEvent.LogMessage -> Log.d(TAG, "radio: ${event.message}")
            is ConnectionEvent.RssiUpdate -> _linkRssi.value = event.rssi
            is ConnectionEvent.Disconnected -> {
                Log.w(TAG, "Link lost (reconnect=${event.shouldReconnect}): ${event.error}")
                _linkRssi.value = null
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
                val idBytes = fromRadio.myInfo.deviceId.toByteArray()
                _deviceId.value = if (idBytes.isEmpty()) null else idBytes.joinToString("") { "%02x".format(it) }
                _myNodeNum.value = ingest.myInfo(fromRadio.myInfo, _deviceName.value, lastAddress, _deviceId.value)
                ingest.setRadioNodeCount(fromRadio.myInfo.nodedbCount)
                _identityReady.value = true
            }
            MeshProtos.FromRadio.PayloadVariantCase.NODE_INFO -> {
                ingest.nodeInfo(fromRadio.nodeInfo, connectedNodeNum = _myNodeNum.value)
                dumpNodeNums?.add(fromRadio.nodeInfo.num.uint())
                nodeCount++
                lastNodeInfoAt = System.currentTimeMillis()
                if (_state.value is RadioState.RetrievingDatabase) {
                    _state.value = RadioState.RetrievingDatabase(nodeCount)
                }
            }
            MeshProtos.FromRadio.PayloadVariantCase.CHANNEL ->
                ingest.channel(fromRadio.channel)
            MeshProtos.FromRadio.PayloadVariantCase.CONFIG -> {
                ingest.config(fromRadio.config)
                if (fromRadio.config.payloadVariantCase == ConfigProtos.Config.PayloadVariantCase.DEVICE) {
                    saveTimeZoneIfEmpty(fromRadio.config.device)
                }
            }
            MeshProtos.FromRadio.PayloadVariantCase.MODULECONFIG ->
                ingest.moduleConfig(fromRadio.moduleConfig)
            MeshProtos.FromRadio.PayloadVariantCase.METADATA -> {
                _deviceMetadata.value = fromRadio.metadata
                ingest.deviceMetadata(fromRadio.metadata)
            }
            MeshProtos.FromRadio.PayloadVariantCase.REGION_PRESETS -> {
                _regionPresets.value = fromRadio.regionPresets
                Log.i(TAG, "Region preset map: ${fromRadio.regionPresets.regionGroupsCount} regions, ${fromRadio.regionPresets.groupsCount} groups")
                fromRadio.regionPresets.regionGroupsList.forEach { rg ->
                    val g = fromRadio.regionPresets.groupsList.getOrNull(rg.groupIndex)
                    Log.d(TAG, "Region ${rg.region}: default ${g?.defaultPreset} licensed=${g?.licensedOnly} presets=${g?.presetsList}")
                }
            }
            MeshProtos.FromRadio.PayloadVariantCase.CONFIG_COMPLETE_ID -> {
                val id = fromRadio.configCompleteId.uint()
                if (id == MeshProtocol.NONCE_ONLY_DB.toLong()) {
                    if (lastNodeInfoAt != 0L) {
                        Log.d(TAG, "Node DB complete: $nodeCount nodes, ${System.currentTimeMillis() - lastNodeInfoAt} ms after the last NodeInfo")
                    }
                    // The dump just delivered the radio's complete NodeDB: anything the
                    // store still holds beyond it (and the protected rows) was evicted by
                    // the radio. Launched so a slow delete never stalls the handshake
                    // waiter below, and runCatching so it can never kill the event loop.
                    val dump = dumpNodeNums
                    dumpNodeNums = null
                    if (dump != null) {
                        // Firmware too old to report nodedb_count still tells us the size
                        // of its NodeDB by streaming it.
                        ingest.setRadioNodeCount(dump.size)
                        val started = dumpStartedMs
                        scope.launch {
                            runCatching { ingest.reconcileNodeDb(dump, _myNodeNum.value, started) }
                                .onFailure { Log.w(TAG, "NodeDB reconcile failed", it) }
                        }
                    }
                }
                configCompleteIds.emit(id)
            }
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
            Portnums.PortNum.NODE_STATUS_APP -> ingest.nodeStatusPacket(packet)
            Portnums.PortNum.POSITION_APP -> ingest.positionPacket(packet)
            Portnums.PortNum.TELEMETRY_APP -> ingest.telemetryPacket(packet, myNum)
            Portnums.PortNum.ADMIN_APP -> ingest.adminResponse(packet)
            Portnums.PortNum.ROUTING_APP -> ingest.routing(packet, myNum)?.let { healPkiFailure(it) }
            Portnums.PortNum.TRACEROUTE_APP -> ingest.traceroute(packet)
            Portnums.PortNum.WAYPOINT_APP -> ingest.waypointPacket(packet)
            else -> Log.d(TAG, "Unhandled port ${packet.decoded.portnum}")
        }
    }

    /**
     * Routing naks on a DM that mean a public key is missing or stale somewhere. What the
     * firmware (meshtastic/firmware master, Router.cpp / ReliableRouter.cpp) actually emits:
     *  - 39 PKI_SEND_FAIL_PUBLIC_KEY: our own radio refused to send because it holds no key
     *    for the destination (every text DM is PKI-encrypted; there is no channel fallback).
     *  - 35 PKI_UNKNOWN_PUBKEY: the peer received our PKI packet but has no key for us at
     *    all. The peer's firmware also sends us its NodeInfo on its own in that case.
     *  - 6 NO_CHANNEL: the peer could not decrypt a packet addressed to it. For a DM that
     *    means it holds a *stale* key for us (or we for it), because the unknown-key case
     *    is reported as 35 instead.
     *  - 34 PKI_FAILED: key requested by the client differs from the radio's, or PKI was
     *    demanded and unavailable.
     */
    private val pkiKeyErrors = setOf(
        MeshProtos.Routing.Error.NO_CHANNEL_VALUE,
        MeshProtos.Routing.Error.PKI_FAILED_VALUE,
        MeshProtos.Routing.Error.PKI_UNKNOWN_PUBKEY_VALUE,
        MeshProtos.Routing.Error.PKI_SEND_FAIL_PUBLIC_KEY_VALUE,
    )
    private val lastKeyExchangeMs = HashMap<Long, Long>()
    @Volatile private var lastKeyBroadcastMs = 0L

    /**
     * Self-heal for stale or missing public keys after a DM nak (see [pkiKeyErrors]).
     * When a node is re-flashed its key pair changes, but every other radio, and this
     * app, keeps the first key it saw (firmware NodeDB.updateUser logs "Public Key
     * mismatch, dropping NodeInfo"). Three repairs, each rate limited:
     *  1. If our radio has no key for the peer (39) but this app does, push it with
     *     add_contact. That is what stock clients do before every DM, and it makes the
     *     user's next send work at once.
     *  2. A direct user-info exchange with the peer. NodeInfo unicasts are never PKI
     *     encrypted by the firmware, so this gets through however stale the keys are;
     *     the peer learns our current key (if its radio has none or its client accepts
     *     a change) and, unless it answered us within the last 12 h, replies with its own.
     *  3. A NodeInfo broadcast for everyone else, the automatic form of Settings >
     *     "Broadcast node info".
     * Only text messages this app stored can trigger it, so the exchange packets cannot
     * recurse. The failed message is not resent; it stays marked failed in the thread.
     */
    private suspend fun healPkiFailure(ack: PacketIngest.AckResult) {
        if (ack.errorReason !in pkiKeyErrors) return
        val message = db.messageDao().get(ack.messageId) ?: return
        val peer = message.toNum ?: return
        val now = System.currentTimeMillis()
        val exchange = synchronized(lastKeyExchangeMs) {
            val last = lastKeyExchangeMs[peer] ?: 0L
            (now - last > KEY_EXCHANGE_COOLDOWN_MS).also { if (it) lastKeyExchangeMs[peer] = now }
        }
        if (!exchange) return
        val peerHex = "!%08x".format(peer)
        Log.i(TAG, "DM to $peerHex naked with error ${ack.errorReason}; repairing keys")
        if (ack.errorReason == MeshProtos.Routing.Error.PKI_SEND_FAIL_PUBLIC_KEY_VALUE) {
            val known = db.userDao().get(peer)?.publicKey?.takeIf { it.size == 32 }
            if (known != null) {
                Log.i(TAG, "Radio has no key for $peerHex; pushing this app's copy with add_contact")
                runCatching { pushContactToRadio(peer, known) }
            }
        }
        // A zero here is "we do not know", not "the default channel": fall through to
        // channelFor rather than re-asking on a channel that has already failed once.
        runCatching { exchangeUserInfo(peer, channel = message.channel.takeIf { it > 0 }) }
        if (now - lastKeyBroadcastMs > KEY_BROADCAST_COOLDOWN_MS) {
            lastKeyBroadcastMs = now
            Log.i(TAG, "Broadcasting node info after key nak")
            runCatching { broadcastNodeInfo() }
        }
    }

    /**
     * Hand the radio a node's user record and public key (admin add_contact, firmware
     * NodeDB.addFromContact). The firmware overwrites the stored user, so this is the one
     * way to replace a key its first-wins NodeInfo path would refuse. Side effect in the
     * firmware: the node is marked favorite so it is not evicted; that is undone here
     * unless the user had favorited it, so the radio's favorites match the app's.
     */
    private suspend fun pushContactToRadio(nodeNum: Long, key: ByteArray): Boolean {
        val user = db.userDao().get(nodeNum) ?: return false
        val contact = AdminProtos.SharedContact.newBuilder()
            .setNodeNum(nodeNum.toInt())
            .setUser(
                MeshProtos.User.newBuilder()
                    .setId(user.userId ?: "!%08x".format(nodeNum))
                    .setLongName(user.longName ?: "")
                    .setShortName(user.shortName ?: "")
                    .setHwModelValue(user.hwModelId)
                    .setRoleValue(user.role)
                    .setIsLicensed(user.isLicensed)
                    .setPublicKey(ByteString.copyFrom(key)),
            )
            .setManuallyVerified(false)
            .build()
        val added = sendAdmin { it.setAddContact(contact) }
        if (added && db.nodeDao().get(nodeNum)?.favorite != true) {
            sendAdmin { it.setRemoveFavoriteNode(nodeNum.toInt()) }
        }
        return added
    }

    /**
     * Adopt a node's changed public key after the user confirmed it. Both key stores are
     * first-wins: this app's users row (PacketIngest.upsertUser) and the radio's own
     * NodeDB, whose firmware logs "Public Key mismatch, dropping NodeInfo" and keeps the
     * old key. So: promote the refused key locally; make the radio forget the node
     * (remove_by_nodenum on ourselves) so no stale key or verified flag survives; hand it
     * the new key with add_contact so DMs work immediately; then ask the node for a fresh
     * NodeInfo, which the radio now accepts because the keys agree.
     */
    suspend fun acceptNewKey(nodeNum: Long): Boolean {
        val myNum = _myNodeNum.value
        if (myNum == 0L) return false
        if (db.userDao().acceptNewKey(nodeNum) == 0) return false
        val key = db.userDao().get(nodeNum)?.publicKey ?: return false
        val forgot = sendAdmin { it.setRemoveByNodenum(nodeNum.toInt()) }
        val pushed = pushContactToRadio(nodeNum, key)
        val asked = exchangeUserInfo(nodeNum)
        Log.i(TAG, "Accepted new key for ${"!%08x".format(nodeNum)}: forget=$forgot push=$pushed exchange=$asked")
        return forgot && pushed
    }

    /**
     * Send a text message. Port of AccessoryManager.sendMessage: the MessageEntity is
     * stored optimistically before the radio write, acks arrive later via ROUTING_APP.
     */
    suspend fun sendTextMessage(
        text: String,
        toNum: Long? = null,
        /**
         * The channel to send on. Every channel-thread caller passes its index; a DM
         * caller leaves it null and [channelFor] decides, because a peer met on a private
         * channel cannot read anything sent on channel 0.
         */
        channel: Int? = null,
        replyId: Long = 0,
        isEmoji: Boolean = false,
    ): Boolean {
        val myNum = _myNodeNum.value
        if (myNum == 0L) return false
        val onChannel = channel ?: toNum?.let { channelFor(it) } ?: 0
        // Curly-quote normalization from the iOS sender.
        val normalized = text.replace('’', '\'').replace('“', '"').replace('”', '"')
        val payload = normalized.encodeToByteArray()
        if (payload.size > MeshProtocol.MAX_TEXT_BYTES) return false

        val messageId = Random.nextLong(255L, 0xFFFFFFFFL)
        // AccessoryManager+ToRadio: our own DM row is marked encrypted when we hold the
        // recipient's key, which is what the firmware will use to encrypt it.
        val pkiEncrypted = toNum != null && db.userDao().get(toNum)?.pkiEncrypted == true
        db.messageDao().insertIgnore(
            MessageEntity(
                messageId = messageId,
                fromNum = myNum,
                toNum = toNum,
                channel = onChannel,
                portNum = Portnums.PortNum.TEXT_MESSAGE_APP_VALUE,
                payload = normalized,
                timestamp = System.currentTimeMillis(),
                read = true,
                isEmoji = isEmoji,
                replyId = replyId,
                pkiEncrypted = pkiEncrypted,
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
            .setChannel(onChannel)
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
     * AccessoryManager+ToRadio.swift's saveXModuleConfig methods (e.g.
     * saveExternalNotificationModuleConfig): a single standalone set_module_config admin
     * field, no edit transaction. Unlike the whole-section setConfig above, none of the
     * Swift per-module config saves wrap begin/commit; the radio still saves and reboots
     * on this one field, it just is not batched with any other write.
     */
    suspend fun setModuleConfig(config: ModuleConfigProtos.ModuleConfig): Boolean =
        sendAdmin { it.setSetModuleConfig(config) }

    /**
     * AccessoryManager+FromRadio.swift "Handle Timezone": a DeviceConfig with an empty
     * tzdef (a fresh radio, or one whose NodeDB reset wiped it) gets the phone's own POSIX
     * timezone string pushed back as a standalone setConfig, no edit transaction, matching
     * saveTimeZone in AccessoryManager+ToRadio.swift. Fired on the manager's scope so a slow
     * ack does not stall the rest of the handshake, same as the Swift `Task { ... }`.
     */
    private fun saveTimeZoneIfEmpty(device: ConfigProtos.Config.DeviceConfig) {
        if (device.tzdef.isNotEmpty()) return
        val tzdef = runCatching { PosixTimeZone.current() }.getOrNull() ?: return
        val config = ConfigProtos.Config.newBuilder()
            .setDevice(device.toBuilder().setTzdef(tzdef).build())
            .build()
        scope.launch {
            Log.i(TAG, "Device Config timezone was empty, setting timezone to $tzdef")
            sendAdmin { it.setSetConfig(config) }
        }
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

    /**
     * AccessoryManager.saveChannel: one channel written on its own, wrapped in the same
     * begin/commit the other config writes use so the radio stores it and reboots once.
     * A role of DISABLED is how the firmware is told to drop a secondary channel.
     */
    suspend fun saveChannel(channel: ChannelProtos.Channel): Boolean {
        if (!sendAdmin { it.setBeginEditSettings(true) }) return false
        if (!sendAdmin { it.setSetChannel(channel) }) return false
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
     * Build and send a mesh packet carrying a decoded portnum payload. Covers the
     * non-admin outbound calls: S&F client-history, user-info exchange, position,
     * telemetry. Port of the per-method sendPosition / requestS&F / exchangeUserInfo /
     * sendLocalStatsRequest builders in AccessoryManager+ToRadio.swift /
     * AccessoryManager+Position.swift.
     */
    private suspend fun sendPayload(
        toNum: Int,
        portnum: Portnums.PortNum,
        reliable: Boolean,
        wantAck: Boolean,
        wantResponse: Boolean,
        channel: Int,
        hopsAway: Int,
        data: (MeshProtos.Data.Builder) -> MeshProtos.Data.Builder,
    ): Boolean {
        val myNum = _myNodeNum.value
        if (myNum == 0L) return false
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setId(Random.nextLong(255L, 0xFFFFFFFFL).toInt())
            .setTo(toNum)
            .setFrom(myNum.toInt())
            .setChannel(channel)
            .setWantAck(wantAck)
            .apply { if (reliable) setPriority(MeshProtos.MeshPacket.Priority.RELIABLE) }
            .apply { if (hopsAway > 0) setHopLimit(hopsAway) }
            .setDecoded(
                (data(MeshProtos.Data.newBuilder().setPortnum(portnum).setWantResponse(wantResponse)))
                    .build()
            )
            .build()
        return runCatching { send { it.setPacket(packet) } }.isSuccess
    }

    /**
     * Send an AdminMessage on a single mesh packet, reliable + ack'd. Remote admin
     * (targeting a node other than ours) carries wantResponse so the radio's
     * AdminMessage reply rides back through the ADMIN_APP dispatch. Port of
     * sendShutdown / sendReboot / removeNode / requestDeviceMetadata /
     * requestStoreAndForwardConfig in AccessoryManager+ToRadio.swift.
     *
     * Session-passkey note: iOS tags cross-node admin with the destination's
     * PKI-issued session passkey. This app does not run that PKI handshake, so
     * the field is left unset (proto3 default == empty bytes == iOS fallback).
     */
    private suspend fun sendRemoteAdmin(
        toNum: Int,
        wantResponse: Boolean,
        build: (AdminProtos.AdminMessage.Builder) -> AdminProtos.AdminMessage.Builder,
    ): Boolean {
        val myNum = _myNodeNum.value
        if (myNum == 0L) return false
        val admin = (build(AdminProtos.AdminMessage.newBuilder())).build()
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.ADMIN_APP)
            .setWantResponse(wantResponse)
            .setPayload(admin.toByteString())
            .build()
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setId(Random.nextLong(255L, 0xFFFFFFFFL).toInt())
            .setTo(toNum)
            .setFrom(myNum.toInt())
            .setWantAck(true)
            .setPriority(MeshProtos.MeshPacket.Priority.RELIABLE)
            .setDecoded(data)
            .build()
        return runCatching { send { it.setPacket(packet) } }.isSuccess
    }

    /**
     * Ask a node to power off after 5 s (admin shutdown_seconds). Mirrors iOS
     * sendShutdown. For the connected radio, targetNum = myNodeNum — same call
     * either way.
     */
    suspend fun sendNodeShutdown(targetNum: Long): Boolean =
        sendRemoteAdmin(targetNum.toInt(), wantResponse = false) { it.setShutdownSeconds(5) }

    /**
     * Ask a node to reboot after 5 s (admin reboot_seconds). Mirrors iOS sendReboot.
     */
    suspend fun sendNodeReboot(targetNum: Long): Boolean =
        sendRemoteAdmin(targetNum.toInt(), wantResponse = false) { it.setRebootSeconds(5) }

    /** AccessoryManager.setFixedPosition: the radio stores this position and stops using its GPS. */
    suspend fun setFixedPosition(latitudeI: Int, longitudeI: Int, altitude: Int): Boolean =
        sendAdmin {
            it.setSetFixedPosition(
                MeshProtos.Position.newBuilder()
                    .setLatitudeI(latitudeI)
                    .setLongitudeI(longitudeI)
                    .setAltitude(altitude)
                    .setTime((System.currentTimeMillis() / 1000).toInt())
                    .build()
            )
        }

    /** AccessoryManager.removeFixedPosition. */
    suspend fun removeFixedPosition(): Boolean = sendAdmin { it.setRemoveFixedPosition(true) }

    /** AccessoryManager.sendNodeDBReset: the radio forgets every node it has heard. */
    suspend fun sendNodeDbReset(): Boolean = sendAdmin { it.setNodedbReset(true) }

    /**
     * AccessoryManager.sendFactoryReset: config only, or config plus keys and BLE bonds
     * (factory_reset_device). The radio reboots and this link ends either way.
     */
    suspend fun sendFactoryReset(resetDevice: Boolean): Boolean =
        sendAdmin { if (resetDevice) it.setFactoryResetDevice(1) else it.setFactoryResetConfig(1) }

    /**
     * Ask a node to forget another node from its node database (admin
     * remove_by_nodenum) and delete our local records. Mirrors iOS removeNode,
     * which also drops the rows locally on send success.
     */
    suspend fun removeNode(targetNum: Long, removedNodeNum: Long): Boolean {
        val sent = sendRemoteAdmin(
            toNum = targetNum.toInt(),
            wantResponse = false,
        ) { it.setRemoveByNodenum(removedNodeNum.toInt()) }
        if (sent) {
            db.nodeDao().delete(removedNodeNum)
            db.userDao().delete(removedNodeNum)
            db.positionDao().deleteFor(removedNodeNum)
            db.telemetryDao().deleteFor(removedNodeNum)
        }
        return sent
    }

    /**
     * Ask a node to replay its Store & Forward history window (S&F rr=CLIENT_HISTORY).
     * Mirrors iOS requestStoreAndForwardClientHistory. This app does not persist a
     * per-node S&F config (iOS does), so the history window and last-request index
     * fall back to the same defaults iOS uses for an unconfigured node.
     */
    suspend fun requestStoreAndForwardClientHistory(targetNum: Long, channel: Int): Boolean {
        val sf = StoreAndForwardProtos.StoreAndForward.newBuilder()
            .setRr(StoreAndForwardProtos.StoreAndForward.RequestResponse.CLIENT_HISTORY)
            .setHistory(
                StoreAndForwardProtos.StoreAndForward.History.newBuilder()
                    .setWindow(120)
                    .setLastRequest(0),
            )
            .build()
        return sendPayload(
            toNum = targetNum.toInt(),
            portnum = Portnums.PortNum.STORE_FORWARD_APP,
            reliable = true,
            wantAck = true,
            wantResponse = true,
            channel = channel,
            hopsAway = 0,
        ) { it.setPayload(sf.toByteString()) }
    }

    /**
     * Which channel a packet aimed at one node must go out on.
     *
     * Channel 0 was hardcoded at every node-targeted action, and for a node met on a
     * private channel that is the wrong radio entirely: the peer holds no key for the
     * default channel, so the request is noise to it and nothing ever comes back. A node
     * that had only ever spoken on a private channel could be asked for its user info,
     * sent a position and asked for one back, and stay nameless indefinitely, because
     * none of the three was ever transmitted anywhere it could hear.
     *
     * The node row's own channel is the radio's answer and wins when it has one, but it
     * is only filled in by a NodeInfo, which is exactly what is missing in that case.
     * The messages are the fallback: a conversation is proof of a channel that works.
     */
    suspend fun channelFor(nodeNum: Long): Int =
        db.nodeDao().get(nodeNum)?.channel?.takeIf { it > 0 }
            ?: db.messageDao().lastChannelWith(nodeNum)?.takeIf { it > 0 }
            ?: 0

    /**
     * Ask a node to broadcast its identity to the mesh with a User info exchange
     * request (admin request to exchange). Mirrors iOS exchangeUserInfo. Target
     * channel comes from the destination node's row, resolved by [channelFor] when the
     * caller does not already know it.
     */
    suspend fun exchangeUserInfo(targetNum: Long, channel: Int? = null): Boolean {
        val myNum = _myNodeNum.value
        if (myNum == 0L) return false
        val me = db.userDao().get(myNum) ?: return false
        val user = MeshProtos.User.newBuilder()
            .setId(me.userId ?: "!%08x".format(myNum))
            .setLongName(me.longName ?: "")
            .setShortName(me.shortName ?: "")
            .apply { me.publicKey?.let { setPublicKey(ByteString.copyFrom(it)) } }
            .build()
        return sendPayload(
            toNum = targetNum.toInt(),
            portnum = Portnums.PortNum.NODEINFO_APP,
            reliable = true,
            wantAck = true,
            wantResponse = true,
            channel = channel ?: channelFor(targetNum),
            hopsAway = 0,
        ) { it.setPayload(user.toByteString()) }
    }

    /**
     * Send the phone's current GPS fix as a POSITION_APP packet to a node. Source is
     * the same LocationManager the periodic LocationSharer already listens on; the
     * fix here is whatever that last returned. Mirrors iOS sendPosition.
     */
    suspend fun sendDestPosition(
        toNum: Long,
        latitudeI: Int,
        longitudeI: Int,
        altitude: Int,
        /** Null resolves to the channel the node is actually reachable on; see [channelFor]. */
        channel: Int? = null,
        wantResponse: Boolean = true,
    ): Boolean {
        val position = MeshProtos.Position.newBuilder()
            .setLatitudeI(latitudeI)
            .setLongitudeI(longitudeI)
            .setAltitude(altitude)
            .setTime((System.currentTimeMillis() / 1000).toInt())
            .setTimestamp((System.currentTimeMillis() / 1000).toInt())
            .setLocationSource(MeshProtos.Position.LocSource.LOC_EXTERNAL)
            .build()
        return sendPayload(
            toNum = toNum.toInt(),
            portnum = Portnums.PortNum.POSITION_APP,
            reliable = false,
            wantAck = false,
            wantResponse = wantResponse,
            channel = channel ?: channelFor(toNum),
            hopsAway = 0,
        ) { it.setPayload(position.toByteString()) }
    }

    /**
     * Ask a node for its local mesh statistics (Telemetry.localStats, empty request
     * payload). Mirrors iOS sendLocalStatsRequest. When the destination carries a
     * 32-byte public key (PKI-capable firmware), tag the packet with that key so
     * the firmware will run it under PKI.
     */
    suspend fun sendLocalStatsRequest(targetNum: Long, destinationPublicKey: ByteArray?): Boolean {
        val telemetry = TelemetryProtos.Telemetry.newBuilder()
            .setLocalStats(TelemetryProtos.LocalStats.newBuilder().build())
            .build()
        val wantPki = destinationPublicKey?.size == 32
        val myNum = _myNodeNum.value
        if (myNum == 0L) return false
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.TELEMETRY_APP)
            .setWantResponse(true)
            .setPayload(telemetry.toByteString())
            .build()
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setId(Random.nextLong(255L, 0xFFFFFFFFL).toInt())
            .setTo(targetNum.toInt())
            .setFrom(myNum.toInt())
            .setWantAck(true)
            .apply {
                if (wantPki && destinationPublicKey != null) {
                    setPublicKey(ByteString.copyFrom(destinationPublicKey))
                    setPkiEncrypted(true)
                }
            }
            .setDecoded(data)
            .build()
        return runCatching { send { it.setPacket(packet) } }.isSuccess
    }

    /**
     * Ask a node for its DeviceMetadata (firmware version, min app version, etc).
     * The response rides back through the ADMIN_APP ingest (PacketIngest.adminResponse).
     * Mirrors iOS requestDeviceMetadata.
     */
    suspend fun requestDeviceMetadata(targetNum: Long): Boolean =
        sendRemoteAdmin(targetNum.toInt(), wantResponse = true) { it.setGetDeviceMetadataRequest(true) }

    /**
     * Ask a node for its Store & Forward ModuleConfig section (admin
     * get_module_config_request=STOREFORWARD_CONFIG). Response rides back through
     * ADMIN_APP ingest as a ModuleConfig row. Mirrors iOS
     * requestStoreAndForwardConfig.
     */
    suspend fun requestStoreAndForwardConfig(targetNum: Long): Boolean =
        sendRemoteAdmin(targetNum.toInt(), wantResponse = true) {
            it.setGetModuleConfigRequest(
                AdminProtos.AdminMessage.ModuleConfigType.STOREFORWARD_CONFIG,
            )
        }

    /**
     * Rename this radio's owner via AdminMessage.set_owner, with an optimistic
     * local user update (the radio re-broadcasts NodeInfo on its own schedule).
     *
     * [unmessagable] is `User.is_unmessagable` (UserConfig.swift's Unmessagable toggle),
     * the flag that tells other clients not to offer a DM to an unattended node. It is
     * carried here because set_owner replaces the owner record: sending only the two
     * names, as this did, writes the flag away rather than leaving it alone. Firmware
     * sets it when the role becomes an infrastructure one and does not clear it when the
     * role changes back, so an explicit way to clear it is the only way out.
     */
    suspend fun setOwner(
        longName: String,
        shortName: String,
        unmessagable: Boolean = false,
    ): Boolean {
        val myNum = _myNodeNum.value
        if (myNum == 0L) return false
        val me = db.userDao().get(myNum)
        val owner = MeshProtos.User.newBuilder()
            .setLongName(longName.take(39))
            .setShortName(shortName.take(4))
            .setIsUnmessagable(unmessagable)
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
            db.userDao().upsert(
                me.copy(
                    longName = longName.take(39),
                    shortName = shortName.take(4),
                    unmessagable = unmessagable,
                )
            )
        }
        return sent
    }

    /**
     * Turn on licensed-operator (ham) mode via AdminMessage.set_ham_mode. Port of
     * UserConfig.swift's `saveLicensedUser`, which sends HamParameters rather than a
     * User: the firmware sets the node name from the call sign, overrides frequency,
     * duty cycle and TX power, and **disables encryption**, so this is deliberately a
     * separate call from [setOwner] and not a flag on it.
     *
     * The radio saves and reboots, as it does for any config write.
     */
    suspend fun setHamMode(
        callSign: String,
        longName: String,
        shortName: String,
        txPower: Int,
        frequency: Float,
    ): Boolean {
        val myNum = _myNodeNum.value
        if (myNum == 0L) return false
        if (callSign.isBlank()) return false
        val ham = AdminProtos.HamParameters.newBuilder()
            .setCallSign(callSign.take(8))
            .setLongName(longName.take(39))
            .setShortName(shortName.take(4))
            .setTxPower(txPower)
            .setFrequency(frequency)
            .build()
        val sent = runCatching { sendAdmin { it.setSetHamMode(ham) } }.getOrDefault(false)
        Log.i(TAG, "Ham mode requested for $callSign: sent=$sent")
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
        // A live drop supersedes any watch left over from an earlier one.
        presenceWatchJob?.cancel()
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
            // The attempts cover a radio rebooting, not a radio carried out of range. Once
            // they run out, keep a cheap watch on the advertisement so returning to range
            // reconnects on its own. Without this the session was over for good: no link
            // means no further Disconnected event to schedule from, and autoConnect has
            // already spent its one attempt for the life of the process, which the
            // foreground service can keep alive for days.
            watchForReturn(gen)
        }
    }

    /**
     * Wait for a radio that went out of range to advertise again, then reconnect.
     *
     * Deliberately not a retry timer: nothing is attempted until the radio is actually
     * visible, so a radio that is switched off costs a duty-cycled scan and no GATT
     * traffic at all. The attempt-loop invariant still holds, because this takes
     * [attemptLock] like every other caller and abandons the whole thing if a newer
     * request has bumped the generation.
     */
    private fun watchForReturn(gen: Int) {
        val probe = lastPresence ?: return
        val factory = connectionFactory ?: return
        val name = lastName
        presenceWatchJob?.cancel()
        presenceWatchJob = scope.launch {
            // A loop, not a recursive call: re-entering this function from inside the job
            // would cancel the very job it is running on, because presenceWatchJob still
            // points at it.
            while (gen == requestGeneration && !isConnected) {
                Log.i(TAG, "Watching for ${name ?: "the radio"} to come back into range")
                val seen = runCatching { probe.awaitReturn() }
                    .onFailure { Log.w(TAG, "Presence watch ended: ${it.message}") }
                    .isSuccess
                if (!seen) return@launch
                if (gen != requestGeneration || isConnected || isAttempting) return@launch
                Log.i(TAG, "${name ?: "The radio"} is advertising again; reconnecting")
                attemptLock.withLock {
                    runAttempts(
                        gen = gen,
                        factory = factory,
                        name = name,
                        attempts = MAX_RECONNECT_ATTEMPTS,
                        delayFor = { attempt -> if (attempt == 0) 0L else RECONNECT_DELAY_MS * (attempt + 1) },
                        progressFor = { attempt -> RadioState.Reconnecting(attempt + 1) },
                        searchingFor = { attempt -> RadioState.Searching(attempt + 1, MAX_RECONNECT_ATTEMPTS) },
                        // It was advertising a moment ago, so go straight at it on the first try.
                        presenceFor = { attempt -> if (attempt == 0) null else lastPresence },
                        absentIsTerminal = false,
                        exhaustedReason = "Connection lost",
                    )
                }
                // It advertised but would not complete a connect. Back to watching rather
                // than leaving the session dead, after a pause so a radio that advertises
                // and then refuses every connect cannot spin this loop.
                if (gen == requestGeneration && !isConnected) delay(WATCH_RETRY_GAP_MS)
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
        _packetsSent.value++
    }

    companion object {
        private const val TAG = "RadioManager"
        /** Per-peer floor between automatic user-info exchanges after a PKI nak. */
        private const val KEY_EXCHANGE_COOLDOWN_MS = 5 * 60_000L
        /** Floor between automatic NodeInfo broadcasts; the whole mesh hears these. */
        private const val KEY_BROADCAST_COOLDOWN_MS = 15 * 60_000L
        private const val CONNECT_ATTEMPTS = 3
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val RECONNECT_DELAY_MS = 2_000L
        /** Pause before re-watching a radio that advertised but would not finish a connect. */
        private const val WATCH_RETRY_GAP_MS = 30_000L
        /** How long to wait for a known radio's advertisement before giving up on it. */
        private const val PRESENCE_TIMEOUT_MS = 6_000L
        private const val HEARTBEAT_TIMEOUT_MS = 3 * MeshProtocol.HEARTBEAT_INTERVAL_MS
        private const val RETENTION_MS = 30L * 24 * 3600_000
    }
}
