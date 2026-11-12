package com.fogofwar.game.render

import android.content.Context
import android.graphics.Bitmap
import com.fogofwar.game.core.Tile

/**
 * Cached 16x16 pixel-art sprites defined as ASCII grids.
 *
 * Grid characters are documented in [BASE]; player-colored sprites replace
 * 'D'/'M'/'L' with shades of the requested ARGB color. All getters are
 * thread-safe: sprites are generated once and reused by the render loop.
 */
object SpriteAtlas {

    private const val SIZE = 16

    private val cache = HashMap<String, Bitmap>()
    private val playerPalettes = HashMap<Int, Map<Char, Int>>()

    private val PLAYER_COLORS = intArrayOf(
        0xFF57D68B.toInt(),
        0xFFE05A5A.toInt(),
        0xFFE0A23F.toInt(),
        0xFFB06BE0.toInt(),
        0xFF43C6E8.toInt(),
        0xFFE86FB0.toInt()
    )

    /** Shared palette. '.' is always transparent and is not listed here. */
    private val BASE: Map<Char, Int> = mapOf(
        'T' to 0xFF3A4148.toInt(), // tread / dark metal
        'W' to 0xFFEAF2EA.toInt(), // near-white highlight
        'B' to 0xFF8A4A2C.toInt(), // brick base
        'b' to 0xFF5A2E1C.toInt(), // brick dark / mortar
        'C' to 0xFF2A1710.toInt(), // crack / hole
        'S' to 0xFF4A5561.toInt(), // steel base
        's' to 0xFF2C343C.toInt(), // steel dark
        'H' to 0xFF8C9AA8.toInt(), // steel highlight
        'A' to 0xFF123C56.toInt(), // water base
        'a' to 0xFF2C6C90.toInt(), // water light
        'G' to 0xFF2A3A28.toInt(), // ground speck
        'Y' to 0xFFFFE27A.toInt(),
        'O' to 0xFFFF9A3C.toInt(),
        'R' to 0xFFD64545.toInt(),
        'K' to 0xFF3A3F44.toInt()
    )

    private val REPAIR_PALETTE = BASE + mapOf(
        'D' to 0xFF2E7A4D.toInt(),
        'M' to 0xFF57D68B.toInt(),
        'L' to 0xFF9AF0BC.toInt()
    )
    private val POWER_PALETTE = BASE + mapOf(
        'D' to 0xFF8A4A14.toInt(),
        'M' to 0xFFFF9A3C.toInt(),
        'L' to 0xFFFFC46A.toInt()
    )
    private val LIGHT_PALETTE = BASE + mapOf(
        'D' to 0xFF9A7A1E.toInt(),
        'M' to 0xFFFFE27A.toInt(),
        'L' to 0xFFFFF3B8.toInt()
    )

    // --- Tank hull, facing +X. Two tread phases. -------------------------

    private val HULL_0 = arrayOf(
        "................",
        "................",
        ".T.TT.TT.TT.TT..",
        ".TTTTTTTTTTTTTT.",
        ".T.TT.TT.TT.TT..",
        "..DWWMMMMMMMMD..",
        ".DMMMMMMMMMMMMD.",
        ".DMMMMMMMMMMMMD.",
        ".DMMMMMMMMMMMMD.",
        ".DDMMMMMMMMMMDD.",
        "..DDDDDDDDDDDD..",
        ".T.TT.TT.TT.TT..",
        ".TTTTTTTTTTTTTT.",
        ".T.TT.TT.TT.TT..",
        "................",
        "................"
    )

    private val HULL_1 = arrayOf(
        "................",
        "................",
        ".TT.TT.TT.TT.T..",
        ".TTTTTTTTTTTTTT.",
        ".TT.TT.TT.TT.T..",
        "..DWWMMMMMMMMD..",
        ".DMMMMMMMMMMMMD.",
        ".DMMMMMMMMMMMMD.",
        ".DMMMMMMMMMMMMD.",
        ".DDMMMMMMMMMMDD.",
        "..DDDDDDDDDDDD..",
        ".TT.TT.TT.TT.T..",
        ".TTTTTTTTTTTTTT.",
        ".TT.TT.TT.TT.T..",
        "................",
        "................"
    )

