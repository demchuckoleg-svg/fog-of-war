package com.fogofwar.game.net

import com.fogofwar.game.core.GameConfig
import com.fogofwar.game.core.GameWorld
import com.fogofwar.game.core.MapGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Host side of a round: authoritative fixed-step simulation, snapshot broadcast
 * and the short "round over" freeze before everybody returns to the persistent lobby.
 */
class HostRound(
    private val net: NearbyNet,
    val mapId: Int,
    val seed: Long,
    private val players: List<PlayerInfo>,
) {
    val world: GameWorld = GameWorld(MapGenerator.generate(mapId, seed), mapId, seed)

    @Volatile
    var frame: GameFrame? = null
        private set

    @Volatile
    var finished: Boolean = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var tick = 0L
    private var overTicks = -1L

    init {
        for (player in players) {
            if (player.isBot) world.addBot(player.slot, player.name)
            else world.addPlayer(player.slot, player.name)
        }
    }

    fun start() {
        scope.launch { loop() }
    }

    fun stop() {
        finished = true
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun loop() {
        var next = System.nanoTime()
        val tickNanos = 1_000_000_000L / GameConfig.TICK_HZ
        while (!finished) {
            val now = System.nanoTime()
            if (now < next) {
                delay(((next - now) / 1_000_000L).coerceAtLeast(1L))
                continue
            }
            try {
                world.step(GameConfig.TICK_DT)
                tick++
            } catch (t: Throwable) {
                net.reportError("Ошибка симуляции: ${t.message}")
                finished = true
                break
            }

            frame = buildFrame()

            // 30 ticks/s simulation, ~15 snapshots/s: plenty for smooth interpolation.
            if (tick % 2L == 0L) {
                val changes = world.drainTileChanges()
                val snapshot = Snapshot(
                    tick = tick,
                    tanks = world.tanks.map { TankSnap(it.slot, it.x, it.y, it.dir, it.hp, it.alive) },
                    bullets = world.bullets.map { BulletSnap(it.x, it.y) },
                    zoneRadius = world.zoneRadius,
                    sonarRadius = world.sonarRadius,
                    sonarActive = world.sonarActive,
                    tileChanges = changes,
                )
                net.broadcastRaw(Protocol.snapshot(snapshot))
            }

            if (world.roundOver) {
                if (overTicks < 0L) {
                    overTicks = tick
                    net.broadcastRaw(
                        Protocol.roundOver(world.winnerSlot, world.winnerName ?: "", players)
                    )
                }
                if (tick - overTicks >= (GameConfig.ROUND_OVER_DELAY * GameConfig.TICK_HZ).toLong()) {
                    finished = true
                    break
                }
            }

            next += tickNanos
            if (now - next > 8 * tickNanos) next = now
        }
        net.onHostRoundFinished(world.winnerSlot, world.winnerName)
    }

    private fun buildFrame(): GameFrame {
        val tanks = world.tanks.map { tank ->
            RenderTank(
                slot = tank.slot,
                name = tank.name,
                x = tank.x,
                y = tank.y,
                dir = tank.dir,
                hp = tank.hp,
                alive = tank.alive,
                isBot = tank.isBot,
            )
        }
        return GameFrame(
            map = world.map,
            mapId = mapId,
            mySlot = 0,
            players = players,
            tanks = tanks,
            bullets = world.bullets.map { BulletSnap(it.x, it.y) },
            zoneRadius = world.zoneRadius,
            sonarRadius = world.sonarRadius,
            sonarActive = world.sonarActive,
            roundOver = world.roundOver,
            winnerName = world.winnerName,
            tick = tick,
        )
    }
}
