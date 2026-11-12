package com.fogofwar.game.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.fogofwar.game.App
import com.fogofwar.game.core.EffectKind
import com.fogofwar.game.core.GameConfig
import com.fogofwar.game.core.Tile
import com.fogofwar.game.net.GameFrame
import com.fogofwar.game.net.NearbyNet
import com.fogofwar.game.net.RenderTank
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * SurfaceView renderer: pixel-art world -> effects -> tanks -> raycast headlight fog ->
 * enemy beams -> zone/sonar -> sound-echo overlays -> twin-stick HUD.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Runnable {

    private val net: NearbyNet
        get() = (context.applicationContext as App).net

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val camera = Camera()
    private val effects = Effects()

    private val lightPath = Path()
    private val enemyPath = Path()
    private val glintX = FloatArray(GameConfig.LIGHT_RAYS)
    private val glintY = FloatArray(GameConfig.LIGHT_RAYS)
    private var glintCount = 0

    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GROUND
        style = Paint.Style.FILL
    }
    private val shadowPaint = Paint().apply { color = 0x66000000 }
    private val spritePaint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
    }
    private val tankSpritePaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
        alpha = 255
    }
    private val bulletPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fogPaint = Paint().apply { color = COLOR_FOG }
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val spriteRect = RectF()
    private val arcRect = RectF()

    private var renderThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var lastFrame: GameFrame? = null

    private val blips = ArrayList<Blip>()
    private var lastSonarRadius = 0f
    private val lastTreadAt = HashMap<Int, Long>()
    private val lastAlive = HashMap<Int, Boolean>()
    private val feed = ArrayList<FeedLine>()

    // Touch state written by the UI thread and read by the render thread.
    @Volatile private var moveX = 0f
    @Volatile private var moveY = 0f
    @Volatile private var aimX = 0f
    @Volatile private var aimY = 0f
    @Volatile private var firing = false
    @Volatile private var lightsOn = true
    @Volatile private var dropMineRequest = false

    @Volatile private var moveActive = false
    @Volatile private var moveBaseX = 0f
    @Volatile private var moveBaseY = 0f
    @Volatile private var moveKnobX = 0f
    @Volatile private var moveKnobY = 0f

    @Volatile private var aimActive = false
    @Volatile private var aimBaseX = 0f
    @Volatile private var aimBaseY = 0f
    @Volatile private var aimKnobX = 0f
    @Volatile private var aimKnobY = 0f

    private var movePointerId = -1
    private var aimPointerId = -1
    private var firePointerId = -1
    private var minePointerId = -1

    private var moveRadius = 0f
    private var moveKnobRadius = 0f
    private var aimRadius = 0f
    private var aimKnobRadius = 0f
    private var fireRadius = 0f
    private var mineRadius = 0f
    private var lightRadius = 0f

    private var hintUntil = 0L
    private var lastMyHp = -1
    private var lastMySlot = -1
    private var shakeUntil = 0L
    private var shakePower = 0f

    @Volatile private var safeLeft = 0
    @Volatile private var safeTop = 0
    @Volatile private var safeRight = 0
    @Volatile private var safeBottom = 0

    init {
        holder.addCallback(this)
        isFocusable = true
        keepScreenOn = true
        SpriteAtlas.warmUp(context)
    }

    fun setSafeInsets(left: Int, top: Int, right: Int, bottom: Int) {
        safeLeft = left
        safeTop = top
        safeRight = right
        safeBottom = bottom
    }

    fun pauseRendering() {
        running = false
        val t = renderThread
        renderThread = null
        if (t != null && t !== Thread.currentThread()) {
            try {
                t.join(400)
            } catch (_: InterruptedException) {
            }
        }
    }

    fun resumeRendering() {
        if (running) return
        if (holder.surface?.isValid != true) return
        startThread()
    }

    private fun startThread() {
        running = true
        val thread = Thread(this, "GameRender")
        renderThread = thread
        thread.start()
    }

    override fun surfaceCreated(holder: SurfaceHolder) = startThread()

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) = pauseRendering()

    override fun run() {
        var lastInput = 0L
        while (running) {
            val frameStart = System.nanoTime()
            try {
                val now = SystemClock.elapsedRealtime()
                effects.update(now)

                for (event in net.pollEffects()) {
                    effects.onEvent(event)
                    val me = lastFrame?.me
                    if (me != null) {
                        val dist = hypot(me.x - event.x, me.y - event.y)
                        when (event.kind) {
                            EffectKind.EXPLOSION -> if (dist < 520f) {
                                addShake((1f - dist / 520f) * 16f, 380L)
                            }
                            EffectKind.SHOT -> if (dist < 300f) {
                                addShake((1f - dist / 300f) * 4f * density, 130L)
                            }
                            else -> Unit
                        }
                    }
                }

                if (now - lastInput >= 50L) {
                    lastInput = now
                    val autoFire = hypot(aimX, aimY) >= GameConfig.AIM_FIRE_THRESHOLD
                    net.sendInput(
                        moveX, moveY, aimX, aimY,
                        fire = firing || autoFire,
                        lights = lightsOn,
                        dropMine = dropMineRequest,
                    )
                    dropMineRequest = false
                }

                val frame = net.currentFrame()
                if (frame != null) {
                    updateBlips(frame)
                    lastFrame = frame
                }
                val canvas = holder.lockCanvas() ?: continue
                try {
                    drawFrame(canvas, lastFrame)
                } finally {
                    holder.unlockCanvasAndPost(canvas)
                }
            } catch (t: Throwable) {
                if (running && t !is IllegalStateException) pauseRendering()
            }
            val elapsedMs = (System.nanoTime() - frameStart) / 1_000_000L
            if (elapsedMs < 16L) SystemClock.sleep(16L - elapsedMs)
        }
    }

    // ------------------------------------------------------------------ input

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                handlePointerDown(event, event.actionIndex)
            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                handlePointerUp(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_CANCEL -> resetInput()
        }
        return true
    }

    private fun fireButtonX() = width - safeRight - 92f * density
    private fun fireButtonY() = height - safeBottom - 92f * density
    private fun mineButtonX() = fireButtonX()
    private fun mineButtonY() = fireButtonY() - 120f * density
    private fun lightButtonX() = width - safeRight - 46f * density
    private fun lightButtonY() = safeTop + 86f * density

    private fun handlePointerDown(event: MotionEvent, index: Int) {
        val id = event.getPointerId(index)
        val x = event.getX(index)
        val y = event.getY(index)

        if (hypot(x - lightButtonX(), y - lightButtonY()) <= lightRadius) {
            lightsOn = !lightsOn
            return
        }
        if (hypot(x - fireButtonX(), y - fireButtonY()) <= fireRadius && firePointerId == -1) {
            firePointerId = id
            firing = true
            return
        }
        if (hypot(x - mineButtonX(), y - mineButtonY()) <= mineRadius && minePointerId == -1) {
            minePointerId = id
            dropMineRequest = true
            return
        }

        if (x < width / 2f && movePointerId == -1) {
            movePointerId = id
            moveActive = true
            moveBaseX = x
            moveBaseY = y
            moveKnobX = x
            moveKnobY = y
            moveX = 0f
            moveY = 0f
        } else if (x >= width / 2f && aimPointerId == -1) {
            aimPointerId = id
            aimActive = true
            aimBaseX = x
            aimBaseY = y
            aimKnobX = x
            aimKnobY = y
            aimX = 0f
            aimY = 0f
        }
    }

    private fun handleMove(event: MotionEvent) {
        if (movePointerId >= 0) {
            val index = event.findPointerIndex(movePointerId)
            if (index >= 0) updateStick(
                event.getX(index), event.getY(index), moveBaseX, moveBaseY, moveRadius,
            ) { nx, ny -> moveX = nx; moveY = ny }
        }
        if (aimPointerId >= 0) {
            val index = event.findPointerIndex(aimPointerId)
            if (index >= 0) {
                var dx = event.getX(index) - aimBaseX
                var dy = event.getY(index) - aimBaseY
                val len = hypot(dx, dy)
                if (len > aimRadius) {
                    dx = dx / len * aimRadius
                    dy = dy / len * aimRadius
                }
                aimKnobX = aimBaseX + dx
                aimKnobY = aimBaseY + dy
                val nx = dx / aimRadius
                val ny = dy / aimRadius
                if (hypot(nx, ny) < GameConfig.AIM_DEADZONE) {
                    aimX = 0f
                    aimY = 0f
                } else {
                    aimX = nx
                    aimY = ny
                }
            }
        }
    }

    private inline fun updateStick(
        x: Float,
        y: Float,
        baseX: Float,
        baseY: Float,
        radius: Float,
        onVector: (Float, Float) -> Unit,
    ) {
        var dx = x - baseX
        var dy = y - baseY
        val len = hypot(dx, dy)
        if (len > radius) {
            dx = dx / len * radius
            dy = dy / len * radius
        }
        moveKnobX = baseX + dx
        moveKnobY = baseY + dy
        val nx = dx / radius
        val ny = dy / radius
        if (hypot(nx, ny) < 0.14f) onVector(0f, 0f) else onVector(nx, ny)
    }

    private fun handlePointerUp(id: Int) {
        if (id == movePointerId) {
            movePointerId = -1
            moveActive = false
            moveX = 0f
            moveY = 0f
        }
        if (id == aimPointerId) {
            aimPointerId = -1
            aimActive = false
            aimX = 0f
            aimY = 0f
        }
        if (id == firePointerId) {
            firePointerId = -1
            firing = false
        }
        if (id == minePointerId) minePointerId = -1
    }

    private fun resetInput() {
        movePointerId = -1
        aimPointerId = -1
        firePointerId = -1
        minePointerId = -1
        moveActive = false
        aimActive = false
        moveX = 0f
        moveY = 0f
        aimX = 0f
        aimY = 0f
        firing = false
        dropMineRequest = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        moveRadius = 74f * density
        moveKnobRadius = 30f * density
        aimRadius = 66f * density
        aimKnobRadius = 26f * density
        fireRadius = 58f * density
        mineRadius = 36f * density
        lightRadius = 24f * density
        moveBaseX = 104f * density
        moveBaseY = h - 104f * density
        moveKnobX = moveBaseX
        moveKnobY = moveBaseY
        aimBaseX = w - 260f * density
        aimBaseY = h - 110f * density
        aimKnobX = aimBaseX
        aimKnobY = aimBaseY
    }

    // ---------------------------------------------------------------- drawing

    private fun drawFrame(canvas: Canvas, frame: GameFrame?) {
        canvas.drawColor(COLOR_VOID)
        if (frame == null) {
            drawCenteredText(canvas, "СОЕДИНЕНИЕ…", canvas.height / 2f, 20f, COLOR_DIM)
            drawCenteredText(canvas, "ожидание состояния от хоста", canvas.height / 2f + 34f * density, 13f, COLOR_DIM)
            return
        }
        if (hintUntil == 0L) hintUntil = SystemClock.elapsedRealtime() + 6500L

        val me = frame.me
        val now = SystemClock.elapsedRealtime()
        updateFeed(frame, now)
        val center = GameConfig.WORLD_SIZE / 2f
        camera.update(
            canvas.width.toFloat(),
            canvas.height.toFloat(),
            me?.x ?: center,
            me?.y ?: center,
        )

        if (me != null) {
            if (me.slot != lastMySlot) {
                lastMySlot = me.slot
                lastMyHp = me.hp
            } else if (me.hp < lastMyHp) {
                addShake(9f * density, 320L)
                lastMyHp = me.hp
            }
        }

        canvas.save()
        val shake = shakeOffset(now)
        canvas.translate(camera.offsetX + shake.first, camera.offsetY + shake.second)
        canvas.scale(camera.scale, camera.scale)

        drawWorld(canvas, frame, now)
        spawnTreads(frame, now)
        effects.draw(canvas)
        drawBullets(canvas, frame)
        drawPickups(canvas, frame, now)
        drawMines(canvas, frame, now)
        drawTanks(canvas, frame, now)
        drawFog(canvas, frame, me, now)
        drawEnemyBeams(canvas, frame)
        drawZone(canvas, frame)
        drawSonar(canvas, frame)
        drawBlips(canvas)
        effects.drawOverlay(canvas)
        drawAimGuide(canvas, frame, me)

        canvas.restore()

        drawHud(canvas, frame, now)
    }

    private fun shakeOffset(now: Long): Pair<Float, Float> {
        if (now >= shakeUntil) {
            shakePower = 0f
            return 0f to 0f
        }
        val fade = ((shakeUntil - now) / 320f).coerceIn(0f, 1f)
        val power = shakePower * fade
        return sin(now * 0.09f) * power to cos(now * 0.13f) * power
    }

    private fun addShake(power: Float, durationMs: Long) {
        val now = SystemClock.elapsedRealtime()
        shakePower = maxOf(shakePower, power)
        shakeUntil = maxOf(shakeUntil, now + durationMs)
    }

    private fun drawWorld(canvas: Canvas, frame: GameFrame, now: Long) {
        val map = frame.map
        val margin = GameConfig.TILE
        val left = (camera.worldLeft - margin).coerceAtLeast(0f)
        val top = (camera.worldTop - margin).coerceAtLeast(0f)
        val right = (camera.worldRight + margin).coerceAtMost(GameConfig.WORLD_SIZE)
        val bottom = (camera.worldBottom + margin).coerceAtMost(GameConfig.WORLD_SIZE)
        canvas.drawRect(left, top, right, bottom, groundPaint)

        val tx0 = floor(left / GameConfig.TILE).toInt().coerceIn(0, map.width - 1)
        val ty0 = floor(top / GameConfig.TILE).toInt().coerceIn(0, map.height - 1)
        val tx1 = floor(right / GameConfig.TILE).toInt().coerceIn(0, map.width - 1)
        val ty1 = floor(bottom / GameConfig.TILE).toInt().coerceIn(0, map.height - 1)

        for (ty in ty0..ty1) {
            for (tx in tx0..tx1) {
                val tile = map.tileAt(tx, ty)
                val x = tx * GameConfig.TILE
                val y = ty * GameConfig.TILE
                spriteRect.set(x, y, x + GameConfig.TILE, y + GameConfig.TILE)
                when (tile) {
                    Tile.EMPTY -> {
                        // Sparse detail: the base ground rect already covers the surface.
                        if (((tx * 31 + ty * 17) and 1) == 0) {
                            val variant = (tx * 31 + ty * 17) and 3
                            canvas.drawBitmap(SpriteAtlas.ground(variant), null, spriteRect, spritePaint)
                        }
                    }
                    Tile.WATER -> {
                        val frameIndex = ((now / 420L) + tx + ty).toInt() and 1
                        canvas.drawBitmap(SpriteAtlas.wall(Tile.WATER, frameIndex), null, spriteRect, spritePaint)
                    }
                    Tile.BRICK -> {
                        canvas.drawRect(x + 3f, y + 3f, x + GameConfig.TILE + 3f, y + GameConfig.TILE + 3f, shadowPaint)
                        canvas.drawBitmap(SpriteAtlas.wall(Tile.BRICK, (tx + ty) and 1), null, spriteRect, spritePaint)
                    }
                    Tile.CRACKED -> {
                        canvas.drawRect(x + 3f, y + 3f, x + GameConfig.TILE + 3f, y + GameConfig.TILE + 3f, shadowPaint)
                        canvas.drawBitmap(SpriteAtlas.wall(Tile.CRACKED, 0), null, spriteRect, spritePaint)
                    }
                    Tile.STEEL -> {
                        canvas.drawRect(x + 3f, y + 3f, x + GameConfig.TILE + 3f, y + GameConfig.TILE + 3f, shadowPaint)
                        canvas.drawBitmap(SpriteAtlas.wall(Tile.STEEL, 0), null, spriteRect, spritePaint)
                    }
                }
            }
        }
    }

    private fun spawnTreads(frame: GameFrame, now: Long) {
        for (tank in frame.tanks) {
            if (!tank.alive || !tank.moving) continue
            val last = lastTreadAt[tank.slot] ?: 0L
            if (now - last >= 110L) {
                effects.addTread(tank.x, tank.y, tank.bodyDir)
                lastTreadAt[tank.slot] = now
            }
        }
    }

    private fun drawTanks(canvas: Canvas, frame: GameFrame, now: Long) {
        for (tank in frame.tanks) {
            if (!tank.alive) continue
            val isMe = tank.slot == frame.mySlot
            val color = if (isMe) COLOR_ME else PLAYER_COLORS[tank.slot % PLAYER_COLORS.size]

            canvas.drawCircle(tank.x + 2f, tank.y + 5f, 13f, shadowPaint)

            val alpha = if (tank.invulnerable && (now / 90L) % 2L == 0L) 90 else 220
            tankSpritePaint.alpha = alpha
            val treadFrame = if (tank.moving) ((now / 90L) % 2L).toInt() else 0
            val hull = SpriteAtlas.hull(color, treadFrame)
            val turret = SpriteAtlas.turret(color)

            canvas.save()
            canvas.translate(tank.x, tank.y)
            canvas.rotate(Math.toDegrees(tank.bodyDir.toDouble()).toFloat())
            spriteRect.set(-16f, -16f, 16f, 16f)
            canvas.drawBitmap(hull, null, spriteRect, tankSpritePaint)
            canvas.restore()

            canvas.save()
            canvas.translate(tank.x, tank.y)
            canvas.rotate(Math.toDegrees(tank.turretDir.toDouble()).toFloat())
            canvas.translate(-tank.cooldown * 4.5f, 0f)
            canvas.drawBitmap(turret, null, spriteRect, tankSpritePaint)
            canvas.restore()

            if (isMe) {
                strokePaint.color = 0x55FFFFFF
                strokePaint.strokeWidth = 1.2f
                canvas.drawCircle(tank.x, tank.y, 14f, strokePaint)
            }

            if (!isMe) {
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize = 10f * scaledDensity / camera.scale
                textPaint.color = 0xCCE6ECE9.toInt()
                canvas.drawText(tank.name, tank.x, tank.y - 19f, textPaint)
                val pipW = 7f
                for (i in 0 until GameConfig.MAX_HP) {
                    hudPaint.color = if (i < tank.hp) 0xFFE05555.toInt() else 0x55333333
                    val px = tank.x - pipW * 1.5f + i * (pipW + 1f)
                    canvas.drawRect(px, tank.y - 17f, px + pipW, tank.y - 14f, hudPaint)
                }
            }
        }
    }

    private fun drawBullets(canvas: Canvas, frame: GameFrame) {
        for (bullet in frame.bullets) {
            bulletPaint.color = 0x44FFD75E
            canvas.drawCircle(bullet.x, bullet.y, 8f, bulletPaint)
            bulletPaint.color = 0xFFFFE27A.toInt()
            canvas.drawCircle(bullet.x, bullet.y, 3f, bulletPaint)
        }
    }

    private fun drawPickups(canvas: Canvas, frame: GameFrame, now: Long) {
        for (pickup in frame.pickups) {
            val bob = sin(now / 320f + pickup.x * 0.05f) * 2f
            spriteRect.set(pickup.x - 14f, pickup.y - 14f + bob, pickup.x + 14f, pickup.y + 14f + bob)
            canvas.drawBitmap(SpriteAtlas.pickup(pickup.type), null, spriteRect, spritePaint)
            hudPaint.color = 0x22FFF0A0
            canvas.drawCircle(pickup.x, pickup.y + bob, 17f, hudPaint)
        }
    }

    private fun drawMines(canvas: Canvas, frame: GameFrame, now: Long) {
        for (mine in frame.mines) {
            val blink = mine.armed && (now / 380L) % 2L == 0L
            spriteRect.set(mine.x - 11f, mine.y - 11f, mine.x + 11f, mine.y + 11f)
            canvas.drawBitmap(SpriteAtlas.mine(mine.armed), null, spriteRect, spritePaint)
            if (blink) {
                bulletPaint.color = 0x66FF4040
                canvas.drawCircle(mine.x, mine.y, 10f, bulletPaint)
            }
        }
    }

    private fun drawFog(canvas: Canvas, frame: GameFrame, me: RenderTank?, now: Long) {
        // Extra margin covers camera shake so no unfogged strip appears at the screen edge.
        val margin = 96f
        val left = camera.worldLeft - margin
        val top = camera.worldTop - margin
        val right = camera.worldRight + margin
        val bottom = camera.worldBottom + margin
        val layer = canvas.saveLayer(left, top, right, bottom, null)
        canvas.drawRect(left, top, right, bottom, fogPaint)
        if (me != null) {
            if (me.alive) {
                if (me.lightsOn) {
                    buildOwnLight(frame, me)
                    canvas.drawPath(lightPath, clearPaint)
                }
                canvas.drawCircle(me.x, me.y, GameConfig.LIGHT_SELF_RADIUS, clearPaint)
            } else {
                canvas.drawCircle(me.x, me.y, 190f, clearPaint)
            }
        }
        canvas.restoreToCount(layer)

        if (me != null && me.alive) {
            val range = ownLightRange(me)
            if (me.lightsOn) {
                glowPaint.shader = RadialGradient(
                    me.x, me.y, range,
                    intArrayOf(0x5AFFF1C4, 0x24FFD98A, 0x00FFD98A),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP,
                )
                canvas.save()
                canvas.clipPath(lightPath)
                canvas.drawCircle(me.x, me.y, range, glowPaint)
                canvas.restore()
                glowPaint.shader = null
                for (i in 0 until glintCount) {
                    bulletPaint.color = 0x33FFE0A0
                    canvas.drawCircle(glintX[i], glintY[i], 3.5f, bulletPaint)
                }
            }
            glowPaint.shader = RadialGradient(
                me.x, me.y, 85f,
                intArrayOf(0x2EFFF1C4, 0x00FFF1C4),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(me.x, me.y, 85f, glowPaint)
            glowPaint.shader = null
        }
    }

    private fun ownLightRange(me: RenderTank): Float =
        if (me.lightBoost) GameConfig.LIGHT_RANGE * GameConfig.LIGHT_RANGE_BOOST else GameConfig.LIGHT_RANGE

    private fun ownLightHalfAngle(me: RenderTank): Float =
        if (me.lightBoost) GameConfig.LIGHT_HALF_ANGLE * GameConfig.LIGHT_HALF_ANGLE_BOOST else GameConfig.LIGHT_HALF_ANGLE

    private fun buildOwnLight(frame: GameFrame, me: RenderTank) {
        glintCount = 0
        lightPath.reset()
        lightPath.moveTo(me.x, me.y)
        val map = frame.map
        val range = ownLightRange(me)
        val half = ownLightHalfAngle(me)
        val start = me.turretDir - half
        val span = half * 2f
        val rays = GameConfig.LIGHT_RAYS
        val lastIndex = (rays - 1).toFloat()
        for (i in 0 until rays) {
            val angle = start + span * (i / lastIndex)
            var hitX = me.x + cos(angle) * range
            var hitY = me.y + sin(angle) * range
            var wallHit = false
            var t = 5f
            while (t < range) {
                val x = me.x + cos(angle) * t
                val y = me.y + sin(angle) * t
                if (map.blocksLightAtWorld(x, y)) {
                    hitX = x - cos(angle) * 4f
                    hitY = y - sin(angle) * 4f
                    wallHit = true
                    break
                }
                t += 5f
            }
            if (wallHit && glintCount < glintX.size) {
                glintX[glintCount] = hitX
                glintY[glintCount] = hitY
                glintCount++
            }
            lightPath.lineTo(hitX, hitY)
        }
        lightPath.close()
    }

    private fun drawEnemyBeams(canvas: Canvas, frame: GameFrame) {
        for (tank in frame.tanks) {
            if (!tank.alive || tank.slot == frame.mySlot || !tank.lightsOn) continue
            if (tank.x < camera.worldLeft - 400f || tank.x > camera.worldRight + 400f) continue
            if (tank.y < camera.worldTop - 400f || tank.y > camera.worldBottom + 400f) continue
            buildCone(enemyPath, frame, tank, GameConfig.LIGHT_RANGE * 0.9f, GameConfig.LIGHT_HALF_ANGLE)
            beamPaint.color = 0x0DFFE0A0
            canvas.drawPath(enemyPath, beamPaint)
            strokePaint.color = 0x1AFFE0A0
            strokePaint.strokeWidth = 1.2f
            canvas.drawPath(enemyPath, strokePaint)
        }
    }

    private fun buildCone(path: Path, frame: GameFrame, tank: RenderTank, range: Float, half: Float) {
        path.reset()
        path.moveTo(tank.x, tank.y)
        val rays = 14
        val start = tank.turretDir - half
        val span = half * 2f
        for (i in 0 until rays) {
            val angle = start + span * (i / (rays - 1f))
            var hitX = tank.x + cos(angle) * range
            var hitY = tank.y + sin(angle) * range
            var t = 6f
            while (t < range) {
                val x = tank.x + cos(angle) * t
                val y = tank.y + sin(angle) * t
                if (frame.map.blocksLightAtWorld(x, y)) {
                    hitX = x - cos(angle) * 3f
                    hitY = y - sin(angle) * 3f
                    break
                }
                t += 8f
            }
            path.lineTo(hitX, hitY)
        }
        path.close()
    }

    private fun drawZone(canvas: Canvas, frame: GameFrame) {
        val center = GameConfig.WORLD_SIZE / 2f
        strokePaint.style = Paint.Style.STROKE
        strokePaint.color = 0x66FF4D4D
        strokePaint.strokeWidth = 4f
        canvas.drawCircle(center, center, frame.zoneRadius, strokePaint)
        strokePaint.color = 0x33FF4D4D
        strokePaint.strokeWidth = 9f
        canvas.drawCircle(center, center, frame.zoneRadius + 3f, strokePaint)
    }

    private fun drawSonar(canvas: Canvas, frame: GameFrame) {
        if (!frame.sonarActive) return
        val center = GameConfig.WORLD_SIZE / 2f
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 3.5f
        strokePaint.color = 0x7757D68B
        canvas.drawCircle(center, center, frame.sonarRadius, strokePaint)
        strokePaint.strokeWidth = 9f
        strokePaint.color = 0x1E57D68B
        canvas.drawCircle(center, center, frame.sonarRadius, strokePaint)
    }

    private fun drawBlips(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        for (blip in blips) {
            if (blip.x < camera.worldLeft || blip.x > camera.worldRight) continue
            if (blip.y < camera.worldTop || blip.y > camera.worldBottom) continue
            val age = (now - blip.bornAt).coerceAtLeast(0L)
            val alpha = (1f - age / 1300f).coerceIn(0f, 1f)
            if (alpha <= 0f) continue
            bulletPaint.color = ((alpha * 120).toInt() shl 24) or 0x00FF3B3B
            canvas.drawCircle(blip.x, blip.y, 13f, bulletPaint)
            bulletPaint.color = ((alpha * 255).toInt() shl 24) or 0x00FF5252
            canvas.drawCircle(blip.x, blip.y, 5.5f, bulletPaint)
        }
    }

    private fun updateBlips(frame: GameFrame) {
        val now = SystemClock.elapsedRealtime()
        blips.removeAll { now - it.bornAt > 1400L }
        if (!frame.sonarActive) {
            lastSonarRadius = 0f
            return
        }
        val center = GameConfig.WORLD_SIZE / 2f
        val radius = frame.sonarRadius
        if (radius < lastSonarRadius) {
            lastSonarRadius = radius
            return
        }
        if (radius > lastSonarRadius) {
            for (tank in frame.tanks) {
                if (tank.slot == frame.mySlot || !tank.alive) continue
                // Stealth: a stationary tank with its headlight off is invisible to sonar.
                if (!tank.lightsOn && !tank.moving) continue
                val dist = hypot(tank.x - center, tank.y - center)
                if (dist > lastSonarRadius && dist <= radius) {
                    blips.add(Blip(tank.x, tank.y, now))
                }
            }
            lastSonarRadius = radius
        }
    }

    private fun drawAimGuide(canvas: Canvas, frame: GameFrame, me: RenderTank?) {
        if (me == null || !me.alive) return
        val range = ownLightRange(me)
        val ex = me.x + cos(me.turretDir) * range
        val ey = me.y + sin(me.turretDir) * range
        strokePaint.color = 0x3357D68B
        strokePaint.strokeWidth = 1.4f
        strokePaint.style = Paint.Style.STROKE
        canvas.drawLine(me.x, me.y, ex, ey, strokePaint)
        strokePaint.color = 0x9957D68B.toInt()
        canvas.drawCircle(ex, ey, 7f, strokePaint)
        canvas.drawLine(ex - 10f, ey, ex + 10f, ey, strokePaint)
        canvas.drawLine(ex, ey - 10f, ex, ey + 10f, strokePaint)
    }

    // -------------------------------------------------------------------- HUD

    private fun drawHud(canvas: Canvas, frame: GameFrame, now: Long) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val me = frame.me
        val hudLeft = safeLeft + 20f * density
        val hudTop = safeTop + 20f * density

        // HP pips.
        val pipW = 30f * density
        val pipH = 13f * density
        for (i in 0 until GameConfig.MAX_HP) {
            val left = hudLeft + i * (pipW + 7f * density)
            hudPaint.color = if (i < (me?.hp ?: 0)) COLOR_HP else 0x33FF5252
            canvas.drawRoundRect(
                RectF(left, hudTop, left + pipW, hudTop + pipH),
                4f * density, 4f * density, hudPaint,
            )
        }
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 13f * scaledDensity
        textPaint.color = COLOR_DIM
        val alive = frame.tanks.count { it.alive }
        val total = frame.tanks.size
        canvas.drawText("В ЖИВЫХ $alive / $total", hudLeft, hudTop + 36f * density, textPaint)

        // Buff badges.
        if (me != null) {
            if (me.powered) drawBadge(canvas, hudLeft, hudTop + 52f * density, "P", 0xFFFF9A3C.toInt())
            if (me.lightBoost) drawBadge(canvas, hudLeft + 24f * density, hudTop + 52f * density, "L", 0xFFFFE27A.toInt())
            if (!me.lightsOn) drawBadge(canvas, hudLeft + 48f * density, hudTop + 52f * density, "•", 0x66FFFFFF)
        }

        // Movement stick.
        val moveBaseXDraw = if (moveActive) moveBaseX else safeLeft + 104f * density
        val moveBaseYDraw = if (moveActive) moveBaseY else h - safeBottom - 104f * density
        val moveKnobXDraw = if (moveActive) this.moveKnobX else moveBaseXDraw
        val moveKnobYDraw = if (moveActive) this.moveKnobY else moveBaseYDraw
        hudPaint.color = 0x22FFFFFF
        canvas.drawCircle(moveBaseXDraw, moveBaseYDraw, moveRadius, hudPaint)
        strokePaint.style = Paint.Style.STROKE
        strokePaint.color = 0x44FFFFFF
        strokePaint.strokeWidth = 2f * density
        canvas.drawCircle(moveBaseXDraw, moveBaseYDraw, moveRadius, strokePaint)
        hudPaint.color = if (moveActive) 0x77FFFFFF else 0x44FFFFFF
        canvas.drawCircle(moveKnobXDraw, moveKnobYDraw, moveKnobRadius, hudPaint)

        // Aim stick (faint default position, bright while held).
        val aimBaseXDraw = if (aimActive) aimBaseX else w - safeRight - 250f * density
        val aimBaseYDraw = if (aimActive) aimBaseY else h - safeBottom - 110f * density
        val aimKnobXDraw = if (aimActive) this.aimKnobX else aimBaseXDraw
        val aimKnobYDraw = if (aimActive) this.aimKnobY else aimBaseYDraw
        hudPaint.color = 0x1A57D68B
        canvas.drawCircle(aimBaseXDraw, aimBaseYDraw, aimRadius, hudPaint)
        strokePaint.color = if (aimActive) 0x6657D68B else 0x2E57D68B
        strokePaint.strokeWidth = 2f * density
        canvas.drawCircle(aimBaseXDraw, aimBaseYDraw, aimRadius, strokePaint)
        hudPaint.color = if (aimActive && hypot(aimX, aimY) >= GameConfig.AIM_FIRE_THRESHOLD) {
            0x99FF5252.toInt()
        } else {
            0x5557D68B
        }
        canvas.drawCircle(aimKnobXDraw, aimKnobYDraw, aimKnobRadius, hudPaint)
        strokePaint.strokeWidth = 1.5f * density
        strokePaint.color = 0x55FF8A8A
        canvas.drawCircle(aimBaseXDraw, aimBaseYDraw, aimRadius * GameConfig.AIM_FIRE_THRESHOLD, strokePaint)

        // Fire button with reload arc.
        val fx = fireButtonX()
        val fy = fireButtonY()
        hudPaint.color = if (firing) 0x99FF5252.toInt() else 0x33FF5252
        canvas.drawCircle(fx, fy, fireRadius, hudPaint)
        strokePaint.color = if (firing) 0xFFFF8080.toInt() else 0x66FF8080
        strokePaint.strokeWidth = 2.5f * density
        canvas.drawCircle(fx, fy, fireRadius, strokePaint)
        hudPaint.color = if (firing) 0xFFFFFFFF.toInt() else 0xB3FFFFFF.toInt()
        canvas.drawCircle(fx, fy, fireRadius * 0.42f, hudPaint)

        val cd = me?.cooldown ?: 0f
        if (cd > 0.01f) {
            arcPaint.strokeWidth = 5f * density
            arcPaint.color = 0xAAFFD75E.toInt()
            arcRect.set(fx - fireRadius - 9f * density, fy - fireRadius - 9f * density, fx + fireRadius + 9f * density, fy + fireRadius + 9f * density)
            canvas.drawArc(arcRect, -90f, 360f * (1f - cd), false, arcPaint)
        }

        // Mine button.
        val mx = mineButtonX()
        val my = mineButtonY()
        hudPaint.color = 0x22000000
        canvas.drawCircle(mx, my, mineRadius, hudPaint)
        strokePaint.color = 0x55B0B0B0
        strokePaint.strokeWidth = 1.5f * density
        canvas.drawCircle(mx, my, mineRadius, strokePaint)
        spriteRect.set(mx - 18f * density, my - 18f * density, mx + 18f * density, my + 18f * density)
        canvas.drawBitmap(SpriteAtlas.mine(true), null, spriteRect, spritePaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 12f * scaledDensity
        textPaint.color = if ((me?.mines ?: 0) > 0) COLOR_TEXT else 0x55FFFFFF
        canvas.drawText("${me?.mines ?: 0}", mx + mineRadius * 0.8f, my + mineRadius * 0.9f, textPaint)

        // Headlight toggle.
        val lx = lightButtonX()
        val ly = lightButtonY()
        hudPaint.color = if (lightsOn) 0x33FFE27A else 0x22000000
        canvas.drawCircle(lx, ly, lightRadius, hudPaint)
        strokePaint.color = if (lightsOn) 0xAAFFE27A.toInt() else 0x55FFFFFF
        strokePaint.strokeWidth = 1.5f * density
        canvas.drawCircle(lx, ly, lightRadius, strokePaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 15f * scaledDensity
        textPaint.color = if (lightsOn) 0xFFFFE27A.toInt() else 0x66FFFFFF
        canvas.drawText("Ф", lx, ly + 5f * density, textPaint)

        // Arrow to the safe zone when outside.
        if (me != null && me.alive) {
            val dist = hypot(me.x - GameConfig.WORLD_SIZE / 2f, me.y - GameConfig.WORLD_SIZE / 2f)
            if (dist > frame.zoneRadius) {
                val angle = kotlin.math.atan2(
                    GameConfig.WORLD_SIZE / 2f - me.y,
                    GameConfig.WORLD_SIZE / 2f - me.x,
                )
                canvas.save()
                canvas.translate(w / 2f, h - safeBottom - 34f * density)
                canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat() + 90f)
                val arrow = Path()
                arrow.moveTo(0f, -16f * density)
                arrow.lineTo(12f * density, 12f * density)
                arrow.lineTo(-12f * density, 12f * density)
                arrow.close()
                hudPaint.color = 0xCCFF5252.toInt()
                canvas.drawPath(arrow, hudPaint)
                canvas.restore()
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize = 12f * scaledDensity
                textPaint.color = 0xCCFF8A8A.toInt()
                canvas.drawText("ВНЕ ЗОНЫ", w / 2f, h - safeBottom - 62f * density, textPaint)
            }
        }

        if (me != null && !me.alive && !frame.roundOver) {
            drawCenteredText(canvas, "УНИЧТОЖЕН", h / 2f, 22f, 0xCCFF6B6B.toInt())
            drawCenteredText(canvas, "наблюдение", h / 2f + 30f * density, 13f, COLOR_DIM)
        }

        if (now < hintUntil && !frame.roundOver) {
            val alpha = ((hintUntil - now) / 6500f).coerceIn(0f, 1f)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 12.5f * scaledDensity
            textPaint.color = ((alpha * 200).toInt() shl 24) or 0x00E6ECE9
            canvas.drawText(
                "левый стик — езда · правый — башня/огонь · Ф — фара",
                w / 2f, safeTop + 92f * density, textPaint,
            )
        }

        drawFeed(canvas, w, now)

        if (frame.roundOver) {
            hudPaint.color = 0xB3000000.toInt()
            canvas.drawRect(0f, 0f, w, h, hudPaint)
            val title = when {
                frame.winnerName == null -> "НИЧЬЯ"
                me != null && frame.winnerName == me.name -> "ПОБЕДА!"
                else -> "ПОБЕДИЛ: ${frame.winnerName}"
            }
            drawCenteredText(canvas, title, h / 2f - 10f * density, 34f, COLOR_ME)
            drawCenteredText(canvas, "возврат в лобби…", h / 2f + 40f * density, 15f, COLOR_DIM)
        }
    }

    private fun updateFeed(frame: GameFrame, now: Long) {
        for (tank in frame.tanks) {
            val wasAlive = lastAlive[tank.slot]
            if (wasAlive == true && !tank.alive) {
                feed.add(FeedLine("${tank.name} уничтожен", now))
            }
            lastAlive[tank.slot] = tank.alive
        }
        feed.removeAll { now - it.at > 4500L }
    }

    private fun drawFeed(canvas: Canvas, w: Float, now: Long) {
        if (feed.isEmpty() || lastFrame?.roundOver == true) return
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = 12f * scaledDensity
        for ((i, line) in feed.withIndex()) {
            val age = now - line.at
            val alpha = (1f - age / 4500f).coerceIn(0f, 1f)
            textPaint.color = ((alpha * 200).toInt() shl 24) or 0x00FFB0A0
            canvas.drawText(line.text, w - safeRight - 20f * density, safeTop + 78f * density + i * 18f * density, textPaint)
        }
    }

    private fun drawBadge(canvas: Canvas, x: Float, y: Float, label: String, color: Int) {
        hudPaint.color = (0x33 shl 24) or (color and 0x00FFFFFF)
        canvas.drawCircle(x + 9f * density, y, 10f * density, hudPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 11f * scaledDensity
        textPaint.color = color
        canvas.drawText(label, x + 9f * density, y + 4f * density, textPaint)
    }

    private fun drawCenteredText(canvas: Canvas, text: String, y: Float, sizeSp: Float, color: Int) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = sizeSp * scaledDensity
        textPaint.color = color
        canvas.drawText(text, canvas.width / 2f, y, textPaint)
    }

    private data class Blip(val x: Float, val y: Float, val bornAt: Long)

    private data class FeedLine(val text: String, val at: Long)

    companion object {
        private val COLOR_VOID = 0xFF05070A.toInt()
        private val COLOR_GROUND = 0xFF1A221A.toInt()
        private val COLOR_ME = 0xFF57D68B.toInt()
        private val COLOR_DIM = 0xFF8A9A92.toInt()
        private val COLOR_TEXT = 0xFFE6ECE9.toInt()
        private val COLOR_HP = 0xFFFF5252.toInt()
        private val COLOR_FOG = 0xF8090C10.toInt()
        private val PLAYER_COLORS = intArrayOf(
            0xFF57D68B.toInt(),
            0xFFE05A5A.toInt(),
            0xFFE0A23F.toInt(),
            0xFFB06BE0.toInt(),
            0xFF43C6E8.toInt(),
            0xFFE86FB0.toInt(),
        )
    }
}
