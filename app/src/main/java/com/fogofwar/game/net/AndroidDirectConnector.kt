package com.fogofwar.game.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Resolves the socket-address problem of Wi-Fi Direct.
 *
 * A Nearby/Wi-Fi Direct network is never the default network, so a plain `Socket.connect()`
 * to `192.168.49.1` leaves through the wrong interface and fails. Every candidate is
 * therefore tried on every Wi-Fi/Ethernet [Network] through `Network.getSocketFactory()`,
 * plus once unbound for the "both phones on one router/hotspot" case. The first socket that
 * connects wins and the rest are closed.
 */
class AndroidDirectConnector(context: Context) : DirectConnector {
    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun connect(candidates: List<InetSocketAddress>, timeoutMs: Int): Socket? {
        if (candidates.isEmpty()) return null
        val manager = connectivity
        val attempts = ArrayList<() -> Socket?>()

        // Same LAN or hotspot: the default route already reaches the host.
        for (candidate in candidates.take(MAX_UNBOUND_CANDIDATES)) {
            attempts.add { openAndConnect(null, candidate, timeoutMs) }
        }

        val networks = manager?.allNetworks.orEmpty()
        for (network in networks) {
            if (attempts.size >= MAX_ATTEMPTS) break
            val capabilities = manager?.getNetworkCapabilities(network) ?: continue
            val usable = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!usable) continue
            val ordered = orderForNetwork(manager, network, candidates)
            for (candidate in ordered.take(MAX_PER_NETWORK)) {
                if (attempts.size >= MAX_ATTEMPTS) break
                attempts.add { openAndConnect(network, candidate, timeoutMs) }
            }
        }
        return race(attempts, timeoutMs)
    }

    /** Addresses in the same /24 as one of this network's own addresses are tried first. */
    private fun orderForNetwork(
        manager: ConnectivityManager,
        network: Network,
        candidates: List<InetSocketAddress>,
    ): List<InetSocketAddress> {
        val local = manager.getLinkProperties(network)
            ?.linkAddresses
            ?.mapNotNull { it.address as? Inet4Address }
            .orEmpty()
        if (local.isEmpty()) return candidates
        return candidates.sortedByDescending { candidate ->
            val remote = candidate.address as? Inet4Address ?: return@sortedByDescending 0
            if (local.any { sameSlash24(it.address, remote.address) }) 1 else 0
        }
    }

    private fun openAndConnect(
        network: Network?,
        address: InetSocketAddress,
        timeoutMs: Int,
    ): Socket? {
        val socket = try {
            network?.socketFactory?.createSocket() ?: Socket()
        } catch (_: Throwable) {
            return null
        }
        return try {
            socket.tcpNoDelay = true
            socket.connect(address, timeoutMs)
            socket
        } catch (_: Throwable) {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            null
        }
    }

    private fun race(attempts: List<() -> Socket?>, timeoutMs: Int): Socket? {
        if (attempts.isEmpty()) return null
        val winner = AtomicReference<Socket?>()
        val finished = CountDownLatch(1)
        val pending = AtomicInteger(attempts.size)
        for (attempt in attempts) {
            thread(name = "FogSocketDial", isDaemon = true) {
                var opened: Socket? = null
                try {
                    if (winner.get() == null) opened = attempt()
                    if (opened != null && winner.compareAndSet(null, opened)) {
                        finished.countDown()
                    } else {
                        try {
                            opened?.close()
                        } catch (_: Throwable) {
                        }
                    }
                } catch (_: Throwable) {
                    try {
                        opened?.close()
                    } catch (_: Throwable) {
                    }
                } finally {
                    if (pending.decrementAndGet() == 0) finished.countDown()
                }
            }
        }
        try {
            finished.await(timeoutMs + 300L, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
        return winner.get()
    }

    private fun sameSlash24(a: ByteArray, b: ByteArray): Boolean {
        if (a.size < 4 || b.size < 4) return false
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2]
    }

    private companion object {
        const val MAX_UNBOUND_CANDIDATES = 3
        const val MAX_PER_NETWORK = 2
        const val MAX_ATTEMPTS = 10
    }
}
