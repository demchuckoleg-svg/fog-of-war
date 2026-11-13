package com.fogofwar.game.net

import com.fogofwar.game.core.GameConfig
import com.fogofwar.game.core.RoundRules
import com.fogofwar.game.core.TileChange
import com.fogofwar.game.core.TileMap
import com.fogofwar.game.core.lerpAngle

/** Diagnostics for the perf overlay: is the client stuttering because packets are late? */
data class ClientNetStats(
    val lastGapMs: Long,
    val maxGapMs: Long,
    val recentMaxGapMs: Long,
    val delayMs: Int,
    val starvedPercent: Int,
    val buffered: Int,
)

/**
 * Client side of a round: stores the snapshots that arrive from the host and
 * renders a slightly delayed interpolated frame, so 15 Hz snapshots look smooth.
 */
class ClientRound(
    private val map: TileMap,
    val mapId: Int,
    private val mySlot: Int,
    private val players: List<PlayerInfo>,
    private val rules: RoundRules = RoundRules.CLASSIC,
) {
    private class Timed(val snapshot: Snapshot, val atMs: Long)

    private val buffer = arrayOfNulls<Timed>(12)
    private var bufferStart = 0
    private var bufferSize = 0
    private val scratch = ArrayList<Timed>(12)
    private val pendingTileChanges = java.util.concurrent.ConcurrentLinkedQueue<TileChange>()
    private val playersBySlot: Map<Int, PlayerInfo> = players.associateBy { it.slot }

    /** Local render timeline in host simulation ticks; advances independently of packet jitter. */
    private var renderTick = Double.NaN
    private var lastFrameAtMs = -1L
    private var snapshotsReceived = 0
    private var lastGapMs = 0L
    private var maxGapMs = 0L
    private var recentMaxGapMs = 0L
    private var currentDelayMs = GameConfig.INTERPOLATION_DELAY_MS.toInt()
    private var starvedFrames = 0
    private var totalFrames = 0

    @Volatile
    private var roundOver = false

    val roundFinished: Boolean get() = roundOver

    @Volatile
    private var winnerName: String? = null

    @Volatile
    private var winnerSlot = -1

    @Volatile
    private var winnerTeam = -1

    @Volatile
    private var results: List<RoundResult> = emptyList()

    /** Last snapshot arrival, used by the host-silence watchdog. */
    @Volatile
    var lastSnapshotAt: Long = System.nanoTime() / 1_000_000L
        private set

    fun onSnapshot(snapshot: Snapshot) {
        val now = System.nanoTime() / 1_000_000L
        if (snapshotsReceived > 0) {
            val gap = now - lastSnapshotAt
            if (gap in 0L..5_000L) {
                lastGapMs = gap
                if (gap > maxGapMs) maxGapMs = gap
                recentMaxGapMs = maxOf(gap, (recentMaxGapMs * 0.96).toLong())
            }
        }
        snapshotsReceived++
        lastSnapshotAt = now
        synchronized(buffer) {
            val index = (bufferStart + bufferSize) % buffer.size
            buffer[index] = Timed(snapshot, now)
            if (bufferSize < buffer.size) {
                bufferSize++
            } else {
                bufferStart = (bufferStart + 1) % buffer.size
            }
        }
    }

    fun stats(): ClientNetStats = ClientNetStats(
        lastGapMs = lastGapMs,
        maxGapMs = maxGapMs,
        recentMaxGapMs = recentMaxGapMs,
        delayMs = currentDelayMs,
        starvedPercent = if (totalFrames > 0) starvedFrames * 100 / totalFrames else 0,
        buffered = bufferSize,
    )

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

    fun setRoundOver(
        slot: Int,
        team: Int,
        name: String?,
        results: List<RoundResult>,
    ) {
        winnerSlot = slot
        winnerTeam = team
        winnerName = name
        this.results = results
        roundOver = true
    }

    fun frameAt(nowMs: Long): GameFrame? {
        applyPendingTileChanges()
        val count = synchronized(buffer) {
            scratch.clear()
            for (i in 0 until bufferSize) {
                scratch.add(buffer[(bufferStart + i) % buffer.size] ?: break)
            }
            scratch.size
        }
        if (count == 0) return null

        val newestTick = scratch[count - 1].snapshot.tick
        val oldestTick = scratch[0].snapshot.tick
        currentDelayMs = (recentMaxGapMs * 0.8 + 70.0).toInt().coerceIn(100, 180)
        val delayTicks = currentDelayMs * GameConfig.TICK_HZ / 1000.0
        if (renderTick.isNaN()) {
            renderTick = newestTick - delayTicks
        } else {
            val elapsed = if (lastFrameAtMs > 0L) {
                (nowMs - lastFrameAtMs).coerceIn(0L, 250L)
            } else {
                0L
            }
            renderTick += elapsed * GameConfig.TICK_HZ / 1000.0
            val wanted = newestTick - delayTicks
            val drift = wanted - renderTick
            if (kotlin.math.abs(drift) > 10.0) {
                // Pause/resume or a long network stall: jump instead of speeding up for seconds.
                renderTick = wanted
            } else {
                renderTick += drift * 0.06
            }
        }
        lastFrameAtMs = nowMs
        if (renderTick < oldestTick.toDouble()) renderTick = oldestTick.toDouble()
        val starved = renderTick > newestTick.toDouble()
        if (starved) {
            renderTick = newestTick.toDouble()
            starvedFrames++
        }
        totalFrames++

        val targetTick = renderTick
        var index = -1
        for (i in 0 until count) {
            if (scratch[i].snapshot.tick >= targetTick) {
                index = i
                break
            }
        }
        if (index < 0) index = count - 1
        val b = scratch[index]
        val a = if (index > 0) scratch[index - 1] else b
        val span = (b.snapshot.tick - a.snapshot.tick).toFloat()
        val alpha = if (span <= 0f) {
            1f
        } else {
            ((targetTick - a.snapshot.tick) / span).toFloat().coerceIn(0f, 1f)
        }

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
                team = tb.team,
                lives = tb.lives,
                powerLeft = tb.powerLeft,
                lightLeft = tb.lightLeft,
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
            rules = rules,
            winnerTeam = winnerTeam,
            results = results,
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
