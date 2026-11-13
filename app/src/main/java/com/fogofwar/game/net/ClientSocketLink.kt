package com.fogofwar.game.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/** Opens a TCP connection to one of the offered addresses; `null` if none are reachable. */
fun interface DirectConnector {
    fun connect(candidates: List<InetSocketAddress>, timeoutMs: Int): Socket?
}

/**
 * Client side of the direct channel.
 *
 * The host advertises its addresses over Nearby; this class races the candidates (on every
 * usable [android.net.Network] via [DirectConnector]), performs the token handshake and then
 * carries game messages. On any failure it quietly returns to Nearby and retries in the
 * background, so a lost socket costs at most a couple of seconds of jitter.
 */
class ClientSocketLink(
    private val connector: DirectConnector,
    private val onFrame: (ByteArray) -> Unit,
    private val onStateChanged: (Boolean) -> Unit = {},
) {
    @Volatile
    private var offer: Offer? = null

    @Volatile
    private var running = false

    @Volatile
    private var connected = false

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var out: OutputStream? = null

    private var worker: Thread? = null

    /** Bumped on every start/close so a thread that is still winding down cannot revive. */
    @Volatile
    private var generation = 0

    private val writeLock = Any()

    private data class Offer(val token: Long, val port: Int, val ips: List<String>)

    val isConnected: Boolean get() = connected

    /** Called for every `NetMessage.TransportOffer` from the host. */
    fun updateOffer(token: Long, port: Int, ips: List<String>) {
        if (port !in 1..65535 || ips.isEmpty()) return
        val same = offer?.let { it.token == token && it.port == port && it.ips == ips } == true
        offer = Offer(token, port, ips)
        if (!same) {
            closeSocket()
            ensureWorker()
        }
    }

    /** Sends a game message over the socket; `false` means "use Nearby instead". */
    fun send(bytes: ByteArray): Boolean {
        if (!connected) return false
        val stream = out ?: return false
        return try {
            synchronized(writeLock) { SocketFrames.write(stream, bytes) }
            true
        } catch (t: Throwable) {
            closeSocket()
            false
        }
    }

    fun close() {
        running = false
        generation++
        offer = null
        closeSocket()
        worker?.interrupt()
        worker = null
    }

    // --------------------------------------------------------------- internals

    private fun ensureWorker() {
        if (worker?.isAlive == true) return
        running = true
        generation++
        val gen = generation
        worker = thread(name = "FogSocketLink", isDaemon = true) { loop(gen) }
    }

    private fun loop(gen: Int) {
        var failures = 0
        while (running && generation == gen) {
            val current = offer
            if (current == null) {
                sleep(RETRY_MS)
                continue
            }
            val candidates = current.ips.mapNotNull { ip ->
                try {
                    InetSocketAddress(java.net.InetAddress.getByName(ip), current.port)
                } catch (_: Throwable) {
                    null
                }
            }
            if (candidates.isEmpty()) {
                sleep(RETRY_MS)
                continue
            }
            var opened: Socket? = null
            try {
                opened = connector.connect(candidates, CONNECT_TIMEOUT_MS)
                if (opened == null) {
                    failures++
                    sleep(retryDelay(failures))
                    continue
                }
                opened.tcpNoDelay = true
                opened.soTimeout = ACK_TIMEOUT_MS
                val input = BufferedInputStream(opened.getInputStream())
                val output = BufferedOutputStream(opened.getOutputStream())
                SocketFrames.writeHandshake(output, Protocol.VERSION, current.token)
                if (SocketFrames.readAck(input) != true) {
                    failures++
                    sleep(retryDelay(failures))
                    continue
                }
                failures = 0
                opened.soTimeout = READ_TIMEOUT_MS
                socket = opened
                out = output
                connected = true
                onStateChanged(true)
                try {
                    while (running) {
                        val bytes = try {
                            SocketFrames.read(input)
                        } catch (_: SocketTimeoutException) {
                            // No host traffic and no keepalive: the link is dead.
                            break
                        } ?: break
                        if (bytes.isNotEmpty()) onFrame(bytes)
                    }
                } catch (_: Throwable) {
                }
            } catch (_: Throwable) {
            } finally {
                if (connected) {
                    connected = false
                    onStateChanged(false)
                }
                out = null
                socket = null
                try {
                    opened?.close()
                } catch (_: Throwable) {
                }
            }
            if (running && generation == gen) {
                failures++
                sleep(retryDelay(failures))
            }
        }
    }

    private fun retryDelay(failures: Int): Long =
        (RETRY_MS * failures.coerceIn(1, 5)).coerceAtMost(MAX_RETRY_MS)

    private fun closeSocket() {
        val current = socket
        socket = null
        out = null
        connected = false
        try {
            current?.close()
        } catch (_: Throwable) {
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 800
        const val ACK_TIMEOUT_MS = 2_000
        const val READ_TIMEOUT_MS = 4_000
        const val RETRY_MS = 2_000L
        const val MAX_RETRY_MS = 15_000L
    }
}
