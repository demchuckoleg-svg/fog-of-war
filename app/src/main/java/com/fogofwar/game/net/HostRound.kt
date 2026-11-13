package com.fogofwar.game.net

import com.fogofwar.game.core.EffectEvent
import com.fogofwar.game.core.GameConfig
import com.fogofwar.game.core.GameWorld
import com.fogofwar.game.core.MapGenerator
import com.fogofwar.game.core.RoundRules
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
    private val rules: RoundRules = RoundRules.CLASSIC,
) {
    val world: GameWorld = GameWorld(MapGenerator.generate(mapId, seed), mapId, seed, rules)

    @Volatile
    var frame: GameFrame? = null
        private set

    @Volatile
    var finished: Boolean = false
        private set

    @Volatile
    private var aborted: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var tick = 0L
    private var overTicks = -1L
    private var startAtNanos = 0L
    private val snapshotEvents = ArrayList<EffectEvent>()
    private val snapshotKills = ArrayList<KillSnap>()

    init {
        for (player in players) {
            if (player.isBot) {
                world.addBot(player.slot, player.name, player.team)
            } else {
                world.addPlayer(player.slot, player.name, team = player.team)
            }
        }
    }

    fun start() {
        // Everybody gets a short 3-2-1 freeze before the first simulation tick.
        startAtNanos = System.nanoTime() + GameConfig.ROUND_COUNTDOWN_MS * 1_000_000L
        frame = buildFrame()
        scope.launch { loop() }
    }

    /** Re-sends the round setup to a client that missed the original broadcast. */
    internal fun startMessage(): ByteArray = Protocol.startRound(mapId, seed, players, rules)

    fun stop() {
        aborted = true
        finished = true
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun loop() {
        try {
            runLoopBody()
        } catch (t: Throwable) {
            net.reportError("Ошибка раунда: ${t.message}")
        } finally {
            if (!aborted) {
                net.onHostRoundFinished(this, world.winnerSlot, world.winnerName)
            }
        }
    }

    private suspend fun runLoopBody() {
        var next = System.nanoTime()
        val tickNanos = 1_000_000_000L / GameConfig.TICK_HZ
        val snapshotEvery = (GameConfig.TICK_HZ / GameConfig.SNAPSHOT_HZ).coerceAtLeast(1)
        while (!finished) {
            val countdownLeft = startAtNanos - System.nanoTime()
            if (countdownLeft > 0L) {
                delay((countdownLeft / 1_000_000L).coerceIn(1L, 50L))
                next = System.nanoTime()
                continue
            }
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

            val effects = world.drainEffects()
            if (effects.isNotEmpty()) {
                net.pushEffects(effects)
                snapshotEvents.addAll(effects)
            }

            val kills = world.drainKills()
            if (kills.isNotEmpty()) {
                net.pushKills(kills)
                for (kill in kills) snapshotKills.add(KillSnap(kill.killer, kill.victim))
            }

            val changes = world.drainTileChanges()
            if (changes.isNotEmpty()) {
                var start = 0
                while (start < changes.size) {
                    val end = minOf(start + TILE_SYNC_CHUNK, changes.size)
                    net.broadcastRaw(Protocol.tileSync(changes.subList(start, end)))
                    start = end
                }
            }

            frame = buildFrame()

            // 30 ticks/s simulation, SNAPSHOT_HZ snapshots/s: plenty for smooth interpolation.
            if (tick % snapshotEvery == 0L) {
                val snapshot = Snapshot(
                    tick = tick,
                    tanks = world.tanks.map {
                        TankSnap(
                            slot = it.slot,
                            x = it.x,
                            y = it.y,
                            bodyDir = it.bodyDir,
                            turretDir = it.turretDir,
                            hp = it.hp,
                            flags = it.flagBits(),
                            grenades = it.grenades,
                            cooldown = it.cooldownFraction(),
                            grenadeRecharge = (it.grenadeRecharge / GameConfig.GRENADE_RECHARGE_TIME)
                                .coerceIn(0f, 1f),
                            team = it.team,
                            lives = it.lives,
                            powerLeft = if (GameConfig.POWER_DURATION > 0f) {
                                (it.powerTime / GameConfig.POWER_DURATION).coerceIn(0f, 1f)
                            } else 0f,
                            lightLeft = if (GameConfig.LIGHT_BOOST_DURATION > 0f) {
                                (it.lightTime / GameConfig.LIGHT_BOOST_DURATION).coerceIn(0f, 1f)
                            } else 0f,
                        )
                    },
                    bullets = world.bullets.map { BulletSnap(it.x, it.y) },
                    pickups = world.pickups.map { PickupSnap(it.type, it.x, it.y) },
                    grenades = world.grenades.map { GrenadeSnap(it.x, it.y, it.landed, it.owner) },
                    zoneRadius = world.zoneRadius,
                    sonarRadius = world.sonarRadius,
                    sonarActive = world.sonarActive,
                    events = ArrayList(snapshotEvents),
                    kills = ArrayList(snapshotKills),
                )
                snapshotEvents.clear()
                snapshotKills.clear()
                try {
                    net.broadcastRaw(Protocol.snapshot(snapshot))
                } catch (t: Throwable) {
                    net.reportError("Ошибка отправки снапшота: ${t.message}")
                }
            }

            if (world.roundOver) {
                if (overTicks < 0L) {
                    overTicks = tick
                    net.broadcastRaw(
                        Protocol.roundOver(
                            winnerSlot = world.winnerSlot,
                            winnerTeam = world.winnerTeam,
                            winnerName = world.winnerName ?: "",
                            players = players,
                            results = roundResults(),
                        )
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
    }

    private fun buildFrame(): GameFrame {
        val tanks = world.tanks.map { tank ->
            RenderTank(
                slot = tank.slot,
                name = tank.name,
                x = tank.x,
                y = tank.y,
                bodyDir = tank.bodyDir,
                turretDir = tank.turretDir,
                hp = tank.hp,
                alive = tank.alive,
                isBot = tank.isBot,
                lightsOn = tank.lightsOn,
                moving = tank.moving,
                invulnerable = tank.invuln > 0f,
                powered = tank.powerTime > 0f,
                lightBoost = tank.lightTime > 0f,
                grenades = tank.grenades,
                cooldown = tank.cooldownFraction(),
                grenadeRecharge = (tank.grenadeRecharge / GameConfig.GRENADE_RECHARGE_TIME)
                    .coerceIn(0f, 1f),
                team = tank.team,
                lives = tank.lives,
                powerLeft = if (GameConfig.POWER_DURATION > 0f) {
                    (tank.powerTime / GameConfig.POWER_DURATION).coerceIn(0f, 1f)
                } else 0f,
                lightLeft = if (GameConfig.LIGHT_BOOST_DURATION > 0f) {
                    (tank.lightTime / GameConfig.LIGHT_BOOST_DURATION).coerceIn(0f, 1f)
                } else 0f,
            )
        }
        return GameFrame(
            map = world.map,
            mapId = mapId,
            mySlot = 0,
            players = players,
            tanks = tanks,
            bullets = world.bullets.map { BulletSnap(it.x, it.y) },
            pickups = world.pickups.map { PickupSnap(it.type, it.x, it.y) },
            grenades = world.grenades.map { GrenadeSnap(it.x, it.y, it.landed, it.owner) },
            zoneRadius = world.zoneRadius,
            sonarRadius = world.sonarRadius,
            sonarActive = world.sonarActive,
            roundOver = world.roundOver,
            winnerName = world.winnerName,
            winnerSlot = world.winnerSlot,
            tick = tick,
            rules = rules,
            winnerTeam = world.winnerTeam,
            results = if (world.roundOver) roundResults() else emptyList(),
        )
    }

    private fun roundResults(): List<RoundResult> = world.tanks
        .map { RoundResult(it.slot, it.kills, it.deaths, it.damageDealt) }
        .sortedWith(compareByDescending<RoundResult> { it.kills }.thenByDescending { it.damage })

    private companion object {
        /** Reliable payloads stay small; a long grenade chain is split into several messages. */
        const val TILE_SYNC_CHUNK = 200
    }
}
