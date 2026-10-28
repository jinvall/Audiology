package com.audiology2.stream.network

import android.util.Log
import com.audiology2.stream.config.TransportType
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.BlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class StreamState { DISCONNECTED, CONNECTING, CONNECTED, STREAMING, ERROR }

/** Runtime/network statistics exposed to the UI. Pure data class. */
data class NetworkStats(
    val state: StreamState = StreamState.DISCONNECTED,
    val packetsSent: Long = 0,
    val bytesSent: Long = 0,
    val sendErrors: Long = 0,
    val queueDepth: Int = 0,
    val reconnects: Long = 0,
    val remote: String = "",
    val lastError: String = ""
)

/**
 * Drains [PacketizedFrame]s from the bounded [queue] and transmits them over
 * UDP or TCP to [host]:[port]. Handles connect/reconnect, reports stats, and
 * never throws uncaught (errors are channelled into [statsListener]).
 *
 * UDP: one datagram per packet. No per-packet acknowledgment (loss is detected
 * by the receiver via sequence gaps). A connected/failed send is reported as an
 * error; the socket is recreated on the next send attempt when disconnected.
 *
 * TCP: a length-prefixed framing ([packetLen uint16 LE] + packet bytes) is used
 * so the receiver can split the stream into packets. Reconnects on failure.
 */
