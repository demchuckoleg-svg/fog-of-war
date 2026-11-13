package com.fogofwar.game.core

/**
 * Host-chosen rules for one round. They travel inside Roster and StartRound, so every
 * client renders the same mode without simulating it.
 */
data class RoundRules(
    val teams: Boolean = false,
    /** 1 = classic one life, 3/5 = respawns. */
    val lives: Int = 1,
    /** 0 = no time limit. */
    val timeLimitSec: Int = 0,
    val pickups: Boolean = true,
) {
    fun sanitized(): RoundRules = copy(
        lives = lives.coerceIn(1, GameConfig.MAX_LIVES),
        timeLimitSec = timeLimitSec.coerceIn(0, 1800),
    )

    companion object {
        val CLASSIC = RoundRules()
    }
}
