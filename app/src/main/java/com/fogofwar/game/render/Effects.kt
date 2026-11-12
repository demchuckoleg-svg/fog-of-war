package com.fogofwar.game.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.fogofwar.game.core.EffectEvent
import com.fogofwar.game.core.EffectKind
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Short-lived cosmetic effects: tread marks, muzzle sparks, explosion flashes, smoke and the
 * gunshot echo rings that give a shooter away in the fog.
 *
 * Everything is expressed in world units. [GameView] already applies the camera transform, so
 * every drawing call here works directly in world space.
 *
 * Rendering happens in two passes:
 *  * [draw] – effects that belong to the world and are dimmed by the fog (treads, sparks, smoke);
 *  * [drawOverlay] – effects that must stay visible on top of the fog (shot rings, explosions,
 *    pickup sparkles).
 *
 * All methods are called from the single render thread, so no synchronization is required.
 * Particles are stored in one pooled list that is pruned in [update]; the paints and the scratch
 * rectangle are allocated once and reused, so drawing never allocates.
 */
class Effects {

    private val particles = ArrayList<Particle>(INITIAL_CAPACITY)

    /** Timestamp of the last [update]; freshly spawned particles are stamped with it. */
    private var now = 0L

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val scratch = RectF()

    /** Call once per rendered frame with `SystemClock.elapsedRealtime()`. */
    fun update(nowMs: Long) {
        now = nowMs
        var i = 0
        while (i < particles.size) {
            val p = particles[i]
            // Effects may be spawned before the first update of a frame; stamp them now.
            if (p.born == 0L) p.born = nowMs
            if (nowMs - p.born >= p.life) particles.removeAt(i) else i++
        }
    }

    /** Spawns the visual effect for a gameplay event. */
    fun onEvent(event: EffectEvent) {
        when (event.kind) {
            EffectKind.SHOT -> shotEcho(event.x, event.y)
            EffectKind.EXPLOSION -> explosion(event.x, event.y, event.radius)
            EffectKind.PICKUP -> pickup(event.x, event.y)
        }
    }

    /** Two short dark tread marks perpendicular to [bodyAngle] (radians). */
    fun addTread(x: Float, y: Float, bodyAngle: Float) {
        // Keep treads bounded independently: they are quiet but live the longest.
        var treads = 0
        var oldest = -1
        var i = 0
        while (i < particles.size) {
            if (particles[i] is Tread) {
                if (oldest < 0) oldest = i
                treads++
            }
            i++
        }
        if (treads >= MAX_TREADS && oldest >= 0) particles.removeAt(oldest)
        spawn(Tread(x, y, bodyAngle))
    }

    /** Muzzle sparks at the barrel tip; [angle] is the turret angle in radians. */
    fun addMuzzle(x: Float, y: Float, angle: Float) {
        val count = 5 + Random.nextInt(3)
        repeat(count) {
            val spread = angle + (Random.nextFloat() - 0.5f) * 0.5f
            val speed = 160f + Random.nextFloat() * 260f
            spawn(
                Spark(
                    x = x,
                    y = y,
                    vx = cos(spread) * speed,
                    vy = sin(spread) * speed,
                    rgb = if (Random.nextBoolean()) ORANGE else EMBER,
                    coreRgb = YELLOW,
                    size = 1.4f + Random.nextFloat() * 1.1f,
                    life = 120L + Random.nextInt(101).toLong(),
                    gravity = 0f,
                    square = false,
                    overlay = false,
                )
            )
        }
    }

    /** World-space effects that are dimmed by the fog. */
    fun draw(canvas: Canvas) {
        var i = 0
        while (i < particles.size) {
            val p = particles[i]
            if (!p.overlay) p.draw(canvas, now)
            i++
        }
    }

    /** Effects that must remain visible through the fog layer. */
    fun drawOverlay(canvas: Canvas) {
        var i = 0
        while (i < particles.size) {
            val p = particles[i]
            if (p.overlay) p.draw(canvas, now)
            i++
        }
    }

    fun clear() {
        particles.clear()
    }

    // --- event spawners ------------------------------------------------------------------

