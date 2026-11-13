package com.fogofwar.game.core

/** All tunable gameplay constants live here. */
object GameConfig {
    const val TILE = 32f
    const val MAP_TILES = 64
    /** Every round on a given map uses this seed, so the lobby preview matches the real map. */
    const val MAP_SEED = 90210L
    const val WORLD_SIZE = TILE * MAP_TILES

    const val TICK_HZ = 30
    const val TICK_DT = 1f / TICK_HZ
    const val MAX_PLAYERS = 6
    const val MAX_CLIENTS = MAX_PLAYERS - 1

    const val TANK_HALF = 11f
    const val TANK_SPEED = 128f
    /** How fast the hull visually turns toward the movement stick. */
    const val BODY_TURN_SPEED = 9f
    /** Turret tracking speed while the aim stick is held. */
    const val TURRET_TURN_SPEED = 6f
    /** Turret lazily recenters to the hull when driving without aiming. */
    const val TURRET_IDLE_TURN_SPEED = 2.2f

    const val BULLET_SPEED = 430f
    const val BULLET_SPEED_POWER = 620f
    const val BULLET_RADIUS = 3.5f
    const val BULLET_MAX_RANGE = 760f
    const val FIRE_COOLDOWN = 0.72f
    const val FIRE_COOLDOWN_POWER = 0.38f

    const val MAX_HP = 3
    const val INVULN_TIME = 0.65f

    const val AIM_DEADZONE = 0.22f
    /** Tap-to-shoot picks the nearest enemy within this range if the line of sight is clear. */
    const val AUTO_AIM_RANGE = 700f

    /** Zone is a circle around the map centre. */
    const val ZONE_START_RADIUS = 1460f
    const val ZONE_MIN_RADIUS = 105f
    const val ZONE_SHRINK_DELAY = 10f
    const val ZONE_SHRINK_SPEED = 15f
    const val ZONE_DAMAGE_INTERVAL = 3f

    /** Sonar pulse timing: short pause, then a ring expands from the map centre. */
    const val SONAR_PAUSE = 4.2f
    const val SONAR_SPEED = 760f
    const val SONAR_MAX_RADIUS = 1500f

    /** Headlight cone. */
    const val LIGHT_RANGE = 260f
    const val LIGHT_HALF_ANGLE = 0.40f
    const val LIGHT_RAYS = 44
    const val LIGHT_SELF_RADIUS = 76f

    /** Pickups. */
    const val PICKUP_INTERVAL = 18f
    const val MAX_PICKUPS = 4
    const val PICKUP_RADIUS = 22f
    const val PICKUP_REPAIR = 0
    const val PICKUP_POWER = 1
    const val PICKUP_LIGHT = 2
    const val POWER_DURATION = 10f
    const val LIGHT_BOOST_DURATION = 12f
    const val LIGHT_RANGE_BOOST = 1.5f
    const val LIGHT_HALF_ANGLE_BOOST = 1.35f

    /** Grenades: lobbed over cover, short fuse, area damage. */
    const val GRENADE_CHARGES = 2
    /** Seconds to regenerate one spent grenade charge. */
    const val GRENADE_RECHARGE_TIME = 9f
    const val GRENADE_THROW_COOLDOWN = 0.8f
    /** Time from the hand to the landing point; walls do not stop a lobbed grenade. */
    const val GRENADE_FLIGHT_TIME = 0.45f
    /** Fuse after landing. */
    const val GRENADE_FUSE_TIME = 1.15f
    const val GRENADE_THROW_RANGE = 245f
    const val GRENADE_BLAST_RADIUS = 92f

    const val ROUND_OVER_DELAY = 5f
    /** Freeze at the start of a round so everybody sees 3-2-1. */
    const val ROUND_COUNTDOWN_MS = 3_000L

    /** Snapshot tank flags. */
    const val FLAG_ALIVE = 1
    const val FLAG_LIGHTS = 2
    const val FLAG_MOVING = 4
    const val FLAG_INVULN = 8
    const val FLAG_POWER = 16
    const val FLAG_LIGHT = 32

    /** Client renders this far in the past to interpolate between snapshots. */
    const val INTERPOLATION_DELAY_MS = 80L
    const val SNAPSHOT_HZ = TICK_HZ / 2
    const val INPUT_SEND_HZ = 20

    /** Host treats a slot as idle when no input packet arrives for this long. */
    const val INPUT_TIMEOUT_MS = 500L
}
