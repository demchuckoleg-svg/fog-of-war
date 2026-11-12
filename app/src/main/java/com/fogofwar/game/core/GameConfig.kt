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
    const val TURRET_TURN_SPEED = 13f
    /** Turret lazily recenters to the hull when driving without aiming. */
    const val TURRET_IDLE_TURN_SPEED = 2.5f

    const val BULLET_SPEED = 430f
    const val BULLET_SPEED_POWER = 620f
    const val BULLET_RADIUS = 3.5f
    const val BULLET_MAX_RANGE = 760f
    const val FIRE_COOLDOWN = 0.72f
    const val FIRE_COOLDOWN_POWER = 0.38f

    const val MAX_HP = 3
    const val INVULN_TIME = 0.65f

    /** Right stick: above this magnitude the cannon fires automatically. */
    const val AIM_FIRE_THRESHOLD = 0.72f
    const val AIM_DEADZONE = 0.15f

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
    const val LIGHT_SELF_RADIUS = 38f

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

    /** Mines. */
    const val MINE_CHARGES = 2
    const val MINE_DROP_COOLDOWN = 0.5f
    const val MINE_ARM_TIME = 0.7f
    const val MINE_TRIGGER_RADIUS = 24f
    const val MINE_BLAST_RADIUS = 64f

    const val ROUND_OVER_DELAY = 5f

    /** Snapshot tank flags. */
    const val FLAG_ALIVE = 1
    const val FLAG_LIGHTS = 2
    const val FLAG_MOVING = 4
    const val FLAG_INVULN = 8
    const val FLAG_POWER = 16
    const val FLAG_LIGHT = 32

    /** Client renders this far in the past to interpolate between snapshots. */
    const val INTERPOLATION_DELAY_MS = 80L
    const val SNAPSHOT_HZ = 30
    const val INPUT_SEND_HZ = 20

    /** Host treats a slot as idle when no input packet arrives for this long. */
    const val INPUT_TIMEOUT_MS = 500L
}