class StreamSender(
    private val queue: BlockingQueue<PacketizedFrame>,
    private val statsListener: (NetworkStats) -> Unit
) {
    @Volatile private var stop = false
    @Volatile private var transport: TransportType = TransportType.UDP
    @Volatile private var host: String = ""
    @Volatile private var port: Int = 0
    @Volatile private var autoReconnect: Boolean = false

    private val packetsSent = AtomicLong()
    private val bytesSent = AtomicLong()
    private val sendErrors = AtomicLong()
    /** Inbound bytes read and discarded by the TCP drain thread. */
    private val drainedBytes = AtomicLong()
    private val reconnects = AtomicLong()
    private val lastError = AtomicReference("")
    private val state = AtomicReference(StreamState.DISCONNECTED)
    private val started = AtomicBoolean(false)

    private var thread: Thread? = null

    fun start(host: String, port: Int, transport: TransportType, autoReconnect: Boolean) {
        if (!started.compareAndSet(false, true)) return
        this.host = host
        this.port = port
        this.transport = transport
        this.autoReconnect = autoReconnect
        stop = false
        Log.i("StreamSender", "start: host=$host port=$port transport=$transport")
        thread = Thread({ run() }, "StreamSender-${transport.name}")
        thread?.isDaemon = true
        thread?.start()
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        stop = true
        thread?.interrupt()
        try { thread?.join(300) } catch (_: InterruptedException) { }
        thread = null
        updateState(StreamState.DISCONNECTED, "stopped")
        emit()
    }

    private fun run() {
        Log.i("StreamSender", "run: starting sender loop")
        while (!stop) {
            if (state.get() == StreamState.DISCONNECTED || state.get() == StreamState.ERROR) {
                val resolved = resolveHost()
                if (resolved == null) {
                    updateState(StreamState.ERROR, "DNS resolution failed for $host")
                    Log.e("StreamSender", "DNS resolution failed for $host")
                    if (!autoReconnect) { emit(); break }
                    backoff()
                    continue
                }
                Log.i("StreamSender", "Resolved host: $resolved")
                updateState(StreamState.CONNECTING, "")
                emit()
                if (!connect(resolved)) {
                    Log.e("StreamSender", "Connection failed to $resolved")
                    if (!autoReconnect) { emit(); break }
                    backoff()
                    continue
                }
            }
            // Connected: drain the queue.
            Log.i("StreamSender", "Connected, starting to drain queue")
            updateState(StreamState.STREAMING, "")
            val hadError = drainQueueWithTicks()
            // If drainQueueWithTicks returned due to an error, apply backoff before reconnecting
            if (hadError && !stop) {
                Log.w("StreamSender", "drainQueueWithTicks returned with error, applying backoff")
                backoff()
            }
        }
        Log.i("StreamSender", "run: sender loop ended")
    }

    private fun drainQueueWithTicks(): Boolean {
        var lastEmit = System.currentTimeMillis()
        var errored = false
        while (!stop && !errored) {
            val frame = try {
                queue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return false
            }
            if (frame != null) {
                val ok = sendFrame(frame)
                if (ok) {
                    packetsSent.incrementAndGet()
                    bytesSent.addAndGet(frame.packet.size.toLong())
                } else {
                    sendErrors.incrementAndGet()
                    updateState(StreamState.ERROR, "send failed")
                    errored = true
                }
            }
            val now = System.currentTimeMillis()
            if (now - lastEmit >= 250) {
                lastEmit = now
                emit()
            }
        }
        emit()
        return errored
    }

    private lateinit var udpSocket: DatagramSocket
    private var tcpSocket: Socket? = null
    private var tcpOut: OutputStream? = null
    private var tcpDrainThread: Thread? = null

    private fun connect(addr: InetSocketAddress): Boolean {
        return try {
            when (transport) {
                TransportType.UDP -> {
                    udpSocket = DatagramSocket().apply {
                        connect(addr)
                        setSoTimeout(5000) // 5 second timeout for sends
                    }
                    true
                }
                TransportType.TCP -> {
                    // Low-latency TCP: no Nagle batching (adds up to 40ms per packet).
                    val socket = Socket().apply {
                        tcpNoDelay = true
                        // Keep the send path from deadlocking if the peer writes
                        // anything back (HTTP errors, control frames) - see drain.
                        receiveBufferSize = 64 * 1024
                    }
                    socket.connect(addr, 2000)
                    tcpSocket = socket
                    tcpOut = socket.outputStream
                    startTcpDrain(socket)
                    true
                }
            }
            // mark connected
            if (state.get() != StreamState.ERROR) updateState(StreamState.CONNECTED, "")
            true
        } catch (e: IOException) {
            updateState(StreamState.ERROR, "connect failed: ${e.message}")
            closeSockets()
            false
        }
    }

    /**
     * Critical for TCP: the phone's sender never reads from its socket, so any
     * data the server sends back (HTTP status/error bodies, control responses)
     * sits un-ACKed and eventually collapses the phone's receive window, which
     * stalls the *send* path and latency grows without bound. This background
     * thread reads and discards inbound bytes so the window never fills.
     */
    private fun startTcpDrain(socket: Socket) {
        stopTcpDrain()
        tcpDrainThread = Thread({
            try {
                val input = socket.inputStream ?: return@Thread
                val buf = ByteArray(4096)
                while (socket.isConnected && !socket.isClosed) {
                    val n = input.read(buf)
                    if (n < 0) break
                    // Discard. Total drained bytes are tracked for diagnostics.
                    drainedBytes.addAndGet(n.toLong())
                }
            } catch (_: IOException) {
                // Socket closed on disconnect: expected.
            }
        }, "StreamSender-TcpDrain").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopTcpDrain() {
        runCatching { tcpDrainThread?.interrupt() }
        tcpDrainThread = null
    }

    private fun sendFrame(frame: PacketizedFrame): Boolean {
        return try {
            if (transport == TransportType.UDP) {
                val pkt = DatagramPacket(frame.packet, frame.packet.size, udpSocket.inetAddress, udpSocket.port)
                udpSocket.send(pkt)
            } else {
                val out = tcpOut ?: return false
                // length-prefixed framing: 2-byte LE length (max 65535; our packets are < 1500)
                val len = frame.packet.size
                if (len > 65535) return false
                out.write(len and 0xFF)
                out.write((len shr 8) and 0xFF)
                out.write(frame.packet)
                out.flush()
            }
            true
        } catch (e: IOException) {
            lastError.set(e.message ?: "io error")
            closeSockets()
            updateState(StreamState.ERROR, lastError.get())
            false
        }
    }

    private fun closeSockets() {
        stopTcpDrain()
        try { udpSocket.close() } catch (_: Exception) { }
        try { tcpOut?.close() } catch (_: Exception) { }
        try { tcpSocket?.close() } catch (_: Exception) { }
        tcpOut = null
        tcpSocket = null
    }

    private fun resolveHost(): InetSocketAddress? {
        return try {
            val addr = if (host.startsWith("[")) {
                val end = host.indexOf(']')
                val ip = host.substring(1, end)
                InetAddress.getByName(ip)
            } else {
                InetAddress.getByName(host)
            }
            // Prefer IPv4 on a LAN for lowest latency; fall back to whatever resolved.
            val best = if (addr is Inet4Address) addr else addr
            InetSocketAddress(best, port)
        } catch (e: IOException) {
            lastError.set("resolve failed: ${e.message}")
            null
        }
    }

    private fun backoff() {
        val delay = 500L * (1L + (reconnects.get() % 8)) // 0.5s..4s capped ~
        reconnects.incrementAndGet()
        try {
            Thread.sleep(delay.coerceAtMost(2000L))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun updateState(s: StreamState, err: String = "") {
        state.set(s)
        if (err.isNotEmpty()) lastError.set(err)
        if (s == StreamState.CONNECTED || s == StreamState.STREAMING) lastError.set("")
    }

    private fun emit() {
        val q = queue
        statsListener(
            NetworkStats(
                state = state.get(),
                packetsSent = packetsSent.get(),
                bytesSent = bytesSent.get(),
                sendErrors = sendErrors.get(),
                queueDepth = q.size,
                reconnects = reconnects.get(),
                remote = "$host:$port",
                lastError = lastError.get()
            )
        )
    }

}
