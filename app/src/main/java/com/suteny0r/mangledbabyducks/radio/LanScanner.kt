package com.suteny0r.mangledbabyducks.radio

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.proto.MeshProtos
import java.io.DataInputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * LAN discovery of radios with Wi-Fi or Ethernet enabled. Two sources feed one map:
 *
 * 1. mDNS, `_meshtastic._tcp` (port of the Bonjour `NWBrowser` in TCPTransport.swift,
 *    on Android's NsdManager). NsdManager resolves one service at a time, so found
 *    services queue through a channel and resolve serially; a multicast lock is held
 *    for the life of the scan because many phones drop multicast frames without it.
 * 2. A subnet sweep, because some firmware builds (a Heltec V4 on 2.7.17 in the test
 *    rig) never answer mDNS. Every host on the phone's /24 is tried on the API port;
 *    an open port is then confirmed by sending `want_config` and reading framed
 *    FromRadio packets back, which also yields the radio's long name. The sweep skips
 *    [exclude] hosts: the firmware allows one TCP client, so probing the radio the app
 *    is currently linked to would drop that session.
 */
class LanScanner(context: Context) {
    data class LanDevice(
        /** The advertised service name or the radio's long name, falling back to its host. */
        val name: String,
        val host: String,
        val port: Int,
        val lastSeenMs: Long,
    ) {
        /** Matches the `host:port` form a saved TCP radio stores as its address. */
        val id: String get() = "$host:$port"
    }

    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Emits the current set of radios whenever it changes; stops when cancelled. [exclude]
     * are hosts the sweep must not touch (the live TCP link).
     */
    fun discover(exclude: Set<String> = emptySet()): Flow<Map<String, LanDevice>> = callbackFlow {
        val found = ConcurrentHashMap<String, LanDevice>()
        val lock = wifi.createMulticastLock("meshtastic-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }

        // --- mDNS
        val pending = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val resolver = launch {
            for (info in pending) {
                val device = resolveOne(info) ?: continue
                found[device.id] = device
                trySend(found.toMap())
            }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "mDNS discovery started")
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.trimEnd('.') == SERVICE_TYPE.trimEnd('.')) pending.trySend(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                val gone = found.values.filter { it.name == info.serviceName }
                gone.forEach { found.remove(it.id) }
                if (gone.isNotEmpty()) trySend(found.toMap())
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        val mdnsStarted = runCatching {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.isSuccess

        // --- subnet sweep, repeated while the scan is alive
        val sweeper = launch(Dispatchers.IO) {
            while (isActive) {
                sweep(exclude) { device ->
                    found[device.id] = device
                    trySend(found.toMap())
                }
                delay(SWEEP_INTERVAL_MS)
            }
        }

        awaitClose {
            if (mdnsStarted) runCatching { nsd.stopServiceDiscovery(listener) }
            pending.close()
            resolver.cancel()
            sweeper.cancel()
            runCatching { lock.release() }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveOne(info: NsdServiceInfo): LanDevice? =
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.d(TAG, "resolve failed for ${serviceInfo.serviceName}: $errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host?.hostAddress
                        if (!cont.isActive) return
                        if (host == null) {
                            cont.resume(null)
                            return
                        }
                        cont.resume(
                            LanDevice(
                                name = serviceInfo.serviceName,
                                host = host,
                                port = serviceInfo.port.takeIf { it > 0 } ?: MeshProtocol.DEFAULT_TCP_PORT,
                                lastSeenMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                },
            )
        }

    // -----------------------------------------------------------------------------------
    // Subnet sweep

    /** The phone's IPv4 address on the active network, or null when not on Wi-Fi / Ethernet. */
    private fun ownIpv4(): Inet4Address? {
        val network = connectivity.activeNetwork ?: return null
        val props = connectivity.getLinkProperties(network) ?: return null
        return props.linkAddresses.mapNotNull { it.address as? Inet4Address }
            .firstOrNull { !it.isLoopbackAddress }
    }

    private suspend fun sweep(exclude: Set<String>, onFound: (LanDevice) -> Unit) {
        val own = ownIpv4() ?: return
        val base = own.address.copyOf()
        val ownHost = own.hostAddress
        val gate = Semaphore(SWEEP_PARALLELISM)
        withContext(Dispatchers.IO) {
            val jobs = (1..254).map { last ->
                launch {
                    gate.withPermit {
                        val host = "${base[0].toUByte()}.${base[1].toUByte()}.${base[2].toUByte()}.$last"
                        if (host == ownHost || host in exclude) return@withPermit
                        probe(host)?.let(onFound)
                    }
                }
            }
            jobs.forEach { it.join() }
        }
    }

    /**
     * Open the API port, send want_config and read framed FromRadio packets for a moment:
     * the magic bytes prove a Meshtastic radio, my_info gives its node number, and the
     * matching node_info (if it arrives within the budget) gives its long name.
     */
    private fun probe(host: String): LanDevice? {
        val port = MeshProtocol.DEFAULT_TCP_PORT
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val request = MeshProtos.ToRadio.newBuilder()
                .setWantConfigId(MeshProtocol.NONCE_ONLY_CONFIG)
                .build()
                .toByteArray()
            socket.getOutputStream().apply {
                write(
                    byteArrayOf(
                        MeshProtocol.MAGIC_0, MeshProtocol.MAGIC_1,
                        (request.size shr 8).toByte(), (request.size and 0xFF).toByte(),
                    ),
                )
                write(request)
                flush()
            }
            val input = DataInputStream(socket.getInputStream().buffered())
            var myNum: Long? = null
            var longName: String? = null
            val deadline = System.currentTimeMillis() + PROBE_BUDGET_MS
            var packets = 0
            while (System.currentTimeMillis() < deadline && packets < PROBE_MAX_PACKETS) {
                var state = 0
                while (state < 2) {
                    val b = input.readByte()
                    state = when {
                        state == 0 && b == MeshProtocol.MAGIC_0 -> 1
                        state == 1 && b == MeshProtocol.MAGIC_1 -> 2
                        b == MeshProtocol.MAGIC_0 -> 1
                        else -> 0
                    }
                }
                val len = input.readUnsignedShort()
                if (len == 0) continue
                val payload = ByteArray(len)
                input.readFully(payload)
                packets++
                val from = runCatching { MeshProtos.FromRadio.parseFrom(payload) }.getOrNull() ?: continue
                when (from.payloadVariantCase) {
                    MeshProtos.FromRadio.PayloadVariantCase.MY_INFO -> myNum = from.myInfo.myNodeNum.uint()
                    MeshProtos.FromRadio.PayloadVariantCase.NODE_INFO -> {
                        if (from.nodeInfo.num.uint() == myNum && from.nodeInfo.hasUser()) {
                            longName = from.nodeInfo.user.longName.takeIf { it.isNotBlank() }
                            break
                        }
                    }
                    else -> {}
                }
            }
            if (packets == 0) return null
            Log.d(TAG, "sweep found $host (${longName ?: myNum})")
            LanDevice(
                name = longName ?: myNum?.let { "Radio ${"!%08x".format(it)}" } ?: host,
                host = host,
                port = port,
                lastSeenMs = System.currentTimeMillis(),
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val TAG = "LanScanner"
        const val SERVICE_TYPE = "_meshtastic._tcp."
        private const val SWEEP_INTERVAL_MS = 30_000L
        private const val SWEEP_PARALLELISM = 48
        private const val CONNECT_TIMEOUT_MS = 700
        private const val READ_TIMEOUT_MS = 2_000
        private const val PROBE_BUDGET_MS = 2_500L
        private const val PROBE_MAX_PACKETS = 40
    }
}
