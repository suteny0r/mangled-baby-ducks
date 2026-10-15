package com.suteny0r.mangledbabyducks.radio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import org.meshtastic.proto.MeshProtos

/**
 * GATT link to a Meshtastic radio. Port of BLEConnection.swift.
 *
 * Wire contract: one GATT operation is one protobuf message. ToRadio is written raw to
 * TORADIO; FROMNUM notifications are a doorbell to drain FROMRADIO with reads until a
 * zero-length read. Unlike iOS, Android must request the MTU explicitly (512).
 */
@SuppressLint("MissingPermission")
class BleConnection(
    private val context: Context,
    private val device: BluetoothDevice,
) : RadioConnection {

    // GATT failures surface as exceptions in these coroutines (e.g. a read that
    // dies when the radio reboots mid-drain); they must never crash the process.
    private val crashGuard = CoroutineExceptionHandler { _, e ->
        Log.w(TAG, "BLE coroutine failed", e)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard)
    private val eventFlow = MutableSharedFlow<ConnectionEvent>(
        extraBufferCapacity = 4096,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<ConnectionEvent> = eventFlow
    override val requiresPeriodicHeartbeat = false

    private var gatt: BluetoothGatt? = null
    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null
    private var fromNum: BluetoothGattCharacteristic? = null

    // One GATT operation at a time; each completes via its deferred.
    private val opMutex = Mutex()
    private var pendingConnect: CompletableDeferred<Unit>? = null
    /** Completed by onConnectionStateChange(STATE_DISCONNECTED); see [disconnect]. */
    private var pendingDisconnect: CompletableDeferred<Unit>? = null
    private var pendingMtu: CompletableDeferred<Int>? = null
    private var pendingDiscovery: CompletableDeferred<Unit>? = null
    private var pendingRead: CompletableDeferred<ByteArray>? = null
    private var pendingWrite: CompletableDeferred<Int>? = null
    private var pendingDescriptorWrite: CompletableDeferred<Int>? = null
    private var pendingBond: CompletableDeferred<Unit>? = null

    @Volatile private var closed = false
    @Volatile private var needsDrain = false
    @Volatile private var isDraining = false
    /** Whether the stack ever reported STATE_CONNECTED, which decides how [disconnect] tears down. */
    @Volatile private var everConnected = false

    // Android invalidates GATT handles silently when the adapter turns off — no
    // onConnectionStateChange ever fires — so adapter state must be watched directly.
    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                if (!closed) {
                    Log.w(TAG, "Bluetooth adapter turned off; dropping link")
                    failAllPending("Bluetooth turned off")
                    gatt?.let {
                        runCatching { it.close() }
                        Log.i(TAG, "GATT client closed (live clients: ${liveClients.decrementAndGet()})")
                    }
                    gatt = null
                    // The handles are already void, so there is nothing left to tear down
                    // and no disconnect to await; marking it closed is what stops the RSSI
                    // poll loop, which otherwise spins for the life of the process.
                    closed = true
                    eventFlow.tryEmit(
                        ConnectionEvent.Disconnected(shouldReconnect = true, error = "Bluetooth turned off")
                    )
                }
            }
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val changed: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            if (changed?.address != device.address) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                BluetoothDevice.BOND_BONDED -> pendingBond?.complete(Unit)
                BluetoothDevice.BOND_NONE -> pendingBond?.completeExceptionally(
                    RadioException("Pairing rejected or removed")
                )
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                    everConnected = true
                    pendingConnect?.complete(Unit)
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    val err = if (status != BluetoothGatt.GATT_SUCCESS) "GATT status $status" else null
                    // Releases disconnect()'s wait before it calls close().
                    pendingDisconnect?.complete(Unit)
                    pendingConnect?.completeExceptionally(RadioException(err ?: "Disconnected"))
                    failAllPending(err ?: "Disconnected")
                    if (!closed) {
                        // Any disconnect the app did not ask for is worth reconnecting:
                        // radio reboots (config saves!) arrive with varying statuses,
                        // including 0. Deliberate disconnects set `closed` and never
                        // reach this branch.
                        eventFlow.tryEmit(ConnectionEvent.Disconnected(true, err))
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            // Completed normally whatever the status: a refused MTU negotiation is not a
            // reason to drop a link that is already up. ESP32 radios (Heltec) answer the
            // central's request with 6 (GATT_REQUEST_NOT_SUPPORTED) when the peripheral
            // got its own MTU exchange in first, which is a race, so the same radio fails
            // only sometimes. nRF52 radios (RAK) never do it, which is why one of the
            // tester's two radios worked and the other did not.
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "MTU request refused (status $status); continuing at $mtu")
            }
            pendingMtu?.complete(mtu)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) pendingDiscovery?.complete(Unit)
            else pendingDiscovery?.completeExceptionally(RadioException("Service discovery failed: $status"))
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            completeRead(ch.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            completeRead(value, status)
        }

        private fun completeRead(value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) pendingRead?.complete(value)
            else pendingRead?.completeExceptionally(RadioException("Read failed: $status"))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingDescriptorWrite?.complete(status)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            onChanged(ch, ch.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onChanged(ch, value)
        }

        private fun onChanged(ch: BluetoothGattCharacteristic, value: ByteArray) {
            when (ch.uuid) {
                MeshProtocol.FROMNUM_UUID -> scope.launch { startDrainPendingPackets() }
                MeshProtocol.LOGRADIO_UUID -> {
                    runCatching { MeshProtos.LogRecord.parseFrom(value) }
                        .onSuccess { eventFlow.tryEmit(ConnectionEvent.LogMessage(it.message)) }
                }
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) eventFlow.tryEmit(ConnectionEvent.RssiUpdate(rssi))
        }
    }

    override suspend fun connect() {
        context.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        context.registerReceiver(adapterReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        val connectDeferred = CompletableDeferred<Unit>()
        pendingConnect = connectDeferred
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            // Null when the adapter is off or still restarting, which is exactly the state
            // right after a Bluetooth stack crash. No client was registered and no callback
            // will ever fire, so failing now beats burning the whole connect window below.
            ?: throw RadioException("Bluetooth is unavailable")
        // Instrumentation for the connect-then-fail-forever reports: this must sit at 1
        // for a live session and 0 when idle. A number that climbs across reconnects is a
        // leaked GATT client, and the device is on its way to refusing all LE connections.
        Log.i(TAG, "connectGatt ${device.address} (live clients: ${liveClients.incrementAndGet()})")
        // This window is shorter than the stack's own direct-connect timeout, so a timed
        // out attempt is one the stack is still working on. That is only safe because
        // disconnect() now cancels it explicitly before releasing the client; without that
        // the next attempt stacks a second connect to the same address on top of it.
        withTimeout(CONNECT_TIMEOUT_MS) { connectDeferred.await() }

        val g = gatt ?: throw RadioException("GATT gone")

        val mtuDeferred = CompletableDeferred<Int>()
        pendingMtu = mtuDeferred
        g.requestMtu(512)
        // A radio that never answers the request is not a radio we have to give up on
        // either; Android does long reads and writes on its own, so a smaller MTU costs
        // round trips and nothing else. A genuine link drop still aborts here, because
        // failAllPending completes this deferred exceptionally.
        val mtu = withTimeoutOrNull(MTU_TIMEOUT_MS) { mtuDeferred.await() }
        pendingMtu = null
        if (mtu == null) Log.w(TAG, "No MTU response in ${MTU_TIMEOUT_MS}ms; continuing at the default")
        else Log.i(TAG, "MTU $mtu")

        val discovery = CompletableDeferred<Unit>()
        pendingDiscovery = discovery
        g.discoverServices()
        withTimeout(10_000) { discovery.await() }

        val service = g.getService(MeshProtocol.SERVICE_UUID)
            ?: throw RadioException("Meshtastic service not found")
        toRadio = service.getCharacteristic(MeshProtocol.TORADIO_UUID)
        fromRadio = service.getCharacteristic(MeshProtocol.FROMRADIO_UUID)
        fromNum = service.getCharacteristic(MeshProtocol.FROMNUM_UUID)
        if (toRadio == null || fromRadio == null || fromNum == null) {
            throw RadioException("Required characteristics missing")
        }

        // Subscribing to FROMNUM is the bonding gate: on a fresh radio the CCCD write
        // fails with insufficient auth/enc until the user confirms the pairing PIN.
        enableNotifyWithBonding(fromNum!!)
        service.getCharacteristic(MeshProtocol.LOGRADIO_UUID)?.let {
            runCatching { enableNotify(it) }
        }
        // Launched, not awaited: the loop runs until the radio returns a zero-length read
        // and a radio that keeps streaming has no obligation to stop, so awaiting it was
        // the one path through connect() with no ceiling on it. Launching also keeps the
        // only CancellationException the drain can see a real one, which is what lets it
        // read a timed-out read as a dead link. Same reasoning as the handshake's drains.
        scope.launch { startDrainPendingPackets() }
        // Link quality for the Connect box's signal bars; the result arrives as an
        // RssiUpdate event from onReadRemoteRssi. It doubles as the link's dead-man
        // switch, because it is the only thing that keeps running on an idle link.
        scope.launch {
            var rejects = 0
            while (!closed) {
                val outcome = runCatching { gatt?.readRemoteRssi() }
                when {
                    // DeadObjectException: the Bluetooth process itself is gone. Samsung's
                    // airplane-mode path calls the private disableRadio(), which kills it
                    // without an ACTION_STATE_CHANGED we ever receive, so neither
                    // onConnectionStateChange nor adapterReceiver fires. Observed on the
                    // Note 20 Ultra: the card sat on "Subscribed" with full signal bars
                    // while every GATT call had been throwing for minutes.
                    outcome.isFailure -> {
                        declareLinkLost("Bluetooth went away (${outcome.exceptionOrNull()?.javaClass?.simpleName})")
                        return@launch
                    }
                    outcome.getOrNull() == true -> rejects = 0
                    // A single false is ordinary: the stack rejects an RSSI read while
                    // another GATT operation is in flight. A run of them is not.
                    else -> if (++rejects >= RSSI_REJECT_LIMIT) {
                        declareLinkLost("Link unresponsive")
                        return@launch
                    }
                }
                delay(RSSI_POLL_MS)
            }
        }
    }

    /**
     * Report a link that died without the stack telling us. Everything else that notices
     * a dead link routes through here so there is one place that decides, and the `closed`
     * flag makes a second caller a no-op.
     */
    private fun declareLinkLost(reason: String) {
        if (closed) return
        closed = true
        Log.w(TAG, "$reason; treating the link as lost")
        failAllPending(reason)
        eventFlow.tryEmit(ConnectionEvent.Disconnected(shouldReconnect = true, error = reason))
    }

    private suspend fun enableNotifyWithBonding(ch: BluetoothGattCharacteristic) {
        try {
            enableNotify(ch)
        } catch (e: GattStatusException) {
            // 5 = INSUFFICIENT_AUTHENTICATION, 15 = INSUFFICIENT_ENCRYPTION, 137 = AUTH_FAIL
            if (e.status !in intArrayOf(5, 15, 137)) throw e
            Log.i(TAG, "Bonding required (status ${e.status}); creating bond")
            val bond = CompletableDeferred<Unit>()
            pendingBond = bond
            if (device.bondState != BluetoothDevice.BOND_BONDED) device.createBond()
            // First-ever bond gets the long window (iOS uses 90 s vs 5 s).
            withTimeout(90_000) { bond.await() }
            enableNotify(ch)
        }
    }

    private suspend fun enableNotify(ch: BluetoothGattCharacteristic) {
        val g = gatt ?: throw RadioException("GATT gone")
        opMutex.withLock {
            if (!g.setCharacteristicNotification(ch, true)) {
                throw RadioException("setCharacteristicNotification failed")
            }
            val cccd = ch.getDescriptor(MeshProtocol.CCCD_UUID)
                ?: throw RadioException("CCCD missing on ${ch.uuid}")
            val deferred = CompletableDeferred<Int>()
            pendingDescriptorWrite = deferred
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            if (!g.writeDescriptor(cccd)) throw RadioException("writeDescriptor rejected")
            val status = withTimeout(95_000) { deferred.await() }
            if (status != BluetoothGatt.GATT_SUCCESS) throw GattStatusException(status)
        }
    }

    override suspend fun send(toRadioMsg: MeshProtos.ToRadio) {
        val bytes = toRadioMsg.toByteArray()
        var lastStatus = -1
        repeat(MeshProtocol.WRITE_ATTEMPT_LIMIT) { attempt ->
            val status = writeOnce(bytes)
            if (status == BluetoothGatt.GATT_SUCCESS) return
            lastStatus = status
            // Mirror of the iOS insufficientResources backoff: 120/240/360 ms.
            delay(120L * (attempt + 1))
        }
        throw RadioException("Write failed after ${MeshProtocol.WRITE_ATTEMPT_LIMIT} attempts, status $lastStatus")
    }

    private suspend fun writeOnce(bytes: ByteArray): Int {
        val g = gatt ?: throw RadioException("GATT gone")
        val ch = toRadio ?: throw RadioException("TORADIO missing")
        return opMutex.withLock {
            val deferred = CompletableDeferred<Int>()
            pendingWrite = deferred
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            ch.value = bytes
            @Suppress("DEPRECATION")
            if (!g.writeCharacteristic(ch)) throw RadioException("writeCharacteristic rejected")
            withTimeout(10_000) { deferred.await() }
        }
    }

    /** Coalesced drain: overlapping FROMNUM doorbells collapse into one loop pass. */
    override suspend fun startDrainPendingPackets() {
        needsDrain = true
        if (isDraining) return
        isDraining = true
        try {
            while (needsDrain) {
                needsDrain = false
                drainPendingPackets()
            }
        } catch (e: TimeoutCancellationException) {
            // A GATT read that gets no answer in 10 s is a dead link, not a slow one.
            // This is the fastest detector of an adapter that vanished without a state
            // broadcast; declareLinkLost is idempotent, so racing the RSSI poll is fine.
            Log.w(TAG, "Drain aborted: ${e.message}")
            declareLinkLost("Radio stopped responding")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Drain aborted: ${e.message}")
            declareLinkLost("Radio stopped responding")
        } finally {
            isDraining = false
        }
    }

    private suspend fun drainPendingPackets() {
        while (true) {
            val bytes = readOnce()
            if (bytes.isEmpty()) return
            // A malformed frame (e.g. invalid UTF-8 in a name) must not kill the link.
            runCatching { MeshProtos.FromRadio.parseFrom(bytes) }
                .onSuccess { eventFlow.tryEmit(ConnectionEvent.Data(it)) }
                .onFailure { Log.w(TAG, "Skipping undecodable FromRadio frame", it) }
        }
    }

    private suspend fun readOnce(): ByteArray {
        val g = gatt ?: throw RadioException("GATT gone")
        val ch = fromRadio ?: throw RadioException("FROMRADIO missing")
        return opMutex.withLock {
            val deferred = CompletableDeferred<ByteArray>()
            pendingRead = deferred
            @Suppress("DEPRECATION")
            if (!g.readCharacteristic(ch)) throw RadioException("readCharacteristic rejected")
            withTimeout(10_000) { deferred.await() }
        }
    }

    /**
     * Tear the link down and, above all, release the GATT client.
     *
     * `close()` fired straight after `disconnect()` is the classic Android BLE defect: it
     * unregisters the app's client while the stack still has an in-flight disconnect, or
     * a direct connect that never resolved, pending for this address. Those entries sit in
     * the controller's connect list, which is a small fixed-size table, and the app's retry
     * policy can stack up to thirteen of them against one radio in a single session. Once
     * the table is full every new LE connection fails, which no adapter toggle clears
     * because a soft Bluetooth restart does not reload the controller; only a reboot does.
     * So the disconnect is awaited before the close.
     *
     * NonCancellable because the usual caller is establish()'s catch block, which may
     * already be running in a cancelled coroutine: without it the first suspension point
     * would throw and skip close() entirely, leaking the very client this protects.
     */
    override suspend fun disconnect() = withContext(NonCancellable) {
        closed = true
        runCatching { context.unregisterReceiver(bondReceiver) }
        runCatching { context.unregisterReceiver(adapterReceiver) }
        failAllPending("Disconnected by app")
        gatt?.let { g ->
            val done = CompletableDeferred<Unit>()
            pendingDisconnect = done
            runCatching { g.disconnect() }
            // A link that came up always reports STATE_DISCONNECTED. One that never
            // connected often reports nothing at all, so that case only gives the stack a
            // moment to drop the pending connect rather than waiting out the full window.
            val wait = if (everConnected) DISCONNECT_WAIT_MS else ABORT_WAIT_MS
            val acked = withTimeoutOrNull(wait) { done.await() } != null
            if (!acked) Log.w(TAG, "No STATE_DISCONNECTED within ${wait}ms; closing anyway")
            runCatching { g.close() }
            Log.i(TAG, "GATT client closed (live clients: ${liveClients.decrementAndGet()})")
        }
        gatt = null
        pendingDisconnect = null
        scope.cancel()
    }

    private fun failAllPending(reason: String) {
        val e = RadioException(reason)
        pendingMtu?.completeExceptionally(e)
        pendingDiscovery?.completeExceptionally(e)
        pendingRead?.completeExceptionally(e)
        pendingWrite?.completeExceptionally(e)
        pendingDescriptorWrite?.completeExceptionally(e)
        pendingBond?.completeExceptionally(e)
    }

    companion object {
        private const val RSSI_POLL_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        /** How long a link that was up may take to report STATE_DISCONNECTED. */
        private const val DISCONNECT_WAIT_MS = 2_000L
        /** A connect that never came up usually reports nothing; just let the stack drop it. */
        private const val ABORT_WAIT_MS = 400L
        /** Consecutive rejected RSSI reads before the link counts as dead (~15 s). */
        private const val RSSI_REJECT_LIMIT = 3
        /** How long to wait for an answer to the MTU request before carrying on without one. */
        private const val MTU_TIMEOUT_MS = 10_000L
        private const val TAG = "BleConnection"

        /**
         * GATT clients this process has registered and not yet closed. Android allows a
         * small fixed number per device; exhausting them makes every later connect fail
         * until reboot, so the count is logged on both edges to make a leak visible in a
         * bug report rather than something only reproducible on the user's own phone.
         */
        private val liveClients = AtomicInteger(0)
    }
}

class RadioException(message: String) : Exception(message)
class GattStatusException(val status: Int) : Exception("GATT status $status")
