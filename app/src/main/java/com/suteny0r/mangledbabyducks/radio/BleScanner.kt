package com.suteny0r.mangledbabyducks.radio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** BLE discovery for Meshtastic radios (port of BLETransport scanning). */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    val adapter get() = bluetoothManager.adapter

    /** Emits every advertisement from a device carrying the Meshtastic service UUID. */
    fun scan(): Flow<DiscoveredDevice> = callbackFlow {
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
            .setServiceUuid(ParcelUuid(MeshProtocol.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, callback)

        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    fun connection(address: String): BleConnection =
        BleConnection(context, adapter.getRemoteDevice(address))
}
