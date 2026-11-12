package com.fogofwar.game.net

import com.fogofwar.game.core.TileChange
import com.fogofwar.game.core.TileMap

data class TankSnap(
    val slot: Int,
    val x: Float,
    val y: Float,
    val dir: Float,
    val hp: Int,
    val alive: Boolean,
)

data class BulletSnap(val x: Float, val y: Float)

data class Snapshot(
    val tick: Long,
    val tanks: List<TankSnap>,
    val bullets: List<BulletSnap>,
    val zoneRadius: Float,
    val sonarRadius: Float,
    val sonarActive: Boolean,
    val tileChanges: List<TileChange>,
)

data class RenderTank(
    val slot: Int,
    val name: String,
    val x: Float,
    val y: Float,
    val dir: Float,
    val hp: Int,
    val alive: Boolean,
    val isBot: Boolean,
)

/** Immutable view of the world that the renderer consumes once per frame. */
data class GameFrame(
    val map: TileMap,
    val mapId: Int,
    val mySlot: Int,
    val players: List<PlayerInfo>,
    val tanks: List<RenderTank>,
    val bullets: List<BulletSnap>,
    val zoneRadius: Float,
    val sonarRadius: Float,
    val sonarActive: Boolean,
    val roundOver: Boolean,
    val winnerName: String?,
    val tick: Long,
) {
    val me: RenderTank? get() = tanks.firstOrNull { it.slot == mySlot }
    val aliveCount: Int get() = tanks.count { it.alive }
}
