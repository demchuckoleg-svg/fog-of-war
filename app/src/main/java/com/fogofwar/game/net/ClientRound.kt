package com.fogofwar.game.net

import com.fogofwar.game.core.GameConfig
import com.fogofwar.game.core.TileMap
import com.fogofwar.game.core.lerpAngle

/**
 * Client side of a round: stores the snapshots that arrive from the host and
 * renders a slightly delayed interpolated frame, so 15 Hz snapshots look smooth.
 */
class ClientRound(
    private val map: TileMap,
    val mapId: Int,
    private val mySlot: Int,
    private val players: List<PlayerInfo>,
) {
    private class Timed(val snapshot: Snapshot, val atMs: Long)

    private val buffer = ArrayList<Timed>()

    @Volatile
    private var roundOver = false

    @Volatile
    private var winnerName: String? = null

    fun onSnapshot(snapshot: Snapshot) {
        for (change in snapshot.tileChanges) map.applyChange(change)
        val now = System.nanoTime() / 1_000_000L
        synchronized(buffer) {
            buffer.add(Timed(snapshot, now))
            while (buffer.size > 12) buffer.removeAt(0)
        }
    }

    fun setRoundOver(name: String?) {
        winnerName = name
        roundOver = true
    }

    fun frameAt(nowMs: Long): GameFrame? {
        val copy = synchronized(buffer) { ArrayList(buffer) }
        if (copy.isEmpty()) return null

        val target = nowMs - GameConfig.INTERPOLATION_DELAY_MS
        var index = copy.indexOfFirst { it.atMs >= target }
        if (index < 0) index = copy.lastIndex
        val b = copy[index]
        val a = if (index > 0) copy[index - 1] else b
        val span = (b.atMs - a.atMs).toFloat()
        val alpha = if (span <= 1f) 1f else ((target - a.atMs) / span).coerceIn(0f, 1f)

        val tanks = b.snapshot.tanks.map { tb ->
            val ta = a.snapshot.tanks.firstOrNull { it.slot == tb.slot }
            val info = players.firstOrNull { it.slot == tb.slot }
            RenderTank(
                slot = tb.slot,
                name = info?.name ?: "?",
                x = if (ta != null) lerp(ta.x, tb.x, alpha) else tb.x,
                y = if (ta != null) lerp(ta.y, tb.y, alpha) else tb.y,
                dir = if (ta != null) lerpAngle(ta.dir, tb.dir, alpha) else tb.dir,
                hp = tb.hp,
                alive = tb.alive,
                isBot = info?.isBot ?: false,
            )
        }

        return GameFrame(
            map = map,
            mapId = mapId,
            mySlot = mySlot,
            players = players,
            tanks = tanks,
            bullets = b.snapshot.bullets,
            zoneRadius = b.snapshot.zoneRadius,
            sonarRadius = b.snapshot.sonarRadius,
            sonarActive = b.snapshot.sonarActive,
            roundOver = roundOver,
            winnerName = winnerName,
            tick = b.snapshot.tick,
        )
    }

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t
}
