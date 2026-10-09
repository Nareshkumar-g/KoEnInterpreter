package com.nars.kointerpreter.audio

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Energy-based voice activity detector that turns a continuous stream of 20 ms
 * PCM frames (16 kHz mono) into utterance segments.
 *
 * - Tracks an adaptive noise floor so it works in quiet rooms and noisy factories.
 * - Keeps ~300 ms of pre-roll so the first syllable is never clipped.
 * - Ends an utterance after [silenceMs] of silence; for long monologues it cuts
 *   earlier on shorter pauses so translations keep flowing.
 * - Drops blips shorter than [minSpeechMs] (coughs, door slams).
 */
class Vad(
    /** Speech must be this many times louder than the noise floor. 1.5 = very sensitive, 5 = strict. */
    private val sensitivity: Float = 2.5f,
    private val silenceMs: Int = 700,
    private val minSpeechMs: Int = 400,
    private val softCutAfterMs: Int = 12_000,
    private val softCutSilenceMs: Int = 300,
    private val maxSegmentMs: Int = 25_000,
    private val preRollMs: Int = 300,
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000 // 320
        private const val MIN_ABS_THRESHOLD = 350f
        private const val START_FRAMES = 3 // 60 ms above threshold to trigger

        fun rms(frame: ShortArray): Float {
            var sum = 0.0
            for (s in frame) sum += s.toDouble() * s
            return sqrt(sum / frame.size).toFloat()
        }
    }

    private var noiseFloor = 200f
    private val preRoll = ArrayDeque<ShortArray>()
    private val preRollFrames = preRollMs / FRAME_MS

    private var inSpeech = false
    private var aboveCount = 0
    private val segment = ArrayList<ShortArray>()
    private var voicedFrames = 0
    private var silentRun = 0

    /** Last frame's loudness, 0..1 on a log scale, for a level meter. */
    var level: Float = 0f
        private set

    val isInSpeech: Boolean get() = inSpeech

    /** Feed one frame; returns a finished utterance when one ends, otherwise null. */
    fun process(frame: ShortArray): ShortArray? {
        val e = rms(frame)
        level = (kotlin.math.ln(1f + e) / kotlin.math.ln(1f + 8000f)).coerceIn(0f, 1f)
        val threshold = max(noiseFloor * sensitivity, MIN_ABS_THRESHOLD)
        val loud = e > threshold

        if (!inSpeech) {
            // Adapt noise floor only while nobody is talking.
            noiseFloor = if (e < noiseFloor) 0.9f * noiseFloor + 0.1f * e else 0.98f * noiseFloor + 0.02f * e
            noiseFloor = noiseFloor.coerceIn(50f, 6000f)

            preRoll.addLast(frame)
            if (preRoll.size > preRollFrames) preRoll.removeFirst()

            aboveCount = if (loud) aboveCount + 1 else 0
            if (aboveCount >= START_FRAMES) {
                inSpeech = true
                segment.clear()
                segment.addAll(preRoll)
                preRoll.clear()
                voicedFrames = aboveCount
                silentRun = 0
            }
            return null
        }

        segment.add(frame)
        if (loud) {
            voicedFrames++
            silentRun = 0
        } else {
            silentRun++
        }

        val segMs = segment.size * FRAME_MS
        val silentMs = silentRun * FRAME_MS
        val shouldEnd = silentMs >= silenceMs ||
            (segMs >= softCutAfterMs && silentMs >= softCutSilenceMs) ||
            segMs >= maxSegmentMs
        return if (shouldEnd) finish() else null
    }

    /** Force-close whatever is being recorded (e.g. when the user presses Stop). */
    fun flush(): ShortArray? = if (inSpeech) finish() else null

    private fun finish(): ShortArray? {
        inSpeech = false
        aboveCount = 0
        // Trim most of the trailing silence but keep ~200 ms of tail.
        val keepTail = 10
        val trimmed = max(0, silentRun - keepTail)
        val frames = segment.subList(0, segment.size - trimmed)
        val ok = voicedFrames * FRAME_MS >= minSpeechMs
        val out = if (ok) concat(frames) else null
        segment.clear()
        voicedFrames = 0
        silentRun = 0
        return out
    }

    private fun concat(frames: List<ShortArray>): ShortArray {
        val out = ShortArray(frames.sumOf { it.size })
        var off = 0
        for (f in frames) {
            f.copyInto(out, off)
            off += f.size
        }
        return out
    }
}