    /** Thin double ring expanding from the shot; this is what betrays a shooter in the dark. */
    private fun shotEcho(x: Float, y: Float) {
        spawn(Ring(x, y))
        // A few bright sparks so the shot also reads up close, where the ring is already wide.
        val count = 4 + Random.nextInt(3)
        repeat(count) {
            val a = Random.nextFloat() * TAU
            val speed = 40f + Random.nextFloat() * 90f
            spawn(
                Spark(
                    x = x,
                    y = y,
                    vx = cos(a) * speed,
                    vy = sin(a) * speed,
                    rgb = ORANGE,
                    coreRgb = WHITE_HOT,
                    size = 1.3f + Random.nextFloat() * 1.2f,
                    life = 140L + Random.nextInt(90).toLong(),
                    gravity = 0f,
                    square = false,
                    overlay = false,
                )
            )
        }
    }

    private fun explosion(x: Float, y: Float, radius: Float) {
        val blastRadius = if (radius > 1f) radius else DEFAULT_BLAST_RADIUS
        spawn(Flash(x, y, blastRadius))

        val sparks = 12 + Random.nextInt(7)
        repeat(sparks) {
            val a = Random.nextFloat() * TAU
            val speed = 90f + Random.nextFloat() * 230f
            spawn(
                Spark(
                    x = x,
                    y = y,
                    vx = cos(a) * speed,
                    vy = sin(a) * speed,
                    rgb = if (Random.nextBoolean()) ORANGE else EMBER,
                    coreRgb = if (Random.nextBoolean()) YELLOW else WHITE_HOT,
                    size = 2f + Random.nextFloat() * 2.2f,
                    life = 400L + Random.nextInt(301).toLong(),
                    gravity = 260f,
                    square = true,
                    overlay = false,
                )
            )
        }

        val puffs = 3 + Random.nextInt(3)
        repeat(puffs) {
            val a = Random.nextFloat() * TAU
            val dist = Random.nextFloat() * blastRadius * 0.35f
            spawn(
                Smoke(
                    x = x + cos(a) * dist,
                    y = y + sin(a) * dist,
                    vx = (Random.nextFloat() - 0.5f) * 22f,
                    vy = -10f - Random.nextFloat() * 28f,
                    r0 = 8f + Random.nextFloat() * 8f,
                    r1 = 26f + Random.nextFloat() * 16f,
                )
            )
        }
    }

    private fun pickup(x: Float, y: Float) {
        repeat(8) {
            val a = Random.nextFloat() * TAU
            val speed = 18f + Random.nextFloat() * 40f
            spawn(
                Spark(
                    x = x,
                    y = y,
                    vx = cos(a) * speed,
                    vy = sin(a) * speed - (50f + Random.nextFloat() * 90f),
                    rgb = PICKUP,
                    coreRgb = WHITE_HOT,
                    size = 1.2f + Random.nextFloat() * 0.8f,
                    life = 600L,
                    gravity = 90f,
                    square = false,
                    overlay = true,
                )
            )
        }
    }

    // --- particle plumbing ---------------------------------------------------------------

    private fun spawn(p: Particle) {
        p.born = now
        particles.add(p)
        while (particles.size > MAX_PARTICLES) particles.removeAt(0)
    }

    private fun withAlpha(rgb: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        return (a shl 24) or (rgb and 0x00FFFFFF)
    }

    private abstract class Particle(val life: Long, val overlay: Boolean) {
        var born = 0L

        fun age(now: Long): Float =
            ((now - born).toFloat() / life.toFloat()).coerceIn(0f, 1f)

        abstract fun draw(canvas: Canvas, now: Long)
    }

    /** Two soft dark patches left behind by a moving tank. */
    private inner class Tread(
        private val x: Float,
        private val y: Float,
        private val angle: Float,
    ) : Particle(1800L, false) {
        override fun draw(canvas: Canvas, now: Long) {
            val t = age(now)
            fillPaint.color = withAlpha(TREAD, 0.35f * (1f - t * t))

            canvas.save()
            canvas.rotate(angle * RAD_TO_DEG, x, y)
            scratch.set(x - 2.5f, y - 11.5f, x + 2.5f, y - 2.5f)
            canvas.drawRoundRect(scratch, 2.2f, 2.2f, fillPaint)
            scratch.set(x - 2.5f, y + 2.5f, x + 2.5f, y + 11.5f)
            canvas.drawRoundRect(scratch, 2.2f, 2.2f, fillPaint)
            canvas.restore()
        }
    }

