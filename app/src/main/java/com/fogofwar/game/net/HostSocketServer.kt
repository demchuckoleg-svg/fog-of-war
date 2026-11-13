package com.fogofwar.game.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Host side of the direct channel: one TCP server for the whole room.
 *
 * Every client that joined over Nearby receives a secret [token][slotForToken]; the first
 * frame on its socket must carry that token, which is how a socket is bound to a player
 * slot. Until the socket arrives, snapshots keep flowing over Nearby, so a phone that
 * cannot open a socket simply stays on the old path.
 *
 * Pure JVM (no Android types) so the accept/handshake/frame path is unit tested.
 */
class HostSocketServer(
    private val onFrame: (slot: Int, bytes: ByteArray) -> Unit,
    private val onSlotChanged: (slot: Int, connected: Boolean) -> Unit = { _, _ -> },
    private val slotForToken: (token: Long) -> Int?,
    private val preferredPort: Int = 0,
) {
    private class Link(val socket: Socket, val out: BufferedOutputStream) {
        val writeLock = Any()

        @Volatile
        var alive = true
    }

    @Volatile
    private var running = false
    private var server: ServerSocket? = null
    private val links = ConcurrentHashMap<Int, Link>()
    private val actualPort = AtomicInteger(preferredPort)

    /** The port clients must use; valid after [start] succeeded. */
    val port: Int get() = actualPort.get()

    val isRunning: Boolean get() = running

    val activeSlots: Int get() = links.size

    fun start(): Boolean {
        if (running) return true
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(preferredPort))
            }
        } catch (t: Throwable) {
            return false
        }
        server = socket
        actualPort.set(socket.localPort)
        running = true
        thread(name = "FogSocketAccept", isDaemon = true) { acceptLoop(socket) }
        thread(name = "FogSocketPing", isDaemon = true) { pingLoop() }
        return true
    }

    /**
     * Writes [bytes] to the player's socket. `false` means the caller should fall back to
     * Nearby for this message (no direct link, or the link just died).
     */
    fun send(slot: Int, bytes: ByteArray): Boolean {
        val link = links[slot] ?: return false
        return try {
            synchronized(link.writeLock) { SocketFrames.write(link.out, bytes) }
            true
        } catch (t: Throwable) {
            link.alive = false
            links.remove(slot, link)
            closeLink(link)
            false
        }
    }

    fun hasSlot(slot: Int): Boolean = links.containsKey(slot)

    /** Closes a player's socket (kicked, disconnected, round host reset). */
    fun dropSlot(slot: Int) {
        links.remove(slot)?.let { closeLink(it) }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Throwable) {
        }
        server = null
        for (link in links.values) closeLink(link)
        links.clear()
    }

    // --------------------------------------------------------------- internals

    private fun acceptLoop(serverSocket: ServerSocket) {
        while (running) {
            val socket = try {
                serverSocket.accept()
            } catch (t: Throwable) {
                if (!running) return
                continue
            }
            thread(name = "FogSocketClient", isDaemon = true) { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        var slot = -1
        var link: Link? = null
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val handshake = SocketFrames.readHandshake(input)
            if (handshake == null || handshake.version != Protocol.VERSION) {
                SocketFrames.writeAck(output, accepted = false)
                return
            }
            val resolved = slotForToken(handshake.token)
            if (resolved == null) {
                SocketFrames.writeAck(output, accepted = false)
                return
            }
            val current = Link(socket, output)
            link = current
            slot = resolved
            links.put(slot, current)?.let { old ->
                old.alive = false
                closeLink(old)
            }
            SocketFrames.writeAck(output, accepted = true)
            socket.soTimeout = 0
            onSlotChanged(slot, true)
            while (running && current.alive) {
                val bytes = SocketFrames.read(input) ?: break
                if (bytes.isNotEmpty()) onFrame(slot, bytes)
            }
        } catch (_: IOException) {
        } catch (_: Throwable) {
        } finally {
            val current = link
            if (slot >= 0 && current != null) {
                // A newer socket for the same slot must not be reported as lost.
                if (links.remove(slot, current)) onSlotChanged(slot, false)
            }
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun pingLoop() {
        while (running) {
            try {
                Thread.sleep(PING_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (!running) return
            for ((slot, link) in links) {
                try {
                    synchronized(link.writeLock) { SocketFrames.write(link.out, SocketFrames.KEEPALIVE) }
                } catch (_: Throwable) {
                    link.alive = false
                    links.remove(slot, link)
                    closeLink(link)
                }
            }
        }
    }

    private fun closeLink(link: Link) {
        try {
            link.socket.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val PING_INTERVAL_MS = 1_000L

        /**
         * Private IPv4 addresses a peer can actually reach: the Wi-Fi Direct interface
         * Nearby brought up, the local network, or this phone's hotspot. Public/cellular
         * addresses are dropped so clients do not waste dial attempts on them. Pure JVM so
         * tests can call it.
         */
        fun localIpv4Addresses(): List<String> {
            val result = LinkedHashSet<String>()
            try {
                for (nif in NetworkInterface.getNetworkInterfaces()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (address in nif.inetAddresses) {
                        val ipv4 = address as? Inet4Address ?: continue
                        val host = ipv4.hostAddress ?: continue
                        if (!isPrivateIpv4(host)) continue
                        result.add(host)
                    }
                }
            } catch (_: Throwable) {
            }
            return result.toList()
        }

        /** RFC 1918 only; Wi-Fi Direct (192.168.49.x) is inside 192.168.0.0/16. */
        internal fun isPrivateIpv4(host: String): Boolean {
            val parts = host.split('.')
            if (parts.size != 4) return false
            val a = parts[0].toIntOrNull() ?: return false
            val b = parts[1].toIntOrNull() ?: return false
            if (a == 10) return true
            if (a == 192 && b == 168) return true
            return a == 172 && b in 16..31
        }
    }
}
