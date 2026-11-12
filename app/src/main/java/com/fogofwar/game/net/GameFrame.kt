package com.fogofwar.game.net

import com.fogofwar.game.core.EffectEvent
import com.fogofwar.game.core.GameConfig
import com.fogofwar.game.core.TileChange
import com.fogofwar.game.core.TileMap

data class TankSnap(
    val slot: Int,
    val x: Float,
    val y: Float,
    val bodyDir: Float,
    val turretDir: Float,
    val hp: Int,
    val flags: Int,
    val mines: Int,
    val cooldown: Float,
) {
    val alive: Boolean get() = flags and GameConfig.FLAG_ALIVE != 0
    val lightsOn: Boolean get() = flags and GameConfig.FLAG_LIGHTS != 0
    val moving: Boolean get() = flags and GameConfig.FLAG_MOVING != 0
    val invulnerable: Boolean get() = flags and GameConfig.FLAG_INVULN != 0
    val powered: Boolean get() = flags and GameConfig.FLAG_POWER != 0
    val lightBoost: Boolean get() = flags and GameConfig.FLAG_LIGHT != 0
}

data class BulletSnap(val x: Float, val y: Float)

data class PickupSnap(val type: Int, val x: Float, val y: Float)

data class MineSnap(val x: Float, val y: Float, val armed: Boolean, val owner: Int)

data class Snapshot(
    val tick: Long,
    val tanks: List<TankSnap>,
    val bullets: List<BulletSnap>,
    val pickups: List<PickupSnap>,
    val mines: List<MineSnap>,
    val zoneRadius: Float,
    val sonarRadius: Float,
    val sonarActive: Boolean,
    val tileChanges: List<TileChange>,
    val events: List<EffectEvent>,
)

data class RenderTank(
    val slot: Int,
    val name: String,
    val x: Float,
    val y: Float,
    val bodyDir: Float,
    val turretDir: Float,
    val hp: Int,
    val alive: Boolean,
    val isBot: Boolean,
    val lightsOn: Boolean,
    val moving: Boolean,
    val invulnerable: Boolean,
    val powered: Boolean,
    val lightBoost: Boolean,
    val mines: Int,
    val cooldown: Float,
)

/** Immutable view of the world that the renderer consumes once per frame. */
data class GameFrame(
    val map: TileMap,
    val mapId: Int,
    val mySlot: Int,
    val players: List<PlayerInfo>,
    val tanks: List<RenderTank>,
    val bullets: List<BulletSnap>,
    val pickups: List<PickupSnap>,
    val mines: List<MineSnap>,
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