    /** Generic bright particle: a large translucent halo plus a small hot core. */
    private inner class Spark(
        private val x: Float,
        private val y: Float,
        private val vx: Float,
        private val vy: Float,
        private val rgb: Int,
        private val coreRgb: Int,
        private val size: Float,
        life: Long,
        private val gravity: Float,
        private val square: Boolean,
        overlay: Boolean,
    ) : Particle(life, overlay) {
        override fun draw(canvas: Canvas, now: Long) {
            val t = age(now)
            val seconds = t * life / 1000f
            val px = x + vx * seconds
            val py = y + vy * seconds + 0.5f * gravity * seconds * seconds
            val fade = 1f - t

            if (square) {
                scratch.set(px - size * 1.7f, py - size * 1.7f, px + size * 1.7f, py + size * 1.7f)
                fillPaint.color = withAlpha(rgb, 0.30f * fade)
                canvas.drawRect(scratch, fillPaint)

                scratch.set(px - size * 0.75f, py - size * 0.75f, px + size * 0.75f, py + size * 0.75f)
                fillPaint.color = withAlpha(coreRgb, 0.95f * fade)
                canvas.drawRect(scratch, fillPaint)
            } else {
                fillPaint.color = withAlpha(rgb, 0.32f * fade)
                canvas.drawCircle(px, py, size * 2.1f, fillPaint)
                fillPaint.color = withAlpha(coreRgb, fade)
                canvas.drawCircle(px, py, size * 0.75f, fillPaint)
            }
        }
    }

    /** Slow dark puff that rises and dissolves after an explosion. */
    private inner class Smoke(
        private val x: Float,
        private val y: Float,
        private val vx: Float,
        private val vy: Float,
        private val r0: Float,
        private val r1: Float,
    ) : Particle(1200L, false) {
        override fun draw(canvas: Canvas, now: Long) {
            val t = age(now)
            val seconds = t * life / 1000f
            val px = x + vx * seconds
            val py = y + vy * seconds
            val radius = r0 + (r1 - r0) * t
            val fade = 1f - t

            fillPaint.color = withAlpha(SMOKE, 0.22f * fade)
            canvas.drawCircle(px, py, radius, fillPaint)
            fillPaint.color = withAlpha(SMOKE, 0.20f * fade)
            canvas.drawCircle(px, py, radius * 0.62f, fillPaint)
        }
    }

    /** Expanding explosion core, drawn above the fog. */
    private inner class Flash(
        private val x: Float,
        private val y: Float,
        private val maxRadius: Float,
    ) : Particle(260L, true) {
        override fun draw(canvas: Canvas, now: Long) {
            val t = age(now)
            val grow = 1f - (1f - t) * (1f - t)
            val radius = maxRadius * grow
            val fade = (1f - t) * (1f - t)

            fillPaint.color = withAlpha(ORANGE, 0.55f * fade)
            canvas.drawCircle(x, y, radius, fillPaint)
            fillPaint.color = withAlpha(YELLOW, 0.85f * fade)
            canvas.drawCircle(x, y, radius * 0.62f, fillPaint)
            fillPaint.color = withAlpha(WHITE_HOT, 0.95f * fade)
            canvas.drawCircle(x, y, radius * 0.28f, fillPaint)
        }
    }

    /** The shot "sound" ring: two concentric strokes, the inner one hotter. */
    private inner class Ring(
        private val x: Float,
        private val y: Float,
    ) : Particle(850L, true) {
        override fun draw(canvas: Canvas, now: Long) {
            val t = age(now)
            val grow = 1f - (1f - t) * (1f - t)
            val radius = 10f + 75f * grow
            val fade = 1f - t

            strokePaint.strokeWidth = 3.5f
            strokePaint.color = withAlpha(ORANGE, 0.45f * fade)
            canvas.drawCircle(x, y, radius, strokePaint)

            strokePaint.strokeWidth = 2.2f
            strokePaint.color = withAlpha(WHITE_HOT, 0.75f * fade)
            canvas.drawCircle(x, y, radius * 0.66f, strokePaint)
        }
    }

    private companion object {
        const val MAX_PARTICLES = 250
        const val MAX_TREADS = 120
        const val INITIAL_CAPACITY = 256
        const val DEFAULT_BLAST_RADIUS = 46f
        const val TAU = 6.2831855f
        const val RAD_TO_DEG = 57.29578f

        val ORANGE = Color.rgb(255, 138, 42)
        val EMBER = Color.rgb(255, 88, 24)
        val YELLOW = Color.rgb(255, 214, 92)
        val WHITE_HOT = Color.rgb(255, 247, 214)
        val PICKUP = Color.rgb(255, 236, 140)
        val SMOKE = Color.rgb(34, 34, 38)
        val TREAD = Color.rgb(18, 18, 20)
    }
}
