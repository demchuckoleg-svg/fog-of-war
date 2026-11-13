package com.fogofwar.game.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.util.Log
import android.util.LruCache
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import com.fogofwar.game.App
import com.fogofwar.game.core.EffectKind
import com.fogofwar.game.core.GameConfig
import com.fogofwar.game.core.Tile
import com.fogofwar.game.core.TileMap
import com.fogofwar.game.core.angleDelta
import com.fogofwar.game.net.GameFrame
import com.fogofwar.game.net.NearbyNet
import com.fogofwar.game.net.RenderTank
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * SurfaceView renderer: pixel-art world -> effects -> tanks -> raycast headlight fog ->
 * enemy beams -> zone/sonar -> sound-echo overlays -> twin-stick HUD.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val net: NearbyNet
        get() = (context.applicationContext as App).net

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val prefs = context.getSharedPreferences("fogofwar", Context.MODE_PRIVATE)
    private val stickScale = prefs.getFloat("stick_scale", 1f).coerceIn(0.7f, 1.4f)
    private val vibrator: Vibrator? = run {
        val ctx = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(VibratorManager::class.java))?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private val camera = Camera()
    private val effects = Effects()

    private val lightPath = Path()
    private val enemyPath = Path()
    private val selfHole = Path()
    private val visibleNow = BooleanArray(GameConfig.MAX_PLAYERS)
    private val glintX = FloatArray(GameConfig.LIGHT_RAYS)
    private val glintY = FloatArray(GameConfig.LIGHT_RAYS)
    private var glintCount = 0

    // Shaders live in tank-local coordinates and are reused; only the canvas is translated.
    private val coneGlow = RadialGradient(
        0f, 0f, GameConfig.LIGHT_RANGE,
        intArrayOf(0x5AFFF1C4, 0x24FFD98A, 0x00FFD98A),
        floatArrayOf(0f, 0.55f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val coneGlowBoost = RadialGradient(
        0f, 0f, GameConfig.LIGHT_RANGE * GameConfig.LIGHT_RANGE_BOOST,
        intArrayOf(0x5AFFF1C4, 0x24FFD98A, 0x00FFD98A),
        floatArrayOf(0f, 0.55f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val haloGlow = RadialGradient(
        0f, 0f, 85f,
        intArrayOf(0x2EFFF1C4, 0x00FFF1C4),
        floatArrayOf(0f, 1f),
        Shader.TileMode.CLAMP,
    )

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
    private val renderGeneration = AtomicInteger()

    @Volatile
    private var running = false

    @Volatile
    private var lastFrame: GameFrame? = null

    private val blips = ArrayList<Blip>()
    private var lastSonarRadius = 0f
    private val lastTreadAt = HashMap<Int, Long>()
    private val feed = ArrayList<FeedLine>()

    /** Where enemies were last seen in the headlight, for the fading "last seen" markers. */
    private data class Seen(val x: Float, val y: Float, val turretDir: Float, val at: Long)
    private val lastSeen = ConcurrentHashMap<Int, Seen>()

    private var minimapBitmap: Bitmap? = null
    private var minimapMap: TileMap? = null
    private var minimapTick = -1000L
    private var minimapPixels: IntArray? = null

    private class WorldChunk(val bitmap: Bitmap, val hash: Long)

    /** 8x8-tile cached terrain; ~6 MB at the 24-chunk cap. */
    private val chunkCache = LruCache<Long, WorldChunk>(24)

    private val minimapPaint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
    }

    // Touch state written by the UI thread and read by the render thread.
    @Volatile private var moveX = 0f
    @Volatile private var moveY = 0f
    @Volatile private var aimX = 0f
    @Volatile private var aimY = 0f
    @Volatile private var lightsOn = true
    @Volatile private var throwGrenadeRequest = false

    /** Queued one-shot: send fire=true with this angle for a couple of input ticks. */
    @Volatile private var shotAngle = Float.NaN
    @Volatile private var shotSendsLeft = 0
    @Volatile private var shotVisualAngle = Float.NaN
    @Volatile private var shotVisualUntil = 0L

    @Volatile private var moveActive = false
    @Volatile private var moveBaseX = 0f
    @Volatile private var moveBaseY = 0f
    @Volatile private var moveKnobX = 0f
    @Volatile private var moveKnobY = 0f

    @Volatile private var aimActive = false
    @Volatile private var aimDragged = false
    @Volatile private var aimBaseX = 0f
    @Volatile private var aimBaseY = 0f
    @Volatile private var aimKnobX = 0f
    @Volatile private var aimKnobY = 0f

    @Volatile private var movePointerId = -1
    @Volatile private var aimPointerId = -1
    @Volatile private var grenadePointerId = -1

    private var moveRadius = 0f
    private var moveKnobRadius = 0f
    private var aimRadius = 0f
    private var aimKnobRadius = 0f
    private var grenadeRadius = 0f
    private var lightRadius = 0f

    private var hintUntil = 0L
    private var lastMyHp = -1
    private var lastMySlot = -1
    private var shakeUntil = 0L
    private var shakePower = 0f
    private var shakeDX = 0f
    private var shakeDY = 0f

    private var lightCacheValid = false
    private var lightCacheMap: TileMap? = null
    private var lightCacheX = 0f
    private var lightCacheY = 0f
    private var lightCacheDir = 0f
    private var lightCacheBoost = false
    private var lightCacheRevision = -1L

    private val perf = PerfOverlay()
    @Volatile private var minimapLeft = 0f
    @Volatile private var minimapTop = 0f
    @Volatile private var minimapSize = 0f
    private var perfTapCount = 0
    private var perfTapAt = 0L
    /** 0 = overlay off, 1 = overlay + software canvas, 2 = overlay + hardware canvas. */
    private var perfMode = 0
    @Volatile private var hardwareCanvas = false
    @Volatile private var hardwareFallback = false
    private var renderRestarts = 0
    private var lastRestartAt = 0L
    private var renderErrorUntil = 0L
    @Volatile private var countdownUntil = 0L

    @Volatile private var safeLeft = 0
    @Volatile private var safeTop = 0
    @Volatile private var safeRight = 0
    @Volatile private var safeBottom = 0

    init {
        holder.addCallback(this)
        isFocusable = true
        keepScreenOn = true
        hardwareCanvas = prefs.getBoolean("hw_canvas", true)
        perfMode = when {
            !prefs.getBoolean("perf_overlay", false) -> 0
            hardwareCanvas -> 2
            else -> 1
        }
        perf.enabled = perfMode > 0
        SpriteAtlas.warmUp(context)
    }

    fun setSafeInsets(left: Int, top: Int, right: Int, bottom: Int) {
        safeLeft = left
        safeTop = top
        safeRight = right
        safeBottom = bottom
    }

    /** Shows a local 3-2-1 overlay; the host freezes the simulation for the same time. */
    fun startCountdown(durationMs: Long = GameConfig.ROUND_COUNTDOWN_MS) {
        countdownUntil = SystemClock.elapsedRealtime() + durationMs
    }

    /** Short haptic tick; disabled in settings if the player prefers silence. */
    private fun vibrate(durationMs: Long, amplitude: Int) {
        if (!prefs.getBoolean("haptics", true)) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255)))
        } catch (_: Throwable) {
        }
    }

    fun pauseRendering() {
        // Stop the server-side tank immediately instead of leaving the last held input alive.
        running = false
        renderGeneration.incrementAndGet()
        resetInput()
        try {
            net.sendInput(0f, 0f, 0f, 0f, false, lightsOn, false)
        } catch (t: Throwable) {
            Log.w("GameView", "could not send the stop input", t)
        }
        val t = renderThread
        renderThread = null
        if (t != null && t !== Thread.currentThread()) {
            try {
                t.join(1_500)
            } catch (_: InterruptedException) {
            }
            if (t.isAlive) Log.w("GameView", "render thread did not stop in time")
        }
    }

    fun resumeRendering() {
        if (running && renderThread?.isAlive == true) return
        if (holder.surface?.isValid != true) return
        startThread()
    }

    /** Stops the local tank without pausing the renderer (window lost focus, shade pulled). */
    fun releaseInput() {
        resetInput()
        try {
            net.sendInput(0f, 0f, 0f, 0f, false, lightsOn, false)
        } catch (t: Throwable) {
            Log.w("GameView", "could not send the stop input", t)
        }
    }

    private fun startThread() {
        val generation = renderGeneration.incrementAndGet()
        running = true
        val thread = Thread({ runLoop(generation) }, "GameRender")
        renderThread = thread
        thread.start()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (renderThread?.isAlive != true) startThread()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) = pauseRendering()

    private fun runLoop(generation: Int) {
        var lastInput = 0L
        var failures = 0
        while (running && renderGeneration.get() == generation) {
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
                                vibrate(60L, (120 * (1f - dist / 520f)).toInt().coerceAtLeast(40))
                            }
                            EffectKind.SHOT -> if (dist < 300f) {
                                addShake((1f - dist / 300f) * 4f * density, 130L)
                            }
                            EffectKind.PICKUP -> if (dist < 120f) vibrate(25L, 90)
                            else -> Unit
                        }
                    }
                }

                val kills = net.pollKills()
                if (kills.isNotEmpty()) {
                    val names = lastFrame?.tanks?.associate { it.slot to it.name } ?: emptyMap()
                    for (kill in kills) {
                        val victim = names[kill.victim] ?: "?"
                        val text = if (kill.killer >= 0) {
                            "${names[kill.killer] ?: "?"} → $victim"
                        } else {
                            "$victim уничтожен"
                        }
                        feed.add(FeedLine(text, now))
                    }
                }

                if (now - lastInput >= 1_000L / GameConfig.INPUT_SEND_HZ) {
                    lastInput = now
                    val grenadeRequested = throwGrenadeRequest
                    val tapAngle = shotAngle
                    val hasShot = shotSendsLeft > 0 && !tapAngle.isNaN()
                    net.sendInput(
                        moveX, moveY,
                        if (hasShot) cos(tapAngle) else aimX,
                        if (hasShot) sin(tapAngle) else aimY,
                        fire = hasShot,
                        lights = lightsOn,
                        throwGrenade = grenadeRequested,
                    )
                    if (hasShot) {
                        shotSendsLeft -= 1
                        if (shotSendsLeft <= 0) shotAngle = Float.NaN
                    }
                    if (grenadeRequested) throwGrenadeRequest = false
                }

                val frame = net.currentFrame()
                if (frame != null) {
                    updateBlips(frame)
                    lastFrame = frame
                }
                val canvas = lockRenderCanvas()
                if (canvas == null) {
                    SystemClock.sleep(4)
                    continue
                }
                try {
                    drawFrame(canvas, lastFrame)
                } finally {
                    holder.unlockCanvasAndPost(canvas)
                }
                failures = 0
                if (renderRestarts > 0 &&
                    SystemClock.elapsedRealtime() - lastRestartAt > 10_000L
                ) {
                    renderRestarts = 0
                }
            } catch (t: Throwable) {
                if (running && renderGeneration.get() == generation) {
                    failures++
                    Log.e("GameView", "render loop failed ($failures)", t)
                    if (failures >= 3) {
                        if (renderRestarts >= 3) {
                            Log.e("GameView", "render failed too many times; giving up")
                            net.reportError("Не удалось восстановить рендер. Перезапустите приложение.")
                            pauseRendering()
                            return
                        }
                        renderRestarts++
                        lastRestartAt = SystemClock.elapsedRealtime()
                        renderErrorUntil = lastRestartAt + 2_500L
                        renderGeneration.incrementAndGet()
                        renderThread = null
                        postDelayed({
                            if (running && holder.surface?.isValid == true) startThread()
                        }, 400L)
                        return
                    }
                }
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

    private fun grenadeButtonX() = width - safeRight - 92f * density
    private fun grenadeButtonY() = height - safeBottom - 92f * density
    private fun lightButtonX() = safeLeft + 40f * density
    private fun lightButtonY() = safeTop + 152f * density

    private fun handlePointerDown(event: MotionEvent, index: Int) {
        val id = event.getPointerId(index)
        val x = event.getX(index)
        val y = event.getY(index)

        val tapNow = SystemClock.elapsedRealtime()
        if (minimapSize > 0f &&
            x >= minimapLeft && x <= minimapLeft + minimapSize &&
            y >= minimapTop && y <= minimapTop + minimapSize
        ) {
            // Five taps on the minimap cycle: overlay off -> software -> hardware.
            if (tapNow - perfTapAt > 1_500L) perfTapCount = 0
            perfTapAt = tapNow
            perfTapCount++
            if (perfTapCount >= 5) {
                perfTapCount = 0
                perfMode = (perfMode + 1) % 3
                perf.enabled = perfMode > 0
                hardwareFallback = false
                if (perfMode != 0) {
                    hardwareCanvas = perfMode == 2
                    prefs.edit().putBoolean("hw_canvas", hardwareCanvas).apply()
                }
                prefs.edit().putBoolean("perf_overlay", perf.enabled).apply()
                val message = when (perfMode) {
                    1 -> "Оверлей: software-канвас"
                    2 -> "Оверлей: hardware-канвас"
                    else -> "Оверлей выключен"
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (hypot(x - lightButtonX(), y - lightButtonY()) <= lightRadius) {
            lightsOn = !lightsOn
            return
        }
        if (hypot(x - grenadeButtonX(), y - grenadeButtonY()) <= grenadeRadius && grenadePointerId == -1) {
            grenadePointerId = id
            throwGrenadeRequest = true
            return
        }

        // A second finger landing on the already-held grenade button must not become the aim stick.
        if (x >= width / 2f && hypot(x - grenadeButtonX(), y - grenadeButtonY()) <= grenadeRadius) return

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
            aimDragged = false
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
                val rawLen = hypot(dx, dy)
                if (rawLen > 14f * density) aimDragged = true
                if (rawLen > aimRadius) {
                    dx = dx / rawLen * aimRadius
                    dy = dy / rawLen * aimRadius
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
            val dragged = aimDragged
            val magnitude = hypot(aimX, aimY)
            // A tiny drag is still a tap: players expect auto-aim instead of a dead release.
            val angle = if (!dragged || magnitude < GameConfig.AIM_DEADZONE) {
                autoAimAngle()
            } else {
                kotlin.math.atan2(aimY, aimX)
            }
            aimPointerId = -1
            aimActive = false
            aimDragged = false
            aimX = 0f
            aimY = 0f
            queueShot(angle)
        }
        if (id == grenadePointerId) grenadePointerId = -1
    }

    private fun resetInput() {
        movePointerId = -1
        aimPointerId = -1
        grenadePointerId = -1
        moveActive = false
        aimActive = false
        aimDragged = false
        moveX = 0f
        moveY = 0f
        aimX = 0f
        aimY = 0f
        throwGrenadeRequest = false
        shotAngle = Float.NaN
        shotSendsLeft = 0
    }

    private fun queueShot(angle: Float) {
        shotAngle = angle
        shotSendsLeft = 2
        shotVisualAngle = angle
        shotVisualUntil = SystemClock.elapsedRealtime() + 260L
    }

    /** Tap on the aim stick: nearest visible enemy or last-seen ghost, otherwise a random direction. */
    private fun autoAimAngle(): Float {
        val frame = lastFrame
        val me = frame?.me
        if (frame != null && me != null && me.alive) {
            var best: RenderTank? = null
            var bestDist = GameConfig.AUTO_AIM_RANGE
            for (tank in frame.tanks) {
                if (!tank.alive || tank.slot == frame.mySlot) continue
                if (isAlly(frame, me, tank)) continue
                if (!isVisibleToMe(frame, me, tank)) continue
                val dist = hypot(tank.x - me.x, tank.y - me.y)
                if (dist < bestDist) {
                    best = tank
                    bestDist = dist
                }
            }
            if (best != null) {
                return kotlin.math.atan2(best.y - me.y, best.x - me.x)
            }

            val now = SystemClock.elapsedRealtime()
            var ghostX = 0f
            var ghostY = 0f
            var ghostDist = GameConfig.AUTO_AIM_RANGE
            for (seen in lastSeen.values) {
                if (now - seen.at > GHOST_MS) continue
                val dist = hypot(seen.x - me.x, seen.y - me.y)
                if (dist < ghostDist) {
                    ghostDist = dist
                    ghostX = seen.x
                    ghostY = seen.y
                }
            }
            if (ghostDist < GameConfig.AUTO_AIM_RANGE) {
                return kotlin.math.atan2(ghostY - me.y, ghostX - me.x)
            }
        }
        return (Math.random() * Math.PI * 2.0).toFloat()
    }

    /** True while an enemy is inside the local headlight cone (or hugging the tank). */
    private fun isVisibleToMe(frame: GameFrame, me: RenderTank, enemy: RenderTank): Boolean {
        if (!me.alive || !enemy.alive) return false
        val dx = enemy.x - me.x
        val dy = enemy.y - me.y
        val dist = hypot(dx, dy)
        if (dist <= GameConfig.LIGHT_SELF_RADIUS) return true
        if (!me.lightsOn) return false
        if (dist > ownLightRange(me)) return false
        if (Math.abs(angleDelta(me.turretDir, kotlin.math.atan2(dy, dx))) > ownLightHalfAngle(me)) {
            return false
        }
        return lineOfSight(frame.map, me.x, me.y, enemy.x, enemy.y)
    }

    private fun updateLastSeen(frame: GameFrame, now: Long) {
        visibleNow.fill(false)
        val me = frame.me
        if (me != null && me.alive) {
            for (tank in frame.tanks) {
                if (tank.slot == frame.mySlot || !tank.alive) continue
                if (isAlly(frame, me, tank)) continue
                if (isVisibleToMe(frame, me, tank)) {
                    lastSeen[tank.slot] = Seen(tank.x, tank.y, tank.turretDir, now)
                    if (tank.slot in visibleNow.indices) visibleNow[tank.slot] = true
                }
            }
        }
        lastSeen.entries.removeAll { now - it.value.at > GHOST_MS + 1000L }
    }

    private fun isAlly(frame: GameFrame, me: RenderTank?, other: RenderTank): Boolean =
        me != null && frame.rules.teams && me.team == other.team

    private fun lineOfSight(map: TileMap, ax: Float, ay: Float, bx: Float, by: Float): Boolean =
        !map.blocksLightAlong(ax, ay, bx, by)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        moveRadius = 58f * density * stickScale
        moveKnobRadius = 24f * density * stickScale
        aimRadius = 50f * density * stickScale
        aimKnobRadius = 21f * density * stickScale
        grenadeRadius = 36f * density
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
        val now = SystemClock.elapsedRealtime()
        if (frame == null) {
            if (now < countdownUntil) {
                drawCountdown(canvas, countdownUntil - now)
            } else {
                drawCenteredText(canvas, "СОЕДИНЕНИЕ…", canvas.height / 2f, 20f, COLOR_DIM)
                drawCenteredText(canvas, "ожидание состояния от хоста", canvas.height / 2f + 34f * density, 13f, COLOR_DIM)
            }
            return
        }
        if (hintUntil == 0L) hintUntil = now + 6500L

        val me = frame.me
        val drawStart = System.nanoTime()
        updateFeed(now)
        updateLastSeen(frame, now)
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
            } else if (me.hp != lastMyHp) {
                if (me.hp < lastMyHp) {
                    addShake(9f * density, 320L)
                    vibrate(120L, 220)
                }
                lastMyHp = me.hp
            }
        }

        canvas.save()
        updateShake(now)
        canvas.translate(camera.offsetX + shakeDX, camera.offsetY + shakeDY)
        canvas.scale(camera.scale, camera.scale)

        drawWorld(canvas, frame, now)
        spawnTreads(frame, now)
        effects.draw(canvas)
        drawBullets(canvas, frame)
        drawPickups(canvas, frame, now)
        drawGrenades(canvas, frame, now)
        val worldDone = System.nanoTime()

        drawTanks(canvas, frame, now)
        val tanksDone = System.nanoTime()

        drawFog(canvas, frame, me, now)
        drawRevealedTanks(canvas, frame, now)
        val fogDone = System.nanoTime()

        drawEnemyBeams(canvas, frame)
        drawZone(canvas, frame)
        drawSonar(canvas, frame)
        drawBlips(canvas)
        effects.drawOverlay(canvas)
        drawAimGuide(canvas, frame, me, now)

        canvas.restore()

        drawHud(canvas, frame, now)
        val hudDone = System.nanoTime()

        if (now < countdownUntil) drawCountdown(canvas, countdownUntil - now)

        if (perf.enabled) {
            perf.sections[0] = (worldDone - drawStart) / 1_000_000f
            perf.sections[1] = (tanksDone - worldDone) / 1_000_000f
            perf.sections[2] = (fogDone - tanksDone) / 1_000_000f
            perf.sections[3] = (hudDone - fogDone) / 1_000_000f
            val total = System.nanoTime() - drawStart
            perf.sections[4] = total / 1_000_000f
            perf.frame(total)
            perf.draw(
                canvas,
                safeLeft + 12f * density,
                safeTop + 134f * density,
                10f * scaledDensity,
                net.bytesSent.get(),
                net.bytesReceived.get(),
                when {
                    hardwareFallback -> "sw*"
                    hardwareCanvas -> "hw"
                    else -> "sw"
                },
            )
        }
    }

    private fun updateShake(now: Long) {
        if (now >= shakeUntil) {
            shakePower = 0f
            shakeDX = 0f
            shakeDY = 0f
            return
        }
        val fade = ((shakeUntil - now) / 320f).coerceIn(0f, 1f)
        val power = shakePower * fade
        shakeDX = sin(now * 0.09f) * power
        shakeDY = cos(now * 0.13f) * power
    }

    /** Hardware canvas when the debug mode asks for it; software is the safe fallback. */
    private fun lockRenderCanvas(): Canvas? {
        if (hardwareCanvas && !hardwareFallback) {
            try {
                holder.lockHardwareCanvas()?.let { return it }
            } catch (t: Throwable) {
                hardwareFallback = true
                Log.w("GameView", "hardware canvas unavailable, falling back to software", t)
            }
        }
        return holder.lockCanvas()
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

        // Static terrain is cached in 8x8-tile chunks; only changed chunks are rebuilt.
        for (cy in ty0 / CHUNK_TILES..ty1 / CHUNK_TILES) {
            for (cx in tx0 / CHUNK_TILES..tx1 / CHUNK_TILES) {
                drawChunk(canvas, map, cx, cy)
            }
        }

        // Water is animated, so it stays out of the chunk cache and is drawn on top.
        for (ty in ty0..ty1) {
            for (tx in tx0..tx1) {
                if (map.tileAt(tx, ty) != Tile.WATER) continue
                val x = tx * GameConfig.TILE
                val y = ty * GameConfig.TILE
                spriteRect.set(x, y, x + GameConfig.TILE, y + GameConfig.TILE)
                val frameIndex = ((now / 420L) + tx + ty).toInt() and 1
                canvas.drawBitmap(SpriteAtlas.wall(Tile.WATER, frameIndex), null, spriteRect, spritePaint)
            }
        }
    }

    private fun drawChunk(canvas: Canvas, map: TileMap, cx: Int, cy: Int) {
        val key = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFF_FFFFL)
        val hash = chunkHash(map, cx, cy)
        var chunk = chunkCache.get(key)
        if (chunk == null || chunk.hash != hash) {
            val bitmap = Bitmap.createBitmap(CHUNK_PX, CHUNK_PX, Bitmap.Config.ARGB_8888)
            val chunkCanvas = Canvas(bitmap)
            val left = cx * CHUNK_TILES
            val top = cy * CHUNK_TILES
            val right = minOf(map.width - 1, left + CHUNK_TILES - 1)
            val bottom = minOf(map.height - 1, top + CHUNK_TILES - 1)
            for (ty in top..bottom) {
                for (tx in left..right) {
                    drawStaticTile(chunkCanvas, map, tx, ty, left, top)
                }
            }
            chunk = WorldChunk(bitmap, hash)
            chunkCache.put(key, chunk)
        }
        val worldX = cx * CHUNK_PX.toFloat()
        val worldY = cy * CHUNK_PX.toFloat()
        spriteRect.set(worldX, worldY, worldX + CHUNK_PX, worldY + CHUNK_PX)
        canvas.drawBitmap(chunk.bitmap, null, spriteRect, spritePaint)
    }

    private fun drawStaticTile(
        canvas: Canvas,
        map: TileMap,
        tx: Int,
        ty: Int,
        originTx: Int,
        originTy: Int,
    ) {
        val tile = map.tileAt(tx, ty)
        if (tile == Tile.WATER) return
        val x = (tx - originTx) * GameConfig.TILE
        val y = (ty - originTy) * GameConfig.TILE
        spriteRect.set(x, y, x + GameConfig.TILE, y + GameConfig.TILE)
        when (tile) {
            Tile.EMPTY -> {
                // Sparse detail: the base ground rect already covers the surface.
                val hash = tx * 31 + ty * 17
                if ((hash and 1) == 0) {
                    val variant = (hash ushr 1) and 3
                    canvas.drawBitmap(SpriteAtlas.ground(variant), null, spriteRect, spritePaint)
                }
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
            Tile.WATER -> Unit
        }
    }

    /** FNV-1a over a chunk's tile types; changes rebuild only that chunk. */
    private fun chunkHash(map: TileMap, cx: Int, cy: Int): Long {
        val left = cx * CHUNK_TILES
        val top = cy * CHUNK_TILES
        val right = minOf(map.width - 1, left + CHUNK_TILES - 1)
        val bottom = minOf(map.height - 1, top + CHUNK_TILES - 1)
        var hash = -3750763034362895579L
        for (ty in top..bottom) {
            for (tx in left..right) {
                hash = (hash xor map.tileAt(tx, ty).ordinal.toLong()) * 1099511628211L
            }
        }
        return hash
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
        // Charred wrecks stay on the battlefield under the living tanks.
        for (tank in frame.tanks) {
            if (tank.alive) continue
            hudPaint.color = 0x33FF7A2A
            canvas.drawCircle(tank.x, tank.y, 22f, hudPaint)
            hudPaint.color = 0x55000000
            canvas.drawCircle(tank.x, tank.y, 16f, hudPaint)
            tankSpritePaint.alpha = 255
            canvas.save()
            canvas.translate(tank.x, tank.y)
            canvas.rotate(Math.toDegrees(tank.bodyDir.toDouble()).toFloat())
            spriteRect.set(-16f, -16f, 16f, 16f)
            canvas.drawBitmap(SpriteAtlas.wreck(), null, spriteRect, tankSpritePaint)
            canvas.restore()
        }
        for (tank in frame.tanks) {
            if (tank.alive) drawTank(canvas, frame, tank, now)
        }
    }

    private fun drawTank(canvas: Canvas, frame: GameFrame, tank: RenderTank, now: Long) {
        val isMe = tank.slot == frame.mySlot
        val color = when {
            frame.rules.teams -> TEAM_COLORS[((tank.team % TEAM_COLORS.size) + TEAM_COLORS.size) % TEAM_COLORS.size]
            isMe -> COLOR_ME
            else -> PLAYER_COLORS[((tank.slot % PLAYER_COLORS.size) + PLAYER_COLORS.size) % PLAYER_COLORS.size]
        }

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

        val turretDir = if (isMe && now < shotVisualUntil && !shotVisualAngle.isNaN()) {
            shotVisualAngle
        } else {
            tank.turretDir
        }
        canvas.save()
        canvas.translate(tank.x, tank.y)
        canvas.rotate(Math.toDegrees(turretDir.toDouble()).toFloat())
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
            val label = if (frame.rules.lives > 1) "${tank.name} ×${tank.lives}" else tank.name
            canvas.drawText(label, tank.x, tank.y - 19f, textPaint)
            val pipW = 7f
            for (i in 0 until GameConfig.MAX_HP) {
                hudPaint.color = if (i < tank.hp) 0xFFE05555.toInt() else 0x55333333
                val px = tank.x - pipW * 1.5f + i * (pipW + 1f)
                canvas.drawRect(px, tank.y - 17f, px + pipW, tank.y - 14f, hudPaint)
            }
        }
    }

    /**
     * An enemy that just left the headlight stays fully drawn for a couple of seconds,
     * instead of turning into a red "ghost" marker.
     */
    private fun drawRevealedTanks(canvas: Canvas, frame: GameFrame, now: Long) {
        val me = frame.me ?: return
        if (!me.alive) return
        for (tank in frame.tanks) {
            if (!tank.alive || tank.slot == frame.mySlot) continue
            if (tank.slot in visibleNow.indices && visibleNow[tank.slot]) continue
            val seen = lastSeen[tank.slot] ?: continue
            if (now - seen.at > REVEAL_MS) continue
            drawTank(canvas, frame, tank, now)
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

    private fun drawGrenades(canvas: Canvas, frame: GameFrame, now: Long) {
        for (grenade in frame.grenades) {
            val lift = if (grenade.landed) 0f else 12f
            hudPaint.color = 0x55000000
            canvas.drawCircle(grenade.x, grenade.y + 3f, if (grenade.landed) 9f else 6f, hudPaint)
            spriteRect.set(
                grenade.x - 10f, grenade.y - 10f - lift,
                grenade.x + 10f, grenade.y + 10f - lift,
            )
            canvas.drawBitmap(SpriteAtlas.grenade(), null, spriteRect, spritePaint)
            if (grenade.landed) {
                val pulse = sin(now / 100f) * 0.5f + 0.5f
                bulletPaint.color = ((50 + 100 * pulse).toInt() shl 24) or 0x00FF5030
                canvas.drawCircle(grenade.x, grenade.y, 13f + 4f * pulse, bulletPaint)
            }
        }
    }

    private fun drawFog(canvas: Canvas, frame: GameFrame, me: RenderTank?, now: Long) {
        // Extra margin covers camera shake so no unfogged strip appears at the screen edge.
        val margin = 40f
        val left = camera.worldLeft - margin
        val top = camera.worldTop - margin
        val right = camera.worldRight + margin
        val bottom = camera.worldBottom + margin

        if (me != null && me.alive) {
            if (me.lightsOn) {
                if (!lightCacheValid ||
                    lightCacheMap !== frame.map ||
                    lightCacheX != me.x ||
                    lightCacheY != me.y ||
                    lightCacheDir != me.turretDir ||
                    lightCacheBoost != me.lightBoost ||
                    lightCacheRevision != frame.map.revision
                ) {
                    buildOwnLight(frame, me)
                    lightCacheValid = true
                    lightCacheMap = frame.map
                    lightCacheX = me.x
                    lightCacheY = me.y
                    lightCacheDir = me.turretDir
                    lightCacheBoost = me.lightBoost
                    lightCacheRevision = frame.map.revision
                }
                val range = ownLightRange(me)
                // Glow is drawn under the fog: the clip hole reveals it, everything else is covered.
                glowPaint.shader = if (me.lightBoost) coneGlowBoost else coneGlow
                canvas.save()
                canvas.translate(me.x, me.y)
                canvas.drawCircle(0f, 0f, range, glowPaint)
                canvas.restore()
                glowPaint.shader = null
            }
        }

        canvas.save()
        if (me != null) {
            if (me.alive) {
                if (me.lightsOn) canvas.clipOutPath(lightPath)
                selfHole.reset()
                selfHole.addCircle(me.x, me.y, GameConfig.LIGHT_SELF_RADIUS, Path.Direction.CW)
                canvas.clipOutPath(selfHole)
            } else {
                selfHole.reset()
                selfHole.addCircle(me.x, me.y, 190f, Path.Direction.CW)
                canvas.clipOutPath(selfHole)
            }
        }
        canvas.drawRect(left, top, right, bottom, fogPaint)
        canvas.restore()

        if (me != null && me.alive) {
            glowPaint.shader = haloGlow
            canvas.save()
            canvas.translate(me.x, me.y)
            canvas.drawCircle(0f, 0f, 85f, glowPaint)
            canvas.restore()
            glowPaint.shader = null
            if (me.lightsOn) {
                for (i in 0 until glintCount) {
                    bulletPaint.color = 0x33FFE0A0
                    canvas.drawCircle(glintX[i], glintY[i], 3.5f, bulletPaint)
                }
            }
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
            val c = cos(angle)
            val s = sin(angle)
            val hit = map.raycastLight(me.x, me.y, c, s, range)
            val blocked = hit < range
            val distance = if (blocked) (hit - 4f).coerceAtLeast(0f) else range
            val hitX = me.x + c * distance
            val hitY = me.y + s * distance
            if (blocked && glintCount < glintX.size) {
                glintX[glintCount] = hitX
                glintY[glintCount] = hitY
                glintCount++
            }
            lightPath.lineTo(hitX, hitY)
        }
        lightPath.close()
    }

    private fun drawEnemyBeams(canvas: Canvas, frame: GameFrame) {
        val me = frame.me
        for (tank in frame.tanks) {
            if (!tank.alive || tank.slot == frame.mySlot || !tank.lightsOn) continue
            if (isAlly(frame, me, tank)) continue
            if (tank.x < camera.worldLeft - 400f || tank.x > camera.worldRight + 400f) continue
            if (tank.y < camera.worldTop - 400f || tank.y > camera.worldBottom + 400f) continue
            val range = if (tank.lightBoost) {
                GameConfig.LIGHT_RANGE * GameConfig.LIGHT_RANGE_BOOST * 0.9f
            } else {
                GameConfig.LIGHT_RANGE * 0.9f
            }
            val half = if (tank.lightBoost) {
                GameConfig.LIGHT_HALF_ANGLE * GameConfig.LIGHT_HALF_ANGLE_BOOST
            } else {
                GameConfig.LIGHT_HALF_ANGLE
            }
            buildCone(enemyPath, frame, tank, range, half)
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
            val c = cos(angle)
            val s = sin(angle)
            val hit = frame.map.raycastLight(tank.x, tank.y, c, s, range)
            val distance = if (hit < range) (hit - 3f).coerceAtLeast(0f) else range
            path.lineTo(tank.x + c * distance, tank.y + s * distance)
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
            if (age > 1300L) continue
            val alpha = (1f - age / 1300f).coerceIn(0f, 1f)
            bulletPaint.color = ((alpha * 120).toInt() shl 24) or 0x00FF3B3B
            canvas.drawCircle(blip.x, blip.y, 13f, bulletPaint)
            bulletPaint.color = ((alpha * 255).toInt() shl 24) or 0x00FF5252
            canvas.drawCircle(blip.x, blip.y, 5.5f, bulletPaint)
        }
    }

    private fun updateBlips(frame: GameFrame) {
        val now = SystemClock.elapsedRealtime()
        blips.removeAll { now - it.bornAt > 4200L }
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
            val me = frame.me
            for (tank in frame.tanks) {
                if (tank.slot == frame.mySlot || !tank.alive) continue
                if (isAlly(frame, me, tank)) continue
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

    private fun drawAimGuide(canvas: Canvas, frame: GameFrame, me: RenderTank?, now: Long) {
        if (me == null || !me.alive) return
        val angle = when {
            aimActive && hypot(aimX, aimY) >= GameConfig.AIM_DEADZONE ->
                kotlin.math.atan2(aimY, aimX)
            now < shotVisualUntil && !shotVisualAngle.isNaN() -> shotVisualAngle
            else -> me.turretDir
        }
        val range = ownLightRange(me)
        val ex = me.x + cos(angle) * range
        val ey = me.y + sin(angle) * range
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
        val hudRight = w - safeRight - 20f * density
        val hudTop = safeTop + 96f * density

        // HP pips, right aligned under the exit button.
        val pipW = 30f * density
        val pipH = 13f * density
        for (i in 0 until GameConfig.MAX_HP) {
            val left = hudRight - pipW - i * (pipW + 7f * density)
            hudPaint.color = if (i < (me?.hp ?: 0)) COLOR_HP else 0x33FF5252
            canvas.drawRoundRect(
                RectF(left, hudTop, left + pipW, hudTop + pipH),
                4f * density, 4f * density, hudPaint,
            )
        }
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = 13f * scaledDensity
        textPaint.color = COLOR_DIM
        val alive = frame.tanks.count { it.alive }
        val total = frame.tanks.size
        val livesText = if (me != null && frame.rules.lives > 1) "  ·  ЖИЗНИ ${me.lives}" else ""
        canvas.drawText("В ЖИВЫХ $alive / $total$livesText", hudRight, hudTop + 36f * density, textPaint)

        // Buff badges with the remaining fraction as a ring.
        if (me != null) {
            if (me.powered) {
                drawBadge(canvas, hudRight - 18f * density, hudTop + 52f * density, "P", 0xFFFF9A3C.toInt(), me.powerLeft)
            }
            if (me.lightBoost) {
                drawBadge(canvas, hudRight - 42f * density, hudTop + 52f * density, "L", 0xFFFFE27A.toInt(), me.lightLeft)
            }
            if (!me.lightsOn) drawBadge(canvas, hudRight - 66f * density, hudTop + 52f * density, "•", 0x66FFFFFF)
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
        hudPaint.color = if (aimActive) 0x8857D68B.toInt() else 0x5557D68B
        canvas.drawCircle(aimKnobXDraw, aimKnobYDraw, aimKnobRadius, hudPaint)

        // Grenade button.
        val mx = grenadeButtonX()
        val my = grenadeButtonY()
        hudPaint.color = 0x22000000
        canvas.drawCircle(mx, my, grenadeRadius, hudPaint)
        strokePaint.color = 0x55B0B0B0
        strokePaint.strokeWidth = 1.5f * density
        canvas.drawCircle(mx, my, grenadeRadius, strokePaint)
        spriteRect.set(mx - 18f * density, my - 18f * density, mx + 18f * density, my + 18f * density)
        canvas.drawBitmap(SpriteAtlas.grenade(), null, spriteRect, spritePaint)
        val grenadesLeft = me?.grenades ?: 0
        if (me != null && grenadesLeft < GameConfig.GRENADE_CHARGES) {
            arcPaint.strokeWidth = 4f * density
            arcPaint.color = 0xAA57D68B.toInt()
            arcRect.set(
                mx - grenadeRadius - 8f * density, my - grenadeRadius - 8f * density,
                mx + grenadeRadius + 8f * density, my + grenadeRadius + 8f * density,
            )
            canvas.drawArc(arcRect, -90f, 360f * me.grenadeRecharge, false, arcPaint)
        }
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 12f * scaledDensity
        textPaint.color = if ((me?.grenades ?: 0) > 0) COLOR_TEXT else 0x55FFFFFF
        canvas.drawText("${me?.grenades ?: 0}", mx + grenadeRadius * 0.8f, my + grenadeRadius * 0.9f, textPaint)
        textPaint.textSize = 9f * scaledDensity
        textPaint.color = 0x99FFFFFF.toInt()
        canvas.drawText("ГРАНАТА", mx, my + grenadeRadius + 14f * density, textPaint)

        // Headlight toggle.
        val lx = lightButtonX()
        val ly = lightButtonY()
        hudPaint.color = if (lightsOn) 0x33FFE27A else 0x22000000
        canvas.drawCircle(lx, ly, lightRadius, hudPaint)
        strokePaint.color = if (lightsOn) 0xAAFFE27A.toInt() else 0x55FFFFFF
        strokePaint.strokeWidth = 1.5f * density
        canvas.drawCircle(lx, ly, lightRadius, strokePaint)
        val bulbColor = if (lightsOn) 0xFFFFE27A.toInt() else 0x88FFFFFF.toInt()
        hudPaint.color = bulbColor
        canvas.drawCircle(lx, ly - 3f * density, 6f * density, hudPaint)
        canvas.drawRoundRect(
            RectF(lx - 4f * density, ly + 2f * density, lx + 4f * density, ly + 7f * density),
            1.5f * density, 1.5f * density, hudPaint,
        )
        canvas.drawRect(lx - 2f * density, ly + 7f * density, lx + 2f * density, ly + 9f * density, hudPaint)
        if (lightsOn) {
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = 1.3f * density
            strokePaint.color = 0x99FFE27A.toInt()
            for (i in 0 until 4) {
                val a = -2.35f + i * 0.55f
                canvas.drawLine(
                    lx + cos(a) * 10f * density, ly - 3f * density + sin(a) * 10f * density,
                    lx + cos(a) * 15f * density, ly - 3f * density + sin(a) * 15f * density,
                    strokePaint,
                )
            }
        }

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
            if (frame.rules.lives > 1 && me.lives > 0) {
                drawCenteredText(canvas, "ВОЗРОЖДЕНИЕ", h / 2f, 22f, 0xCCFFE27A.toInt())
                drawCenteredText(canvas, "жизней осталось: ${me.lives}", h / 2f + 30f * density, 13f, COLOR_DIM)
            } else {
                drawCenteredText(canvas, "УНИЧТОЖЕН", h / 2f, 22f, 0xCCFF6B6B.toInt())
                drawCenteredText(canvas, "наблюдение", h / 2f + 30f * density, 13f, COLOR_DIM)
            }
        }

        if (now < hintUntil && !frame.roundOver) {
            val alpha = ((hintUntil - now) / 6500f).coerceIn(0f, 1f)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 12.5f * scaledDensity
            textPaint.color = ((alpha * 200).toInt() shl 24) or 0x00E6ECE9
            canvas.drawText(
                "правый стик: тап — автоогонь, потяни и отпусти — выстрел · граната и фара — справа",
                w / 2f, safeTop + 150f * density, textPaint,
            )
        }

        if (now < renderErrorUntil) {
            drawCenteredText(
                canvas,
                "перезапуск рендера…",
                safeTop + 182f * density,
                14f,
                0xCCFFB0A0.toInt(),
            )
        }

        drawFeed(canvas, w, now)
        drawMinimap(canvas, frame, now)

        if (frame.roundOver) {
            hudPaint.color = 0xB3000000.toInt()
            canvas.drawRect(0f, 0f, w, h, hudPaint)
            val iWon = when {
                frame.winnerTeam >= 0 -> me != null && me.team == frame.winnerTeam
                frame.winnerSlot >= 0 -> frame.winnerSlot == frame.mySlot
                else -> false
            }
            val title = when {
                frame.winnerName == null -> "НИЧЬЯ"
                iWon -> "ПОБЕДА!"
                else -> "ПОБЕДИЛ: ${frame.winnerName}"
            }
            drawCenteredText(canvas, title, h / 2f - 76f * density, 34f, COLOR_ME)

            if (frame.results.isNotEmpty()) {
                val names = frame.players.associate { it.slot to it.name }
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize = 12.5f * scaledDensity
                textPaint.color = 0xFFE6ECE9.toInt()
                val top = frame.results
                for ((index, result) in top.withIndex()) {
                    val name = names[result.slot]
                        ?: frame.tanks.firstOrNull { it.slot == result.slot }?.name
                        ?: "?"
                    val line = "$name   убийств: ${result.kills}   смертей: ${result.deaths}   урон: ${result.damage}"
                    canvas.drawText(line, w / 2f, h / 2f - 18f * density + index * 20f * density, textPaint)
                }
            }
            drawCenteredText(canvas, "возврат в лобби…", h / 2f + 128f * density, 15f, COLOR_DIM)
        }
    }

    private fun updateFeed(now: Long) {
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
            canvas.drawText(line.text, w - safeRight - 20f * density, safeTop + 162f * density + i * 18f * density, textPaint)
        }
    }

    /** Sonar overview: full map, camera rect, sonar ring and last-known enemy dots. */
    private fun drawMinimap(canvas: Canvas, frame: GameFrame, now: Long) {
        val map = frame.map
        val size = 112f * density
        val left = safeLeft + 10f * density
        val top = safeTop + 10f * density
        minimapLeft = left
        minimapTop = top
        minimapSize = size

        if (minimapMap !== map) {
            minimapMap = map
            minimapBitmap = Bitmap.createBitmap(map.width, map.height, Bitmap.Config.ARGB_8888)
            minimapTick = -1000L
        }
        if (frame.tick - minimapTick >= 30L) {
            val pixels = minimapPixels
                ?.takeIf { it.size == map.width * map.height }
                ?: IntArray(map.width * map.height).also { minimapPixels = it }
            for (ty in 0 until map.height) {
                for (tx in 0 until map.width) {
                    pixels[ty * map.width + tx] = when (map.tileAt(tx, ty)) {
                        Tile.EMPTY -> 0xFF0D1318.toInt()
                        Tile.BRICK -> 0xFF7A4027.toInt()
                        Tile.CRACKED -> 0xFF4B2A1B.toInt()
                        Tile.STEEL -> 0xFF4C5866.toInt()
                        Tile.WATER -> 0xFF1B4E6A.toInt()
                    }
                }
            }
            minimapBitmap?.setPixels(pixels, 0, map.width, 0, 0, map.width, map.height)
            minimapTick = frame.tick
        }

        hudPaint.color = 0xCC070B10.toInt()
        canvas.drawRoundRect(
            RectF(left - 4f * density, top - 4f * density, left + size + 4f * density, top + size + 4f * density),
            8f * density, 8f * density, hudPaint,
        )
        minimapBitmap?.let {
            canvas.drawBitmap(it, null, RectF(left, top, left + size, top + size), minimapPaint)
        }

        val scale = size / GameConfig.WORLD_SIZE
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 1f * density
        strokePaint.color = 0x33FFFFFF
        canvas.drawRect(
            left + camera.worldLeft * scale,
            top + camera.worldTop * scale,
            left + camera.worldRight * scale,
            top + camera.worldBottom * scale,
            strokePaint,
        )

        if (frame.sonarActive) {
            strokePaint.color = 0x9957D68B.toInt()
            strokePaint.strokeWidth = 1.5f * density
            canvas.drawCircle(left + size / 2f, top + size / 2f, frame.sonarRadius * scale, strokePaint)

            // Allies answer the sonar pulse as green dots.
            val meTank = frame.me
            if (frame.rules.teams && meTank != null) {
                for (tank in frame.tanks) {
                    if (!tank.alive || tank.slot == frame.mySlot || tank.team != meTank.team) continue
                    bulletPaint.color = 0xCC57D68B.toInt()
                    canvas.drawCircle(
                        left + tank.x * scale,
                        top + tank.y * scale,
                        2.6f * density,
                        bulletPaint,
                    )
                }
            }
        }

        for (blip in blips) {
            val age = now - blip.bornAt
            if (age > 4200L) continue
            val alpha = (1f - age / 4200f).coerceIn(0f, 1f)
            bulletPaint.color = ((alpha * 220).toInt() shl 24) or 0x00FF4040
            canvas.drawCircle(left + blip.x * scale, top + blip.y * scale, 2.4f * density, bulletPaint)
        }
        for (seen in lastSeen.values) {
            val age = now - seen.at
            if (age > GHOST_MS) continue
            val alpha = (1f - age / GHOST_MS.toFloat()).coerceIn(0f, 1f)
            bulletPaint.color = ((alpha * 170).toInt() shl 24) or 0x00FF8080
            canvas.drawCircle(left + seen.x * scale, top + seen.y * scale, 2.1f * density, bulletPaint)
        }

        val me = frame.me
        if (me != null) {
            bulletPaint.color = 0xFF57D68B.toInt()
            canvas.drawCircle(left + me.x * scale, top + me.y * scale, 3f * density, bulletPaint)
        }
    }

    private fun drawBadge(
        canvas: Canvas,
        x: Float,
        y: Float,
        label: String,
        color: Int,
        fraction: Float = 1f,
    ) {
        val cx = x + 9f * density
        hudPaint.color = (0x33 shl 24) or (color and 0x00FFFFFF)
        canvas.drawCircle(cx, y, 10f * density, hudPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 11f * scaledDensity
        textPaint.color = color
        canvas.drawText(label, cx, y + 4f * density, textPaint)
        if (fraction < 0.999f) {
            arcPaint.strokeWidth = 2.5f * density
            arcPaint.color = (0xAA shl 24) or (color and 0x00FFFFFF)
            arcRect.set(
                cx - 12.5f * density, y - 12.5f * density,
                cx + 12.5f * density, y + 12.5f * density,
            )
            canvas.drawArc(arcRect, -90f, 360f * fraction.coerceIn(0f, 1f), false, arcPaint)
        }
    }

    private fun drawCenteredText(canvas: Canvas, text: String, y: Float, sizeSp: Float, color: Int) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = sizeSp * scaledDensity
        textPaint.color = color
        canvas.drawText(text, canvas.width / 2f, y, textPaint)
    }

    private fun drawCountdown(canvas: Canvas, remainingMs: Long) {
        val number = ceil(remainingMs / 1000f).toInt().coerceIn(1, 3)
        hudPaint.color = 0x55000000
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), hudPaint)
        drawCenteredText(canvas, "ПРИГОТОВЬСЯ", canvas.height / 2f - 90f * density, 16f, COLOR_DIM)
        drawCenteredText(canvas, number.toString(), canvas.height / 2f + 30f * density, 64f, 0xFFFFE27A.toInt())
    }

    private data class Blip(val x: Float, val y: Float, val bornAt: Long)

    private data class FeedLine(val text: String, val at: Long)

    companion object {
        private val COLOR_VOID = 0xFF05070A.toInt()
        private val COLOR_GROUND = 0xFF1A221A.toInt()
        private val COLOR_ME = 0xFF57D68B.toInt()
        private val COLOR_DIM = 0xFF8A9A92.toInt()
        private val TEAM_COLORS = intArrayOf(0xFF57D68B.toInt(), 0xFFE05A5A.toInt())
        private val COLOR_TEXT = 0xFFE6ECE9.toInt()
        private val COLOR_HP = 0xFFFF5252.toInt()
        private const val GHOST_MS = 4000L
        private const val REVEAL_MS = 2_200L
        private const val CHUNK_TILES = 8
        private const val CHUNK_PX = CHUNK_TILES * 32
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