    // --- Turret + barrel, facing +X. ------------------------------------

    private val TURRET = arrayOf(
        "................",
        "................",
        "................",
        "....DDDD........",
        "...DMMMMD.......",
        "..DMMMMMMD......",
        "..DMMMMMMDLLLLLW",
        "..DMMMMMMDMMMMMW",
        "..DMMMMMMDMMMMMW",
        "..DMMMMMMDDDDDDW",
        "...DMMMMD.......",
        "....DDDD........",
        "................",
        "................",
        "................",
        "................"
    )

    // --- Ground variants: mostly transparent, a few dark specks. --------

    private val GROUND_0 = arrayOf(
        "................",
        "................",
        "....G...........",
        "................",
        "................",
        "...........G....",
        "................",
        "................",
        "................",
        "......G.........",
        "................",
        "................",
        "................",
        ".............G..",
        "................",
        "................"
    )

    private val GROUND_1 = arrayOf(
        "................",
        "..G.............",
        "................",
        "...........G....",
        "................",
        "................",
        "................",
        "...G............",
        "................",
        "................",
        "............G...",
        "................",
        "......G.........",
        "................",
        "................",
        "................"
    )

    private val GROUND_2 = arrayOf(
        ".......G........",
        "................",
        "..G.............",
        "................",
        ".............G..",
        "................",
        ".....G..........",
        "................",
        "..........G.....",
        "................",
        "..G.............",
        "................",
        "........G.......",
        "................",
        "....G...........",
        "................"
    )

    private val GROUND_3 = arrayOf(
        "................",
        "................",
        "......G.........",
        "......G.........",
        ".....G..........",
        "................",
        "................",
        "................",
        "................",
        "...........G....",
        "................",
        "................",
        "..........G.....",
        "..........G.....",
        "................",
        "................"
    )

    // --- Walls -----------------------------------------------------------

    private val BRICK_0 = arrayOf(
        "BWBBBBBbBBBWBBBB",
        "BBBBBBBbBBBBBBBB",
        "BBBBBBBbBBBBBBBB",
        "bbbbbbbbbbbbbbbb",
        "BBBbBBBBBBBbBBBB",
        "BBBbBBBBBBBbBBBB",
        "BBBbBBBBBBBbBBBB",
        "bbbbbbbbbbbbbbbb",
        "BBBBBBBbBBBBBBBB",
        "BBBBBBBbBBBBBBBB",
        "BBBBBBBbBBBBBBBB",
        "bbbbbbbbbbbbbbbb",
        "BBBBbBBBBBBBbBBB",
        "BBBBbBBBBBBBbBBB",
        "BBBBbBBBBBBBbBBB",
        "bbbbbbbbbbbbbbbb"
    )

    private val BRICK_1 = arrayOf(
        "BBBBBbBBBBBBBbBB",
        "BBBBBbBBBWBBBbBB",
        "BBBBBbBBBBBBBbBB",
        "bbbbbbbbbbbbbbbb",
        "BBBBBBBBbBBBBBBB",
        "BBBBBBBBbBBBBBBB",
        "BBBBBBBBbBBBBBBB",
        "bbbbbbbbbbbbbbbb",
        "BBBBbBBBBBBBbBBB",
        "BBBBbBBBCBBBbBBB",
        "BBBBbBBBBBBBbBBB",
        "bbbbbbbbbbbbbbbb",
        "BBBBBBBBBBbBBBBB",
        "BBBBBBBBBBbBBBBB",
        "BBBBBBBBBBbBBBBB",
        "bbbbbbbbbbbbbbbb"
    )

