package com.suteny0r.meshtastic.radio

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
import kotlinx.coroutines.withTimeout
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
    private var pendingMtu: CompletableDeferred<Int>? = null
    private var pendingDiscovery: CompletableDeferred<Unit>? = null
    private var pendingRead: CompletableDeferred<ByteArray>? = null
    private var pendingWrite: CompletableDeferred<Int>? = null
    private var pendingDescriptorWrite: CompletableDeferred<Int>? = null
    private var pendingBond: CompletableDeferred<Unit>? = null

    @Volatile private var closed = false
    @Volatile private var needsDrain = false
    @Volatile private var isDraining = false

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
                    gatt?.let { runCatching { it.close() } }
                    gatt = null
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
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS ->
                    pendingConnect?.complete(Unit)
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    val err = if (status != BluetoothGatt.GATT_SUCCESS) "GATT status $status" else null
                    pendingConnect?.completeExceptionally(RadioException(err ?: "Disconnected"))
                    failAllPending(err ?: "Disconnected")
                    if (!closed) {
                        // Status 8 (conn timeout) and 19 (peer terminated) mirror the iOS
                        // reconnect-worthy CBError cases; everything else stays down.
                        val reconnect = status == 8 || status == 19
                        eventFlow.tryEmit(ConnectionEvent.Disconnected(reconnect, err))
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) pendingMtu?.complete(mtu)
            else pendingMtu?.completeExceptionally(RadioException("MTU change failed: $status"))
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
        withTimeout(20_000) { connectDeferred.await() }

        val g = gatt ?: throw RadioException("GATT gone")

        val mtuDeferred = CompletableDeferred<Int>()
        pendingMtu = mtuDeferred
        g.requestMtu(512)
        val mtu = withTimeout(10_000) { mtuDeferred.await() }
        Log.i(TAG, "Negotiated MTU $mtu")

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
        startDrainPendingPackets()
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

    override suspend fun disconnect() {
        closed = true
        runCatching { context.unregisterReceiver(bondReceiver) }
        runCatching { context.unregisterReceiver(adapterReceiver) }
        failAllPending("Disconnected by app")
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
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
        private const val TAG = "BleConnection"
    }
}

class RadioException(message: String) : Exception(message)
class GattStatusException(val status: Int) : Exception("GATT status $status")
