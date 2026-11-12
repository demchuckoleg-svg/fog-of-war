package com.fogofwar.game.net

import kotlin.random.Random

/**
 * Compact room description carried in the Nearby advertising endpoint name.
 *
 * Nearby Connections gives no data channel before a connection is established, so the
 * endpoint name is the only thing a searching phone can read about a room. It must stay
 * inside 64 UTF-8 bytes (Cyrillic takes 2 bytes per character):
 *
 * `name|mapId|players|state|code` — for example `Олег|2|3|L|K7Q`.
 *
 * `state` is `L` for a lobby and `G` for a running round. A plain legacy nickname (no
 * separators) does not parse; callers then fall back to showing it as-is.
 */
data class RoomDescriptor(
    val name: String,
    val mapId: Int,
    val players: Int,
    val inGame: Boolean,
    val code: String = "",
) {
    fun encode(): String {
        var safeName = sanitizeName(name)
        while (true) {
            val encoded = buildString {
                append(safeName)
                append(SEPARATOR)
                append(mapId.coerceAtLeast(0))
                append(SEPARATOR)
                append(players.coerceIn(1, MAX_PLAYERS))
                append(SEPARATOR)
                append(if (inGame) STATE_GAME else STATE_LOBBY)
                append(SEPARATOR)
                append(sanitizeCode(code))
            }
            if (encoded.utf8Size() <= MAX_BYTES) return encoded
            if (safeName.isEmpty()) return encoded
            safeName = safeName.dropLastCodePoint()
        }
    }

    companion object {
        /** Nearby advertising endpoint names are short; keep a safe margin for UTF-8. */
        const val MAX_BYTES = 64
        const val MAX_NAME_CHARS = 12
        const val MAX_CODE_CHARS = 4
        const val CODE_LENGTH = 3
        private const val MAX_PLAYERS = 6
        private const val SEPARATOR = '|'
        private const val STATE_LOBBY = 'L'
        private const val STATE_GAME = 'G'

        /** No look-alike characters: easy to read aloud to a friend. */
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        fun newCode(random: Random = Random.Default): String = buildString(CODE_LENGTH) {
            repeat(CODE_LENGTH) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) }
        }

        fun parse(raw: String): RoomDescriptor? {
            val parts = raw.trim().split(SEPARATOR)
            if (parts.size != 5) return null
            val name = parts[0].trim()
            if (name.isEmpty()) return null
            val mapId = parts[1].toIntOrNull() ?: return null
            val players = parts[2].toIntOrNull() ?: return null
            val inGame = when (parts[3].trim().uppercase()) {
                STATE_GAME.toString() -> true
                STATE_LOBBY.toString() -> false
                else -> return null
            }
            return RoomDescriptor(
                name = name.takeCodePoints(MAX_NAME_CHARS),
                mapId = mapId.coerceAtLeast(0),
                players = players.coerceIn(1, MAX_PLAYERS),
                inGame = inGame,
                code = sanitizeCode(parts[4]),
            )
        }

        private fun sanitizeName(raw: String): String {
            val cleaned = buildString(raw.length) {
                for (ch in raw) {
                    append(if (ch == SEPARATOR || ch.isISOControl()) ' ' else ch)
                }
            }
            return cleaned.replace(WHITESPACE, " ").trim().takeCodePoints(MAX_NAME_CHARS).ifBlank { "Игрок" }
        }

        private fun sanitizeCode(raw: String): String =
            raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(MAX_CODE_CHARS)

        private val WHITESPACE = Regex("\\s+")
    }
}

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

private fun String.takeCodePoints(max: Int): String {
    if (codePointCount(0, length) <= max) return this
    return substring(0, offsetByCodePoints(0, max))
}

private fun String.dropLastCodePoint(): String {
    if (isEmpty()) return this
    return substring(0, offsetByCodePoints(length, -1))
}