    private val CRACKED = arrayOf(
        "BBBBBBBCBBBBBBBB",
        "BBBBBBBCBBBBBBBB",
        "BBBBBBBCBBBBBBBB",
        "bbbbbbbCbbbbbbbb",
        "BBBbBBCBBBBbBBBB",
        "BBBbBBCBBBBbBBBB",
        "BBBbC.BBBBBbBBBB",
        "bbbbCbbbbbbbbbbb",
        "BBBBCBBbBBBBBBBB",
        "BBBB.CBBBBBBBBBB",
        "BBBBBCBbBBBBBBBB",
        "bbbbbCbbbbbbbbbb",
        "BBBBBBBCBBBbBBBB",
        "BBBBBBBCBBBbBBBB",
        "BBBBBBBCBBBbBBBB",
        "bbbbbbbCbbbbbbbb"
    )

    private val STEEL = arrayOf(
        "ssssssssssssssss",
        "sSSSSSSSSSSSSSSs",
        "sSHHSSSSSSSSSSWs",
        "sSSHHSSSSSSSSSSs",
        "sSSSHHSSSSSSSSSs",
        "sSSSSSSSSSSSSSSs",
        "sSWSSSSSSSSSSSSs",
        "sSSSSSSSSSSSSSSs",
        "sSSSSSSSSSSSSSSs",
        "sSSSSSSSSSSSSSSs",
        "sSSSSSSSSSSSSWSs",
        "sSSSSSSSSSSSSSSs",
        "sSSSSSSSSSSSSSSs",
        "sSWSSSSSSSSSSSSs",
        "sSSSSSSSSSSSSSSs",
        "ssssssssssssssss"
    )

    private val WATER_0 = arrayOf(
        "AAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAA",
        "AAaaaaAAAAAAAAAA", "AAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAA",
        "AAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAA", "AAAAAAaaaaAAAAAA", "AAAAAAAAAAAAAAAA",
        "AAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAA"
    )

    private val WATER_1 = arrayOf(
        "AAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAA",
        "AAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAA", "AAaaaaAAAAAAAAAA", "AAAAAAAAAAAAAAAA",
        "AAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAA",
        "AAAAAAAAAAAAAAAA", "AAAAAAaaaaAAAAAA", "AAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAA"
    )

    // --- Pickups: wooden crate with a colored icon. ---------------------

    private val PICKUP_REPAIR = arrayOf(
        "................",
        ".TTTTTTTTTTTTTT.",
        ".TbbbbbbbbbbbbT.",
        ".TbbbbbbbbbbbbT.",
        ".TbbbbbLLbbbbbT.",
        ".TbbbbbMMbbbbbT.",
        ".TbbbbbMMbbbbbT.",
        ".TbbLLMMMMMMbbT.",
        ".TbbMMMMMMMMbbT.",
        ".TbbbbbMMbbbbbT.",
        ".TbbbbbMMbbbbbT.",
        ".TbbbbbMMbbbbbT.",
        ".TbbbbbbbbbbbbT.",
        ".TbbbbbbbbbbbbT.",
        ".TTTTTTTTTTTTTT.",
        "................"
    )

    private val PICKUP_POWER = arrayOf(
        "................",
        ".TTTTTTTTTTTTTT.",
        ".TbbbbbbbbbbbbT.",
        ".TbbbbbbbbbbbbT.",
        ".Tbb....YY..bbT.",
        ".Tbb...OO...bbT.",
        ".Tbb..OOO...bbT.",
        ".Tbb.YOOO...bbT.",
        ".Tbb..OOO...bbT.",
        ".Tbb...OO...bbT.",
        ".Tbb..OO....bbT.",
        ".Tbb.OO.....bbT.",
        ".TbbbbbbbbbbbbT.",
        ".TbbbbbbbbbbbbT.",
        ".TTTTTTTTTTTTTT.",
        "................"
    )

    private val PICKUP_LIGHT = arrayOf(
        "................",
        ".TTTTTTTTTTTTTT.",
        ".TbbbbbbbbbbbbT.",
        ".TbbbbbbbbbbbbT.",
        ".Tbb..YYYY..bbT.",
        ".Tbb.YYYYYY.bbT.",
        ".Tbb.YWYYYY.bbT.",
        ".Tbb.YYYYYY.bbT.",
        ".Tbb..YYYY..bbT.",
        ".Tbb...YY...bbT.",
        ".Tbb...TT...bbT.",
        ".Tbb...TT...bbT.",
        ".TbbbbbbbbbbbbT.",
        ".TbbbbbbbbbbbbT.",
        ".TTTTTTTTTTTTTT.",
        "................"
    )

