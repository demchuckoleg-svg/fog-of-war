package com.fogofwar.game.net

import com.fogofwar.game.core.GameConfig
import com.fogofwar.game.core.TileChange
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
    private val pendingTileChanges = java.util.concurrent.ConcurrentLinkedQueue<TileChange>()
    private val playersBySlot: Map<Int, PlayerInfo> = players.associateBy { it.slot }

    @Volatile
    private var roundOver = false

    @Volatile
    private var winnerName: String? = null

    @Volatile
    private var winnerSlot = -1

    /** Last snapshot arrival, used by the host-silence watchdog. */
    @Volatile
    var lastSnapshotAt: Long = System.nanoTime() / 1_000_000L
        private set

    fun onSnapshot(snapshot: Snapshot) {
        lastSnapshotAt = System.nanoTime() / 1_000_000L
        val now = System.nanoTime() / 1_000_000L
        synchronized(buffer) {
            buffer.add(Timed(snapshot, now))
            while (buffer.size > 12) buffer.removeAt(0)
        }
    }

    /** Tile changes arrive as reliable messages; the render thread applies them safely. */
    fun onTileChanges(changes: List<TileChange>) {
        for (change in changes) pendingTileChanges.add(change)
    }

    private fun applyPendingTileChanges() {
        while (true) {
            val change = pendingTileChanges.poll() ?: break
            map.applyChange(change)
        }
    }

    fun setRoundOver(slot: Int, name: String?) {
        winnerSlot = slot
        winnerName = name
        roundOver = true
    }

    fun frameAt(nowMs: Long): GameFrame? {
        applyPendingTileChanges()
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
            val info = playersBySlot[tb.slot]
            RenderTank(
                slot = tb.slot,
                name = info?.name ?: "?",
                x = if (ta != null) lerp(ta.x, tb.x, alpha) else tb.x,
                y = if (ta != null) lerp(ta.y, tb.y, alpha) else tb.y,
                bodyDir = if (ta != null) lerpAngle(ta.bodyDir, tb.bodyDir, alpha) else tb.bodyDir,
                turretDir = if (ta != null) lerpAngle(ta.turretDir, tb.turretDir, alpha) else tb.turretDir,
                hp = tb.hp,
                alive = tb.alive,
                isBot = info?.isBot ?: false,
                lightsOn = tb.lightsOn,
                moving = tb.moving,
                invulnerable = tb.invulnerable,
                powered = tb.powered,
                lightBoost = tb.lightBoost,
                grenades = tb.grenades,
                cooldown = tb.cooldown,
                grenadeRecharge = tb.grenadeRecharge,
            )
        }

        val bullets = interpolateBullets(a.snapshot.bullets, b.snapshot.bullets, alpha)

        return GameFrame(
            map = map,
            mapId = mapId,
            mySlot = mySlot,
            players = players,
            tanks = tanks,
            bullets = bullets,
            pickups = b.snapshot.pickups,
            grenades = b.snapshot.grenades,
            zoneRadius = b.snapshot.zoneRadius,
            sonarRadius = b.snapshot.sonarRadius,
            sonarActive = b.snapshot.sonarActive,
            roundOver = roundOver,
            winnerName = winnerName,
            winnerSlot = winnerSlot,
            tick = b.snapshot.tick,
        )
    }

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    /** Bullets have no ids, so interpolate by index only when the lists still line up. */
    private fun interpolateBullets(
        from: List<BulletSnap>,
        to: List<BulletSnap>,
        alpha: Float,
    ): List<BulletSnap> {
        if (from.size != to.size) return to
        if (alpha >= 1f || alpha <= 0f) return to
        return to.mapIndexed { index, bullet ->
            val previous = from[index]
            if (kotlin.math.hypot(previous.x - bullet.x, previous.y - bullet.y) > 90f) {
                bullet
            } else {
                BulletSnap(lerp(previous.x, bullet.x, alpha), lerp(previous.y, bullet.y, alpha))
            }
        }
    }
}
