package com.nars.kointerpreter.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * One layer of the voice wave. Change the colors here to restyle the whole animation.
 * [freq] = how many lobes, [speed] = how fast it flows (negative = other direction).
 */
private class WaveLayer(
    val color: Color,
    val freq: Float,
    val speed: Float,
    val amp: Float,
    val offset: Float,
)

private val Layers = listOf(
    WaveLayer(Color(0xFF7C4DFF), freq = 3.0f, speed = 1.9f, amp = 1.00f, offset = 0.0f), // violet
    WaveLayer(Color(0xFF00BFA5), freq = 4.2f, speed = -2.4f, amp = 0.85f, offset = 1.3f), // teal
    WaveLayer(Color(0xFFFF6E6E), freq = 2.4f, speed = 2.9f, amp = 0.72f, offset = 2.6f), // coral
    WaveLayer(Color(0xFF40C4FF), freq = 5.1f, speed = -1.6f, amp = 0.58f, offset = 4.0f), // sky
)

/** Tapers each wave to zero at both ends so it swells from the middle. */
private fun attenuation(t: Float): Float {
    val k = 2f
    return (k / (k + t.pow(4))).pow(k)
}

/**
 * Siri-style listening animation: overlapping translucent waves that flow
 * continuously, breathe gently in silence and swell with the speaker's voice.
 *
 * Performance: the time and loudness values are read only inside the draw lambda,
 * so each frame redraws this canvas without recomposing anything. The frame loop
 * stops completely once the wave has faded out.
 */
@Composable
fun VoiceWave(level: StateFlow<Float>, active: Boolean, modifier: Modifier = Modifier) {
    // Loudness 0..1 (already normalized by the engine): rises quickly with speech, falls back more slowly (less jitter).
    val loud = remember { Animatable(0f) }
    LaunchedEffect(level) {
        level.collect { raw ->
            val target = raw.coerceIn(0f, 1f)
            val ms = if (target > loud.value) 90 else 280
            loud.animateTo(target, tween(ms, easing = LinearEasing))
        }
    }

    // Grows in when listening starts, flattens back into a line when it stops.
    val presence by animateFloatAsState(
        if (active) 1f else 0f, tween(500, easing = FastOutSlowInEasing), label = "wavePresence",
    )

    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active) {
        var last = 0L
        while (active || presence > 0.001f) {
            withFrameNanos { now ->
                if (last != 0L) time += (now - last) / 1_000_000_000f
                last = now
            }
        }
    }

    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val path = remember { Path() }

    Canvas(modifier) {
        val w = size.width
        val mid = size.height / 2
        val p = presence

        // Resting line, which the waves grow out of.
        drawLine(
            lineColor.copy(alpha = 0.12f + 0.22f * (1f - p)),
            Offset(0f, mid), Offset(w, mid),
            strokeWidth = 1.dp.toPx(),
        )
        if (p < 0.01f || w <= 0f) return@Canvas

        val energy = (0.10f + 0.90f * loud.value) * p
        val maxAmp = mid * 0.92f
        val step = 3.dp.toPx()
        val t0 = time

        Layers.forEachIndexed { i, layer ->
            // Each layer drifts in size a little on its own so the wave never looks mechanical.
            val wobble = 0.75f + 0.25f * sin(t0 * (1.1f + i * 0.37f) + layer.offset)
            val amp = maxAmp * energy * layer.amp * wobble
            if (amp < 0.5f) return@forEachIndexed

            fun y(x: Float, sign: Float): Float {
                val t = x / w * 4f - 2f
                return mid + sign * amp * attenuation(t) *
                    abs(sin(layer.freq * t + t0 * layer.speed + layer.offset))
            }

            path.reset()
            path.moveTo(0f, mid)
            var x = 0f
            while (x <= w) { path.lineTo(x, y(x, -1f)); x += step }
            path.lineTo(w, mid)
            x = w
            while (x >= 0f) { path.lineTo(x, y(x, 1f)); x -= step }
            path.close()

            drawPath(
                path,
                layer.color.copy(alpha = if (dark) 0.6f else 0.42f),
                // Additive blending on dark backgrounds gives the glowing overlap.
                blendMode = if (dark) BlendMode.Plus else BlendMode.SrcOver,
            )
        }
    }
}
