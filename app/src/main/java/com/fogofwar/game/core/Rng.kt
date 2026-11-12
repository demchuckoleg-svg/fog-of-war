package com.fogofwar.game.core

/**
 * Tiny deterministic LCG. Deliberately not kotlin.random/java.util.Random so that
 * map generation is bit-identical on every device and JVM version.
 */
class Rng(seed: Long) {
    private var state: Long = if (seed == 0L) 0x2545F4914F6CDD1DL else seed

    fun nextLong(): Long {
        state = state * 6364136223846793005L + 1442695040888963407L
        return state
    }

    fun nextInt(bound: Int): Int {
        require(bound > 0)
        val v = (nextLong() ushr 33).toInt() and 0x7FFFFFFF
        return v % bound
    }

    fun nextInt(from: Int, until: Int): Int = from + nextInt(until - from)

    fun nextFloat(): Float = nextInt(1 shl 20) / (1 shl 20).toFloat()
}
