package com.fogofwar.game.core

/** All tunable gameplay constants live here. */
object GameConfig {
    const val TILE = 32f
    const val MAP_TILES = 64
    const val WORLD_SIZE = TILE * MAP_TILES

    const val TICK_HZ = 30
    const val TICK_DT = 1f / TICK_HZ
    const val MAX_PLAYERS = 6
    const val MAX_CLIENTS = MAX_PLAYERS - 1

    const val TANK_HALF = 11f
    const val TANK_SPEED = 128f
    const val TANK_TURN_SPEED = 11f

    const val BULLET_SPEED = 430f
    const val BULLET_RADIUS = 3.5f
    const val BULLET_MAX_RANGE = 760f
    const val FIRE_COOLDOWN = 0.72f

    const val MAX_HP = 3
    const val INVULN_TIME = 0.65f

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
    const val LIGHT_SELF_RADIUS = 46f

    const val ROUND_OVER_DELAY = 5f

    /** Client renders this far in the past to interpolate between snapshots. */
    const val INTERPOLATION_DELAY_MS = 95L
    const val SNAPSHOT_HZ = 30
    const val INPUT_SEND_HZ = 20
}