    // --- Mine -------------------------------------------------------------

    private val MINE_UNARMED = arrayOf(
        "................",
        ".......TT.......",
        ".......TT.......",
        ".....TTTTTT.....",
        "....TSWTTTTT....",
        "...TSTTTTTTTT...",
        "...TTTTTTTTTT...",
        "TTTTTTTssTTTTTTT",
        "TTTTTTTssTTTTTTT",
        "...TTTTTTTTTT...",
        "...TTTTTTTTTT...",
        "....TTTTTTTT....",
        ".....TTTTTT.....",
        ".......TT.......",
        ".......TT.......",
        "................"
    )

    private val MINE_ARMED = arrayOf(
        "................",
        ".......TT.......",
        ".......TT.......",
        ".....TTTTTT.....",
        "....TSWTTTTT....",
        "...TSTTTTTTTT...",
        "...TTTTWTTTTT...",
        "TTTTTTTRRTTTTTTT",
        "TTTTTTTRRTTTTTTT",
        "...TTTTTTTTTT...",
        "...TTTTTTTTTT...",
        "....TTTTTTTT....",
        ".....TTTTTT.....",
        ".......TT.......",
        ".......TT.......",
        "................"
    )

    // --- Explosion frames: core -> fireball -> smoke. --------------------

    private val EXPLOSION_0 = arrayOf(
        "................",
        "................",
        "................",
        ".....OOOOOO.....",
        "....OOYYYYOO....",
        "...OOYYWWYYOO...",
        "...OYYWWWWYYO...",
        "..OOYYWWWWYYOO..",
        "..OOYYWWWWYYOO..",
        "...OYYWWWWYYO...",
        "...OOYYWWYYOO...",
        "....OOYYYYOO....",
        ".....OOOOOO.....",
        "................",
        "................",
        "................"
    )

    private val EXPLOSION_1 = arrayOf(
        "................",
        "................",
        ".....KKKKKK.....",
        "...KKOOOOOOKK...",
        "..KOOORRRROOOK..",
        ".KOOORRRRRROOOK.",
        ".KORRRRRRRRRROK.",
        "KORRRRRRRRRRRROK",
        "KORRRRRRRRRRRROK",
        ".KORRRRRRRRRROK.",
        ".KOOORRRRRROOOK.",
        "..KOOORRRROOOK..",
        "...KKOOOOOOKK...",
        ".....KKKKKK.....",
        "................",
        "................"
    )

    private val EXPLOSION_2 = arrayOf(
        "................",
        "................",
        "....KKKKKKKK....",
        "..KKKKKKKKKKKK..",
        ".KKKKKKKKKKKKKK.",
        "KKKKKRKKKKKKKKKK",
        "KKKKKKKKKKKKKKKK",
        "KKKKKKKKKKKKKKKK",
        "KKKKKKKKKRKKKKKK",
        "KKKKKKKKKKKKKKKK",
        ".KKKKKKKKKKKKKK.",
        "..KKKKRKKKKKKK..",
        "...KKKKKKKKKK...",
        ".....KKKKKK.....",
        "................",
        "................"
    )

    private val EMPTY = arrayOf(
        "................", "................", "................", "................",
        "................", "................", "................", "................",
        "................", "................", "................", "................",
        "................", "................", "................", "................"
    )

    /**
     * Generates every sprite up front. Safe to call repeatedly; a missing
     * warm-up only means a sprite is generated lazily on first use.
     */
    @Suppress("UNUSED_PARAMETER")
    fun warmUp(context: Context) {
        for (color in PLAYER_COLORS) {
            hull(color, 0)
            hull(color, 1)
            turret(color)
        }
        for (v in 0..3) ground(v)
        wall(Tile.BRICK, 0)
        wall(Tile.BRICK, 1)
        wall(Tile.CRACKED, 0)
        wall(Tile.STEEL, 0)
        wall(Tile.WATER, 0)
        wall(Tile.WATER, 1)
        for (t in 0..2) pickup(t)
        mine(false)
        mine(true)
        for (f in 0..2) explosion(f)
    }

