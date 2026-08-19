package com.suteny0r.meshtastic.radio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.meshtastic.proto.MeshProtos
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP link to a radio. Port of TCPConnection.swift: frames are
 * [0x94][0xC3][len_hi][len_lo][protobuf], length big-endian UInt16.
 */
class TcpConnection(
    private val host: String,
    private val port: Int = MeshProtocol.DEFAULT_TCP_PORT,
) : RadioConnection {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventFlow = MutableSharedFlow<ConnectionEvent>(
        extraBufferCapacity = 4096,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<ConnectionEvent> = eventFlow
    override val requiresPeriodicHeartbeat = true

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private val writeMutex = Mutex()
    @Volatile private var closed = false

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 10_000)
        socket = s
        output = s.getOutputStream()
        val input = DataInputStream(s.getInputStream().buffered())
        scope.launch { readerLoop(input) }
        Unit
    }

    private fun readerLoop(input: DataInputStream) {
        try {
            while (!closed) {
                // Hunt for the two magic bytes one byte at a time so a corrupt
                // stream resynchronizes instead of stalling.
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
                if (len == 0) continue // crafted 94 C3 00 00 must not stall the reader
                val payload = ByteArray(len)
                input.readFully(payload)
                runCatching { MeshProtos.FromRadio.parseFrom(payload) }
                    .onSuccess { eventFlow.tryEmit(ConnectionEvent.Data(it)) }
            }
        } catch (e: EOFException) {
            if (!closed) eventFlow.tryEmit(ConnectionEvent.Disconnected(true, "Stream ended"))
        } catch (e: Exception) {
            if (!closed) eventFlow.tryEmit(ConnectionEvent.Disconnected(true, e.message))
        }
    }

    override suspend fun send(toRadio: MeshProtos.ToRadio) = withContext(Dispatchers.IO) {
        val out = output ?: throw RadioException("Not connected")
        val payload = toRadio.toByteArray()
        require(payload.size <= 0xFFFF) { "Frame too large" }
        val frame = ByteArray(4 + payload.size)
        frame[0] = MeshProtocol.MAGIC_0
        frame[1] = MeshProtocol.MAGIC_1
        frame[2] = (payload.size shr 8).toByte()
        frame[3] = (payload.size and 0xFF).toByte()
        payload.copyInto(frame, 4)
        writeMutex.withLock {
            out.write(frame)
            out.flush()
        }
    }

    override suspend fun startDrainPendingPackets() {
        // TCP pushes frames; there is no drain step.
    }

    override suspend fun disconnect() {
        closed = true
        runCatching { socket?.close() }
        socket = null
        scope.cancel()
    }
}
