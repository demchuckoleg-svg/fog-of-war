package com.fogofwar.game.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomDescriptorTest {

    @Test
    fun roundTripKeepsEveryField() {
        val descriptor = RoomDescriptor("Олег", 2, 3, inGame = false, code = "K7Q")
        val encoded = descriptor.encode()

        assertEquals("Олег|2|3|L|K7Q", encoded)
        assertEquals(descriptor, RoomDescriptor.parse(encoded))
    }

    @Test
    fun cyrillicNameFitsAdvertisingLimit() {
        val encoded = RoomDescriptor("ОченьДлинныйНик", 3, 6, inGame = false, code = "ABCD").encode()

        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= RoomDescriptor.MAX_BYTES)
        assertNotNull(RoomDescriptor.parse(encoded))
    }

    @Test
    fun emojiNameFitsAndKeepsValidCodePoints() {
        val encoded = RoomDescriptor("🚀🚀🚀🚀🚀🚀🚀🚀", 1, 2, inGame = false, code = "XYZ").encode()

        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= RoomDescriptor.MAX_BYTES)
        val parsed = RoomDescriptor.parse(encoded)!!
        assertEquals(1, parsed.mapId)
        assertEquals(2, parsed.players)
        assertEquals("XYZ", parsed.code)
        assertFalse(parsed.name.isEmpty())
    }

    @Test
    fun separatorAndControlCharsInNameAreSanitized() {
        val parsed = RoomDescriptor.parse(
            RoomDescriptor("a|b\nc", 0, 1, inGame = false, code = "AB").encode()
        )!!

        assertEquals("a b c", parsed.name)
    }

    @Test
    fun gameStateParses() {
        val parsed = RoomDescriptor.parse("Хост|0|6|G|")!!

        assertTrue(parsed.inGame)
        assertEquals("", parsed.code)
        assertEquals(6, parsed.players)
    }

    @Test
    fun plainNicknameIsNotADescriptor() {
        assertNull(RoomDescriptor.parse("Танк"))
        assertNull(RoomDescriptor.parse("Олег|2|3|X|ABC"))
        assertNull(RoomDescriptor.parse("Олег|2|много|L|ABC"))
        assertNull(RoomDescriptor.parse("|2|3|L|ABC"))
    }

    @Test
    fun generatedCodeUsesReadableAlphabet() {
        repeat(50) {
            val code = RoomDescriptor.newCode()
            assertEquals(RoomDescriptor.CODE_LENGTH, code.length)
            assertTrue(code.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" })
        }
    }

    @Test
    fun endpointFallbackKeepsLegacyNickname() {
        val host = HostInfo.fromEndpoint("ep-1", "Танк")

        assertEquals("Танк", host.name)
        assertNull(host.mapId)
        assertEquals(0, host.players)
        assertFalse(host.inGame)
        assertEquals("", host.code)
    }

    @Test
    fun endpointDescriptorIsUnpacked() {
        val host = HostInfo.fromEndpoint("ep-2", "Олег|2|3|L|K7Q")

        assertEquals("Олег", host.name)
        assertEquals(2, host.mapId)
        assertEquals(3, host.players)
        assertFalse(host.inGame)
        assertEquals("K7Q", host.code)
    }
}