    /** Tank hull facing +X; [frame] selects one of two tread phases. */
    @Synchronized
    fun hull(color: Int, frame: Int): Bitmap {
        val f = frame and 1
        return cached(
            key = "hull:$color:$f",
            grid = if (f == 0) HULL_0 else HULL_1,
            palette = playerPalette(color)
        )
    }

    /** Tank turret and barrel facing +X. */
    @Synchronized
    fun turret(color: Int): Bitmap =
        cached("turret:$color", TURRET, playerPalette(color))

    /** Ground speck overlay; [variant] is reduced to 0..3. */
    @Synchronized
    fun ground(variant: Int): Bitmap {
        val v = variant and 3
        val grid = when (v) {
            0 -> GROUND_0
            1 -> GROUND_1
            2 -> GROUND_2
            else -> GROUND_3
        }
        return cached("ground:$v", grid)
    }

    /** Wall tile; brick and water use [variant] as an animation phase. */
    @Synchronized
    fun wall(tile: Tile, variant: Int): Bitmap = when (tile) {
        Tile.BRICK -> {
            val v = variant and 1
            cached("brick:$v", if (v == 0) BRICK_0 else BRICK_1)
        }
        Tile.CRACKED -> cached("cracked", CRACKED)
        Tile.STEEL -> cached("steel", STEEL)
        Tile.WATER -> {
            val v = variant and 1
            cached("water:$v", if (v == 0) WATER_0 else WATER_1)
        }
        Tile.EMPTY -> cached("empty", EMPTY)
    }

    /** Pickup crate: 0 = repair, 1 = power, 2 = light. */
    @Synchronized
    fun pickup(type: Int): Bitmap = when (type) {
        1 -> cached("pickup:power", PICKUP_POWER, POWER_PALETTE)
        2 -> cached("pickup:light", PICKUP_LIGHT, LIGHT_PALETTE)
        else -> cached("pickup:repair", PICKUP_REPAIR, REPAIR_PALETTE)
    }

    /** Anti-tank mine; [armed] shows the red trigger dot. */
    @Synchronized
    fun mine(armed: Boolean): Bitmap =
        if (armed) cached("mine:armed", MINE_ARMED) else cached("mine:unarmed", MINE_UNARMED)

    /** Explosion animation frame, clamped to 0..2. */
    @Synchronized
    fun explosion(frame: Int): Bitmap {
        val f = frame.coerceIn(0, 2)
        val grid = when (f) {
            0 -> EXPLOSION_0
            1 -> EXPLOSION_1
            else -> EXPLOSION_2
        }
        return cached("explosion:$f", grid)
    }

    @Synchronized
    private fun playerPalette(color: Int): Map<Char, Int> =
        playerPalettes.getOrPut(color) {
            BASE + mapOf(
                'D' to scale(color, 0.55f),
                'M' to color,
                'L' to scale(color, 1.45f)
            )
        }

    private fun cached(
        key: String,
        grid: Array<String>,
        palette: Map<Char, Int> = BASE
    ): Bitmap = cache.getOrPut(key) { render(grid, palette) }

    /** Expands a grid into a 16x16 ARGB bitmap. Missing cells are transparent. */
    private fun render(grid: Array<String>, palette: Map<Char, Int>): Bitmap {
        val pixels = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) {
            val row = grid.getOrElse(y) { "" }
            for (x in 0 until SIZE) {
                val ch = row.getOrElse(x) { '.' }
                pixels[y * SIZE + x] = if (ch == '.') 0 else palette[ch] ?: 0
            }
        }
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        return bitmap
    }

    /** Multiplies RGB channels by [factor] while preserving alpha. */
    private fun scale(color: Int, factor: Float): Int {
        val a = color ushr 24 and 0xFF
        val r = ((color shr 16 and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = ((color shr 8 and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
