package com.suteny0r.mangledbabyducks.radio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** BLE discovery for Meshtastic radios (port of BLETransport scanning). */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    val adapter get() = bluetoothManager.adapter

    /**
     * Emits every advertisement from a device carrying the Meshtastic service UUID, or
     * from one specific [address] when given (a known radio is matched by MAC, since its
     * advertisement is the thing being waited for, not its service list).
     */
    fun scan(address: String? = null, lowPower: Boolean = false): Flow<DiscoveredDevice> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
            ?: run { close(RadioException("Bluetooth unavailable")); return@callbackFlow }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(
                    DiscoveredDevice(
                        id = result.device.address,
                        name = result.scanRecord?.deviceName ?: result.device.name ?: "Meshtastic",
                        rssi = result.rssi,
                        lastSeenMs = System.currentTimeMillis(),
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                close(RadioException("Scan failed: $errorCode"))
            }
        }

        val filter = ScanFilter.Builder()
            .apply {
                if (address != null) setDeviceAddress(address)
                else setServiceUuid(ParcelUuid(MeshProtocol.SERVICE_UUID))
            }
            .build()
        val settings = ScanSettings.Builder()
            // Low latency is right for the few-second probe before a connect attempt. It is
            // wrong for [awaitReturn], which may run for hours waiting on a radio to come
            // back, so that one asks for the duty-cycled mode instead.
            .setScanMode(
                if (lowPower) ScanSettings.SCAN_MODE_LOW_POWER
                else ScanSettings.SCAN_MODE_LOW_LATENCY
            )
            .build()
        scanner.startScan(listOf(filter), settings, callback)

        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    /**
     * True once the radio at [address] is seen advertising. Connecting by MAC to a radio
     * that is not advertising just burns a ~5 s GATT timeout and returns status 133, so
     * every attempt at a known radio waits for its advertisement first.
     */
    suspend fun isAdvertising(address: String, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            runCatching { scan(address).first() }.isSuccess
        } ?: false

    /**
     * Suspends until the radio at [address] advertises, however long that takes. This is
     * how a radio that went out of range gets reconnected: the attempt loop gives up after
     * a couple of minutes, and without this nothing is left alive to notice it come back.
     *
     * Scanning is cheaper than it looks here. The filter is on the MAC, so the match is
     * made in the controller, and the mode is duty-cycled rather than low latency. The
     * background connect costs less still: nothing runs on the host at all.
     */
    suspend fun awaitReturn(address: String) {
        // Two listeners, first one wins, and the loser is cancelled and cleaned up before
        // this returns. The scan alone was not enough for a long absence:
        //
        // - Android downgrades any scan that has run for 30 minutes to "opportunistic",
        //   which only hears what some other app's scan turns up (AppScanStats
        //   SCAN_TIMEOUT_MS; filtered scans included). So a radio away for an hour came
        //   back to a watch that had gone deaf, and the user had to reconnect by hand.
        // - A bonded radio can be connectable and still invisible to every scan (the
        //   tester's Heltec; see the HANDOFF note on directed advertising).
        //
        // The background connect covers both. It is the controller that waits, on its
        // accept list, with no time limit and with the phone asleep.
        val how = merge(
            flow { awaitAdvertisement(address); emit("advertisement") },
            flow { awaitBackgroundConnect(address); emit("background connect") },
        ).first()
        Log.i(TAG, "$address is back ($how)")
    }

    /** The duty-cycled scan, restarted before Android's 30-minute downgrade can land. */
    private suspend fun awaitAdvertisement(address: String) {
        while (true) {
            val seen = withTimeoutOrNull(SCAN_RESTART_MS) { scan(address, lowPower = true).first() }
            if (seen != null) return
        }
    }

    /**
     * Suspends until the controller manages a background connection to [address], then
     * drops that connection again. It is a presence signal and nothing more: the GATT
     * client opened here is closed before this returns, whether it fired or was cancelled,
     * so the attempt loop that follows still registers the only client for the radio.
     * Never returns when Bluetooth is unavailable; the scan is the other half.
     */
    private suspend fun awaitBackgroundConnect(address: String) {
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: awaitCancellation()
        val connected = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Unit>()
        val rearms = AtomicInteger(0)
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when {
                    newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS ->
                        connected.complete(Unit)
                    newState == BluetoothProfile.STATE_DISCONNECTED && connected.isCompleted ->
                        disconnected.complete(Unit)
                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        // A failed try, not the end of the request, but some stacks drop
                        // the device from the accept list after one. Put it back, a
                        // bounded number of times so a stack that fails instantly cannot
                        // spin here.
                        val n = rearms.incrementAndGet()
                        Log.w(TAG, "Presence probe for $address: status $status (re-arm $n/$PROBE_REARMS)")
                        if (n <= PROBE_REARMS) runCatching { g.connect() }
                    }
                }
            }
        }
        val gatt = device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
            ?: awaitCancellation()
        Log.i(TAG, "Presence probe armed for $address")
        try {
            connected.await()
        } finally {
            withContext(NonCancellable) {
                runCatching { gatt.disconnect() }
                // Only a link that came up has a disconnect to wait for; a pending
                // background request is simply withdrawn.
                if (connected.isCompleted) withTimeoutOrNull(PROBE_DISCONNECT_MS) { disconnected.await() }
                runCatching { gatt.close() }
                Log.i(TAG, "Presence probe closed for $address")
            }
        }
    }

    fun connection(address: String): BleConnection =
        BleConnection(context, adapter.getRemoteDevice(address))

    private companion object {
        const val TAG = "BleScanner"
        /** Under Android's 30-minute scan downgrade, with room to spare. */
        const val SCAN_RESTART_MS = 20 * 60_000L
        const val PROBE_REARMS = 5
        const val PROBE_DISCONNECT_MS = 3_000L
    }
}
