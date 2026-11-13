package com.fogofwar.game.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the direct channel without Android: real loopback sockets, real framing,
 * real handshake. The Wi-Fi Direct address binding lives in AndroidDirectConnector and is
 * verified on devices; everything below it is covered here.
 */
class SocketTransportTest {

    @Test
    fun frameRoundTrip() {
        val out = java.io.ByteArrayOutputStream()
        val payload = Protocol.mapSelect(4)
        SocketFrames.write(out, payload)
        val input = out.toByteArray().inputStream()
        val read = SocketFrames.read(input)
        assertNotNull(read)
        assertEquals(4, (Protocol.parse(read!!) as NetMessage.MapSelect).mapId)
        assertNull(SocketFrames.read(input))
    }

    @Test
    fun keepaliveIsAFrameNotAMessage() {
        val out = java.io.ByteArrayOutputStream()
        SocketFrames.write(out, SocketFrames.KEEPALIVE)
        val read = SocketFrames.read(out.toByteArray().inputStream())
        assertEquals(0, read?.size)
        assertNull(Protocol.parse(read!!))
    }

    @Test
    fun hostAcceptsHandshakeAndRoutesFrames() {
        val received = LinkedBlockingQueue<Pair<Int, ByteArray>>()
        val server = HostSocketServer(
            onFrame = { slot, bytes -> received.add(slot to bytes) },
            slotForToken = { token -> if (token == 42L) 1 else null },
        )
        assertTrue(server.start())
        val socket = Socket("127.0.0.1", server.port)
        try {
            socket.soTimeout = 4_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            SocketFrames.writeHandshake(output, Protocol.VERSION, 42L)
            assertTrue(SocketFrames.readAck(input) == true)

            val inputMessage = Protocol.input(7, 0.5f, -0.5f, 1f, 0f, fire = true, lights = false, throwGrenade = false)
            SocketFrames.write(output, inputMessage)
            val (slot, bytes) = received.poll(3, TimeUnit.SECONDS)!!
            assertEquals(1, slot)
            val parsed = Protocol.parse(bytes)
            assertTrue(parsed is NetMessage.InputMsg)
            assertEquals(7, (parsed as NetMessage.InputMsg).seq)
            assertTrue(parsed.fire)

            val outbound = Protocol.mapSelect(3)
            assertTrue(server.send(1, outbound))
            val frame = readMessage(input, 3_000)
            assertNotNull(frame)
            assertEquals(3, (Protocol.parse(frame!!) as NetMessage.MapSelect).mapId)
        } finally {
            socket.close()
            server.stop()
        }
    }

    @Test
    fun hostRejectsUnknownToken() {
        val server = HostSocketServer(
            onFrame = { _, _ -> },
            slotForToken = { 1L },
        )
        assertTrue(server.start())
        val socket = Socket("127.0.0.1", server.port)
        try {
            socket.soTimeout = 4_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            SocketFrames.writeHandshake(output, Protocol.VERSION, 999L)
            assertTrue(SocketFrames.readAck(input) == false)
            assertEquals(0, server.activeSlots)
        } finally {
            socket.close()
            server.stop()
        }
    }

    @Test
    fun clientLinkConnectsSendsAndReceives() {
        val serverFrames = LinkedBlockingQueue<ByteArray>()
        val server = HostSocketServer(
            onFrame = { _, bytes -> serverFrames.add(bytes) },
            slotForToken = { token -> if (token == 777L) 2 else null },
        )
        assertTrue(server.start())
        val clientFrames = LinkedBlockingQueue<ByteArray>()
        val link = ClientSocketLink(
            connector = DirectConnector { candidates, timeout ->
                try {
                    Socket().apply { connect(candidates.first(), timeout) }
                } catch (_: Throwable) {
                    null
                }
            },
            onFrame = { bytes -> clientFrames.add(bytes) },
        )
        try {
            link.updateOffer(777L, server.port, listOf("127.0.0.1"))
            waitUntil(3_000) { link.isConnected }
            assertTrue("socket link did not connect", link.isConnected)

            assertTrue(link.send(Protocol.mapSelect(5)))
            val fromClient = serverFrames.poll(3, TimeUnit.SECONDS)
            assertNotNull(fromClient)
            assertEquals(5, (Protocol.parse(fromClient!!) as NetMessage.MapSelect).mapId)

            assertTrue(server.send(2, Protocol.roomClosed("bye")))
            val fromServer = clientFrames.poll(3, TimeUnit.SECONDS)
            assertNotNull(fromServer)
            assertEquals("bye", (Protocol.parse(fromServer!!) as NetMessage.RoomClosed).reason)
        } finally {
            link.close()
            server.stop()
        }
    }

    private fun readMessage(input: InputStream, timeoutMs: Int): ByteArray? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val bytes = SocketFrames.read(input) ?: return null
            if (bytes.isNotEmpty()) return bytes
        }
        return null
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
    }
}
