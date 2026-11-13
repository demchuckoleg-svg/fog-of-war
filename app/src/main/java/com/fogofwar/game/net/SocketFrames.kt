package com.fogofwar.game.net

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Framing for the direct TCP channel.
 *
 * TCP is a byte stream while [Protocol] messages are discrete, so every message travels as
 * a 4-byte big-endian length followed by the message bytes. A client starts with a fixed
 * handshake (magic, protocol version, secret room token) and the host answers with a
 * one-byte accept/reject. Zero-length frames are keepalives and are never parsed.
 *
 * The code is pure JVM on purpose: the same framing is exercised by unit tests.
 */
object SocketFrames {
    /** "FOGS" — distinguishes our channel from random connections on the same port. */
    const val MAGIC = 0x464F4753

    const val MAX_FRAME_BYTES = 64 * 1024

    /** magic (4) + version (1) + token (8). */
    const val HANDSHAKE_BYTES = 13

    /** magic (4) + accepted (1). */
    const val ACK_BYTES = 5

    /** Sent by the host every second so the client can tell a quiet lobby from a dead link. */
    val KEEPALIVE = ByteArray(0)

    data class Handshake(val version: Int, val token: Long)

    fun writeHandshake(out: OutputStream, version: Int, token: Long) {
        val buffer = ByteBuffer.allocate(HANDSHAKE_BYTES)
        buffer.putInt(MAGIC)
        buffer.put(version.toByte())
        buffer.putLong(token)
        out.write(buffer.array())
        out.flush()
    }

    /** `null` means the peer closed the connection or sent something that is not a handshake. */
    fun readHandshake(input: InputStream): Handshake? {
        val bytes = readExactly(input, HANDSHAKE_BYTES) ?: return null
        val buffer = ByteBuffer.wrap(bytes)
        if (buffer.int != MAGIC) return null
        return Handshake(version = buffer.get().toInt() and 0xFF, token = buffer.long)
    }

    fun writeAck(out: OutputStream, accepted: Boolean) {
        val buffer = ByteBuffer.allocate(ACK_BYTES)
        buffer.putInt(MAGIC)
        buffer.put(if (accepted) 1 else 0)
        out.write(buffer.array())
        out.flush()
    }

    /** `null` on EOF/bad magic; the boolean is the host's verdict on the token. */
    fun readAck(input: InputStream): Boolean? {
        val bytes = readExactly(input, ACK_BYTES) ?: return null
        val buffer = ByteBuffer.wrap(bytes)
        if (buffer.int != MAGIC) return null
        return buffer.get().toInt() != 0
    }

    @Throws(IOException::class)
    fun write(out: OutputStream, bytes: ByteArray) {
        if (bytes.size > MAX_FRAME_BYTES) throw IOException("frame too large: ${bytes.size}")
        val header = ByteBuffer.allocate(4).putInt(bytes.size).array()
        out.write(header)
        out.write(bytes)
        out.flush()
    }

    /** `null` on a clean EOF; throws on a malformed frame length. */
    @Throws(IOException::class)
    fun read(input: InputStream): ByteArray? {
        val header = readExactly(input, 4) ?: return null
        val length = ByteBuffer.wrap(header).int
        if (length < 0 || length > MAX_FRAME_BYTES) throw IOException("bad frame length $length")
        if (length == 0) return KEEPALIVE
        return readExactly(input, length) ?: throw EOFException("short frame")
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray? {
        val bytes = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = input.read(bytes, read, size - read)
            if (count < 0) return null
            read += count
        }
        return bytes
    }
}
