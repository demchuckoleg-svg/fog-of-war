package com.fogofwar.game.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.fogofwar.game.App
import com.fogofwar.game.core.GameConfig
import com.fogofwar.game.core.Tile
import com.fogofwar.game.net.GameFrame
import com.fogofwar.game.net.NearbyNet
import com.fogofwar.game.net.RenderTank
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * SurfaceView renderer: world -> tanks -> fog with a raycast headlight cut-out ->
 * zone ring -> sonar blips -> HUD with a dynamic joystick and fire button.
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
    private val lightPath = Path()
    private val arrowPath = Path()

    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GROUND
        style = Paint.Style.FILL
    }
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }
    private val tankPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bulletPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fogPaint = Paint().apply { color = COLOR_FOG }
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private var renderThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var lastFrame: GameFrame? = null

    private var lastInputAt = 0L
    private var lastSonarRadius = 0f
    private val blips = ArrayList<Blip>()

    // Touch state is written by the UI thread and read by the render thread.
    @Volatile private var moveX = 0f
    @Volatile private var moveY = 0f
    @Volatile private var firing = false
    @Volatile private var joyActive = false
    @Volatile private var joyBaseX = 0f
    @Volatile private var joyBaseY = 0f
    @Volatile private var joyKnobX = 0f
    @Volatile private var joyKnobY = 0f
    private var joyPointerId = -1
    private var firePointerId = -1

    private var joyRadius = 0f
    private var joyKnobRadius = 0f
    private var fireRadius = 0f
    private var fireX = 0f
    private var fireY = 0f

    init {
        holder.addCallback(this)
        isFocusable = true
        keepScreenOn = true
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

    override fun surfaceCreated(holder: SurfaceHolder) {
        startThread()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        pauseRendering()
    }

    override fun run() {
        var lastInput = 0L
        while (running) {
            val frameStart = System.nanoTime()
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastInput >= 50L) {
                    lastInput = now
                    net.sendInput(moveX, moveY, firing)
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
                // A destroyed surface or a closing activity must not kill the app.
                if (running && t !is IllegalStateException) {
                    pauseRendering()
                }
            }
            val elapsedMs = (System.nanoTime() - frameStart) / 1_000_000L
            if (elapsedMs < 16L) SystemClock.sleep(16L - elapsedMs)
        }
    }

    // ------------------------------------------------------------------ input

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                handlePointerDown(event, event.actionIndex)
            }
            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                handlePointerUp(event.getPointerId(event.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> resetInput()
        }
        return true
    }

    private fun handlePointerDown(event: MotionEvent, index: Int) {
        val id = event.getPointerId(index)
        val x = event.getX(index)
        val y = event.getY(index)
        if (x < width / 2f && joyPointerId == -1) {
            joyPointerId = id
            joyActive = true
            joyBaseX = x
            joyBaseY = y
            joyKnobX = x
            joyKnobY = y
            moveX = 0f
            moveY = 0f
        } else if (x >= width / 2f && firePointerId == -1) {
            firePointerId = id
            firing = true
        }
    }

    private fun handleMove(event: MotionEvent) {
        if (joyPointerId < 0) return
        val index = event.findPointerIndex(joyPointerId)
        if (index < 0) return
        var dx = event.getX(index) - joyBaseX
        var dy = event.getY(index) - joyBaseY
        val len = hypot(dx, dy)
        val max = joyRadius
        if (len > max) {
            dx = dx / len * max
            dy = dy / len * max
        }
        joyKnobX = joyBaseX + dx
        joyKnobY = joyBaseY + dy
        val nx = dx / max
        val ny = dy / max
        if (hypot(nx, ny) < 0.14f) {
            moveX = 0f
            moveY = 0f
        } else {
            moveX = nx
            moveY = ny
        }
    }

    private fun handlePointerUp(id: Int) {
        if (id == joyPointerId) {
            joyPointerId = -1
            joyActive = false
            moveX = 0f
            moveY = 0f
        }
        if (id == firePointerId) {
            firePointerId = -1
            firing = false
        }
    }

    private fun resetInput() {
        joyPointerId = -1
        firePointerId = -1
        joyActive = false
        moveX = 0f
        moveY = 0f
        firing = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        joyRadius = 74f * density
        joyKnobRadius = 30f * density
        fireRadius = 58f * density
        fireX = w - 92f * density
        fireY = h - 92f * density
        joyBaseX = 104f * density
        joyBaseY = h - 104f * density
        joyKnobX = joyBaseX
        joyKnobY = joyBaseY
    }

    // ---------------------------------------------------------------- drawing

    private fun drawFrame(canvas: Canvas, frame: GameFrame?) {
        canvas.drawColor(COLOR_VOID)
        if (frame == null) {
            drawCenteredText(canvas, "СОЕДИНЕНИЕ…", canvas.height / 2f, 20f, COLOR_DIM)
            drawCenteredText(canvas, "ожидание состояния от хоста", canvas.height / 2f + 34f * density, 13f, COLOR_DIM)
            return
        }

        val me = frame.me
        val centerX = GameConfig.WORLD_SIZE / 2f
        camera.update(
            canvas.width.toFloat(),
            canvas.height.toFloat(),
            me?.x ?: centerX,
            me?.y ?: centerX,
        )

        canvas.save()
        canvas.translate(camera.offsetX, camera.offsetY)
        canvas.scale(camera.scale, camera.scale)

        drawWorld(canvas, frame)
        drawBullets(canvas, frame)
        drawTanks(canvas, frame)
        drawFog(canvas, frame, me)
        drawZone(canvas, frame)
        drawSonar(canvas, frame)
        drawBlips(canvas, frame)

        canvas.restore()

        drawHud(canvas, frame)
    }

    private fun drawWorld(canvas: Canvas, frame: GameFrame) {
        val map = frame.map
        val left = camera.worldLeft.coerceAtLeast(0f)
        val top = camera.worldTop.coerceAtLeast(0f)
        val right = camera.worldRight.coerceAtMost(GameConfig.WORLD_SIZE)
        val bottom = camera.worldBottom.coerceAtMost(GameConfig.WORLD_SIZE)
        canvas.drawRect(left, top, right, bottom, groundPaint)

        val tx0 = floor(left / GameConfig.TILE).toInt().coerceIn(0, map.width - 1)
        val ty0 = floor(top / GameConfig.TILE).toInt().coerceIn(0, map.height - 1)
        val tx1 = floor(right / GameConfig.TILE).toInt().coerceIn(0, map.width - 1)
        val ty1 = floor(bottom / GameConfig.TILE).toInt().coerceIn(0, map.height - 1)

        for (ty in ty0..ty1) {
            for (tx in tx0..tx1) {
                val tile = map.tileAt(tx, ty)
                if (tile == Tile.EMPTY) continue
                val x = tx * GameConfig.TILE
                val y = ty * GameConfig.TILE
                when (tile) {
                    Tile.BRICK -> {
                        tilePaint.color = COLOR_BRICK
                        canvas.drawRect(x, y, x + GameConfig.TILE, y + GameConfig.TILE, tilePaint)
                        detailPaint.color = COLOR_BRICK_DARK
                        detailPaint.strokeWidth = 2.4f
                        canvas.drawLine(x, y + 10f, x + GameConfig.TILE, y + 10f, detailPaint)
                        canvas.drawLine(x, y + 21f, x + GameConfig.TILE, y + 21f, detailPaint)
                        canvas.drawLine(x + 16f, y, x + 16f, y + 10f, detailPaint)
                        canvas.drawLine(x + 8f, y + 10f, x + 8f, y + 21f, detailPaint)
                        canvas.drawLine(x + 24f, y + 21f, x + 24f, y + GameConfig.TILE, detailPaint)
                    }
                    Tile.CRACKED -> {
                        tilePaint.color = COLOR_CRACKED
                        canvas.drawRect(x, y, x + GameConfig.TILE, y + GameConfig.TILE, tilePaint)
                        detailPaint.color = COLOR_CRACKED_LINE
                        detailPaint.strokeWidth = 2.2f
                        canvas.drawLine(x + 5f, y + 5f, x + 27f, y + 27f, detailPaint)
                        canvas.drawLine(x + 27f, y + 5f, x + 5f, y + 27f, detailPaint)
                    }
                    Tile.STEEL -> {
                        tilePaint.color = COLOR_STEEL
                        canvas.drawRect(x, y, x + GameConfig.TILE, y + GameConfig.TILE, tilePaint)
                        tilePaint.color = COLOR_STEEL_HIGHLIGHT
                        canvas.drawRect(x + 5f, y + 5f, x + 27f, y + 27f, tilePaint)
                    }
                    Tile.WATER -> {
                        tilePaint.color = COLOR_WATER
                        canvas.drawRect(x, y, x + GameConfig.TILE, y + GameConfig.TILE, tilePaint)
                        detailPaint.color = COLOR_WATER_LINE
                        detailPaint.strokeWidth = 2f
                        canvas.drawLine(x + 6f, y + 13f, x + 20f, y + 13f, detailPaint)
                        canvas.drawLine(x + 14f, y + 22f, x + 28f, y + 22f, detailPaint)
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun drawTanks(canvas: Canvas, frame: GameFrame) {
        for (tank in frame.tanks) {
            if (!tank.alive) continue
            val isMe = tank.slot == frame.mySlot
            val color = if (isMe) COLOR_ME else TANK_COLORS[tank.slot % TANK_COLORS.size]
            canvas.save()
            canvas.translate(tank.x, tank.y)
            canvas.rotate(Math.toDegrees(tank.dir.toDouble()).toFloat())

            tankPaint.color = 0xFF0B0F12.toInt()
            canvas.drawRoundRect(-11f, -14.5f, 11f, 14.5f, 4f, 4f, tankPaint)

            tankPaint.color = darken(color, 0.55f)
            canvas.drawRoundRect(-11.5f, -14f, -5.5f, 14f, 2.5f, 2.5f, tankPaint)
            canvas.drawRoundRect(5.5f, -14f, 11.5f, 14f, 2.5f, 2.5f, tankPaint)

            tankPaint.color = color
            canvas.drawRoundRect(-8f, -11f, 8f, 11f, 4f, 4f, tankPaint)

            tankPaint.color = darken(color, 1.35f)
            canvas.drawCircle(0f, 0f, 5.2f, tankPaint)

            tankPaint.color = darken(color, 1.55f)
            canvas.drawRoundRect(3f, -2.6f, 20f, 2.6f, 1.6f, 1.6f, tankPaint)

            if (isMe) {
                strokePaint.color = 0x66FFFFFF
                strokePaint.strokeWidth = 1.6f
                canvas.drawRoundRect(-12f, -15f, 12f, 15f, 5f, 5f, strokePaint)
            }
            canvas.restore()
        }
    }

    private fun drawBullets(canvas: Canvas, frame: GameFrame) {
        for (bullet in frame.bullets) {
            bulletPaint.color = 0x55FFD75E
            canvas.drawCircle(bullet.x, bullet.y, 8.5f, bulletPaint)
            bulletPaint.color = 0xFFFFE27A.toInt()
            canvas.drawCircle(bullet.x, bullet.y, 3.4f, bulletPaint)
        }
    }

    private fun drawFog(canvas: Canvas, frame: GameFrame, me: RenderTank?) {
        val left = camera.worldLeft
        val top = camera.worldTop
        val right = camera.worldRight
        val bottom = camera.worldBottom
        val layer = canvas.saveLayer(left, top, right, bottom, null)
        canvas.drawRect(left, top, right, bottom, fogPaint)
        if (me != null) {
            if (me.alive) {
                buildLightPath(frame, me)
                canvas.drawPath(lightPath, clearPaint)
                canvas.drawCircle(me.x, me.y, GameConfig.LIGHT_SELF_RADIUS, clearPaint)
            } else {
                // Spectators keep a small circle around their wreck.
                canvas.drawCircle(me.x, me.y, 190f, clearPaint)
            }
        }
        canvas.restoreToCount(layer)
    }

    private fun buildLightPath(frame: GameFrame, me: RenderTank) {
        lightPath.reset()
        lightPath.moveTo(me.x, me.y)
        val map = frame.map
        val start = me.dir - GameConfig.LIGHT_HALF_ANGLE
        val span = GameConfig.LIGHT_HALF_ANGLE * 2f
        val rays = GameConfig.LIGHT_RAYS
        val lastIndex = (rays - 1).toFloat()
        for (i in 0 until rays) {
            val angle = start + span * (i / lastIndex)
            val dx = cos(angle)
            val dy = sin(angle)
            var t = 5f
            var hitX = me.x + dx * GameConfig.LIGHT_RANGE
            var hitY = me.y + dy * GameConfig.LIGHT_RANGE
            while (t < GameConfig.LIGHT_RANGE) {
                val x = me.x + dx * t
                val y = me.y + dy * t
                if (map.blocksLightAtWorld(x, y)) {
                    hitX = x - dx * 4f
                    hitY = y - dy * 4f
                    break
                }
                t += 5f
            }
            lightPath.lineTo(hitX, hitY)
        }
        lightPath.close()
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

    private fun drawBlips(canvas: Canvas, frame: GameFrame) {
        val now = SystemClock.elapsedRealtime()
        for (blip in blips) {
            if (blip.x < camera.worldLeft || blip.x > camera.worldRight) continue
            if (blip.y < camera.worldTop || blip.y > camera.worldBottom) continue
            val age = (now - blip.bornAt).coerceAtLeast(0L)
            val alpha = (1f - age / 1300f).coerceIn(0f, 1f)
            if (alpha <= 0f) continue
            val glow = (alpha * 120).toInt()
            bulletPaint.color = (glow shl 24) or 0x00FF3B3B
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
                val dist = hypot(tank.x - center, tank.y - center)
                if (dist > lastSonarRadius && dist <= radius) {
                    blips.add(Blip(tank.x, tank.y, now))
                }
            }
            lastSonarRadius = radius
        }
    }

    // -------------------------------------------------------------------- HUD

    private fun drawHud(canvas: Canvas, frame: GameFrame) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val me = frame.me

        // HP pips.
        val pipW = 30f * density
        val pipH = 13f * density
        for (i in 0 until GameConfig.MAX_HP) {
            val left = 20f * density + i * (pipW + 7f * density)
            hudPaint.color = if (i < (me?.hp ?: 0)) COLOR_HP else 0x33FF5252
            canvas.drawRoundRect(
                RectF(left, 20f * density, left + pipW, 20f * density + pipH),
                4f * density,
                4f * density,
                hudPaint,
            )
        }
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 13f * scaledDensity
        textPaint.color = COLOR_DIM
        val alive = frame.tanks.count { it.alive }
        val total = frame.tanks.size
        canvas.drawText("В ЖИВЫХ $alive / $total", 20f * density, 56f * density, textPaint)

        // Joystick.
        val baseX = if (joyActive) joyBaseX else joyBaseX
        val baseY = if (joyActive) joyBaseY else joyBaseY
        val knobX = if (joyActive) joyKnobX else baseX
        val knobY = if (joyActive) joyKnobY else baseY
        hudPaint.color = 0x22FFFFFF
        canvas.drawCircle(baseX, baseY, joyRadius, hudPaint)
        strokePaint.style = Paint.Style.STROKE
        strokePaint.color = 0x44FFFFFF
        strokePaint.strokeWidth = 2f * density
        canvas.drawCircle(baseX, baseY, joyRadius, strokePaint)
        hudPaint.color = if (joyActive) 0x77FFFFFF else 0x44FFFFFF
        canvas.drawCircle(knobX, knobY, joyKnobRadius, hudPaint)

        // Fire button.
        hudPaint.color = if (firing) 0x99FF5252 else 0x33FF5252
        canvas.drawCircle(fireX, fireY, fireRadius, hudPaint)
        strokePaint.color = if (firing) 0xFFFF8080.toInt() else 0x66FF8080
        strokePaint.strokeWidth = 2.5f * density
        canvas.drawCircle(fireX, fireY, fireRadius, strokePaint)
        hudPaint.color = if (firing) 0xFFFFFFFF.toInt() else 0xB3FFFFFF.toInt()
        canvas.drawCircle(fireX, fireY, fireRadius * 0.42f, hudPaint)

        // Arrow to the safe zone when outside.
        if (me != null && me.alive) {
            val dist = hypot(me.x - GameConfig.WORLD_SIZE / 2f, me.y - GameConfig.WORLD_SIZE / 2f)
            if (dist > frame.zoneRadius) {
                val angle = atan2(
                    GameConfig.WORLD_SIZE / 2f - me.y,
                    GameConfig.WORLD_SIZE / 2f - me.x,
                )
                canvas.save()
                canvas.translate(w / 2f, h - 34f * density)
                canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat() + 90f)
                arrowPath.reset()
                arrowPath.moveTo(0f, -16f * density)
                arrowPath.lineTo(12f * density, 12f * density)
                arrowPath.lineTo(-12f * density, 12f * density)
                arrowPath.close()
                hudPaint.color = 0xCCFF5252.toInt()
                canvas.drawPath(arrowPath, hudPaint)
                canvas.restore()
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize = 12f * scaledDensity
                textPaint.color = 0xCCFF8A8A.toInt()
                canvas.drawText("ВНЕ ЗОНЫ", w / 2f, h - 62f * density, textPaint)
            }
        }

        if (me != null && !me.alive && !frame.roundOver) {
            drawCenteredText(canvas, "УНИЧТОЖЕН", h / 2f, 22f, 0xCCFF6B6B.toInt())
            drawCenteredText(canvas, "наблюдение", h / 2f + 30f * density, 13f, COLOR_DIM)
        }

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

    private fun drawCenteredText(canvas: Canvas, text: String, y: Float, sizeSp: Float, color: Int) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = sizeSp * scaledDensity
        textPaint.color = color
        canvas.drawText(text, canvas.width / 2f, y, textPaint)
    }

    private fun darken(color: Int, factor: Float): Int {
        val r = (((color shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((color shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private data class Blip(val x: Float, val y: Float, val bornAt: Long)

    companion object {
        private val COLOR_VOID = 0xFF05070A.toInt()
        private val COLOR_GROUND = 0xFF10160F.toInt()
        private val COLOR_BRICK = 0xFF7A4027.toInt()
        private val COLOR_BRICK_DARK = 0xFF542B1A.toInt()
        private val COLOR_CRACKED = 0xFF4B2A1B.toInt()
        private val COLOR_CRACKED_LINE = 0xFF2B160D.toInt()
        private val COLOR_STEEL = 0xFF39424C.toInt()
        private val COLOR_STEEL_HIGHLIGHT = 0xFF4E5A66.toInt()
        private val COLOR_WATER = 0xFF123044.toInt()
        private val COLOR_WATER_LINE = 0xFF1F4C68.toInt()
        private val COLOR_FOG = 0xF8090C10.toInt()
        private val COLOR_ME = 0xFF57D68B.toInt()
        private val COLOR_DIM = 0xFF8A9A92.toInt()
        private val COLOR_HP = 0xFFFF5252.toInt()
        private val TANK_COLORS = intArrayOf(
            0xFF57D68B.toInt(),
            0xFFE05A5A.toInt(),
            0xFFE0A23F.toInt(),
            0xFFB06BE0.toInt(),
            0xFF43C6E8.toInt(),
            0xFFE86FB0.toInt(),
        )
    }
}
