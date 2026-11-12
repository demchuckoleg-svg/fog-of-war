package com.fogofwar.game.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Debug
import android.os.SystemClock
import java.util.Locale

/**
 * Small on-screen frame meter: fps, p95 frame time, per-section cost, GC/s and
 * network traffic. It is intentionally cheap and only samples once every 500 ms;
 * enable it by tapping the minimap five times.
 */
class PerfOverlay {

    @Volatile
    var enabled: Boolean = false

    /** world, tanks, fog, hud, total — milliseconds for the last frame. */
    val sections = FloatArray(5)

    private val frameNanos = LongArray(180)
    private var frameIndex = 0
    private var frameSamples = 0

    private var lastStatsAt = 0L
    private var avgMs = 0.0
    private var p95Ms = 0.0

    private var lastGcCount = -1L
    private var lastGcAt = 0L
    private var gcPerSecond = 0.0

    private var lastNetAt = 0L
    private var lastSent = 0L
    private var lastReceived = 0L
    private var sentPerSecond = 0.0
    private var receivedPerSecond = 0.0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.LEFT
        color = 0xFFB8F5D0.toInt()
    }
    private val background = Paint().apply { color = 0xB0000000.toInt() }

    fun frame(nanos: Long) {
        frameNanos[frameIndex] = nanos
        frameIndex = (frameIndex + 1) % frameNanos.size
        if (frameSamples < frameNanos.size) frameSamples++
    }

    fun draw(
        canvas: Canvas,
        x: Float,
        y: Float,
        textSize: Float,
        sentBytes: Long,
        receivedBytes: Long,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatsAt >= 500L) {
            lastStatsAt = now
            sampleFrames()
            sampleGc(now)
            sampleNetwork(now, sentBytes, receivedBytes)
        }

        paint.textSize = textSize
        val lineHeight = textSize * 1.25f
        val lines = arrayOf(
            String.format(
                Locale.US,
                "fps %.0f  avg %.1f  p95 %.1f ms",
                if (avgMs > 0.0) 1000.0 / avgMs else 0.0,
                avgMs,
                p95Ms,
            ),
            String.format(
                Locale.US,
                "world %.1f  tanks %.1f  fog %.1f ms",
                sections[0],
                sections[1],
                sections[2],
            ),
            String.format(Locale.US, "hud %.1f  total %.1f ms", sections[3], sections[4]),
            String.format(
                Locale.US,
                "gc %.1f/s  net %.1f/%.1f KB/s",
                gcPerSecond,
                sentPerSecond / 1024.0,
                receivedPerSecond / 1024.0,
            ),
        )
        var width = 0f
        for (line in lines) width = maxOf(width, paint.measureText(line))
        background.alpha = 176
        canvas.drawRect(x - 6f, y - 4f, x + width + 6f, y + lines.size * lineHeight + 4f, background)
        for ((index, line) in lines.withIndex()) {
            canvas.drawText(line, x, y + textSize + index * lineHeight, paint)
        }
    }

    private fun sampleFrames() {
        if (frameSamples <= 0) return
        var sum = 0L
        for (i in 0 until frameSamples) sum += frameNanos[i]
        avgMs = sum.toDouble() / frameSamples / 1_000_000.0

        val sorted = LongArray(frameSamples)
        for (i in 0 until frameSamples) sorted[i] = frameNanos[i]
        sorted.sort()
        val index = (frameSamples * 95 / 100).coerceIn(0, frameSamples - 1)
        p95Ms = sorted[index] / 1_000_000.0
    }

    private fun sampleGc(now: Long) {
        val count = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: -1L
        if (lastGcCount >= 0L && count >= lastGcCount && now > lastGcAt) {
            gcPerSecond = (count - lastGcCount) * 1000.0 / (now - lastGcAt)
        }
        lastGcCount = count
        lastGcAt = now
    }

    private fun sampleNetwork(now: Long, sentBytes: Long, receivedBytes: Long) {
        if (lastNetAt == 0L) {
            lastNetAt = now
            lastSent = sentBytes
            lastReceived = receivedBytes
            return
        }
        val elapsed = now - lastNetAt
        if (elapsed <= 0L) return
        sentPerSecond = (sentBytes - lastSent) * 1000.0 / elapsed
        receivedPerSecond = (receivedBytes - lastReceived) * 1000.0 / elapsed
        lastSent = sentBytes
        lastReceived = receivedBytes
        lastNetAt = now
    }
}
