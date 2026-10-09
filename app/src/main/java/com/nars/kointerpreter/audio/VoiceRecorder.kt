package com.nars.kointerpreter.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Records the microphone to a WAV file and, at the same time, streams the same audio
 * to the speech recognizer through a pipe ([RecognizerIntent.EXTRA_AUDIO_SOURCE],
 * Android 13+). Android's recognizer normally takes the microphone for itself, so this
 * is how push-to-talk can both translate and keep a recording.
 *
 * Audio: 16 kHz, mono, 16-bit PCM (what the recognizer expects).
 */
class VoiceRecorder(private val file: File, private val device: AudioDeviceInfo?) {

    /** Loudness 0..1 for the voice wave, called on the recording thread. */
    var onLevel: (Float) -> Unit = {}

    @Volatile private var running = false
    private var thread: Thread? = null
    private var bytesWritten = 0L

    /** Pipe to the recognizer for the current recognition session (one per session). */
    private var sink: ParcelFileDescriptor.AutoCloseOutputStream? = null
    private val lock = Any()
    // Writes to the recognizer happen off the recording thread, so a slow reader never drops recorded audio.
    private val pipeWriter = Executors.newSingleThreadExecutor()

    val durationMs: Long get() = bytesWritten * 1000 / (SAMPLE_RATE * 2)

    @SuppressLint("MissingPermission") // the caller checks RECORD_AUDIO before starting
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME_BYTES * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("Couldn't open the microphone for recording.")
        }
        device?.let { record.preferredDevice = it }
        val out = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) } // header written at the end
        running = true
        record.startRecording()
        thread = Thread({
            val frame = ByteArray(FRAME_BYTES)
            try {
                while (running) {
                    val n = record.read(frame, 0, frame.size)
                    if (n <= 0) continue
                    out.write(frame, 0, n)
                    bytesWritten += n
                    onLevel(level(frame, n))
                    val chunk = frame.copyOf(n)
                    pipeWriter.execute { writeToRecognizer(chunk) }
                }
            } finally {
                record.stop()
                record.release()
                writeWavHeader(out, bytesWritten)
                out.close()
            }
        }, "VoiceRecorder").apply { start() }
    }

    /** Opens a fresh pipe for a new recognition session and returns the end the recognizer reads. */
    fun newRecognizerInput(): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createPipe()
        val previous = synchronized(lock) { sink.also { sink = ParcelFileDescriptor.AutoCloseOutputStream(write) } }
        pipeWriter.execute { runCatching { previous?.close() } }
        return read
    }

    /** Ends the audio for the current recognition session, so the recognizer finishes it. */
    fun endRecognizerInput() {
        val previous = synchronized(lock) { sink.also { sink = null } }
        pipeWriter.execute { runCatching { previous?.close() } }
    }

    private fun writeToRecognizer(chunk: ByteArray) {
        val s = synchronized(lock) { sink } ?: return
        try {
            s.write(chunk)
        } catch (e: IOException) {
            // The recognizer closed its end (its session ended): stop feeding this pipe.
            synchronized(lock) { if (sink === s) sink = null }
            runCatching { s.close() }
        }
    }

    /** Stops recording and finishes the WAV file. Returns the file, or null if nothing was recorded. */
    fun stop(): File? {
        running = false
        thread?.join(2000)
        endRecognizerInput()
        pipeWriter.shutdown()
        return file.takeIf { bytesWritten > 0 }
    }

    /** Same loudness scale as the recognizer's own level: quiet room ≈ 0, clear speech ≈ 1. */
    private fun level(buf: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toDouble()
            sum += s * s
            i += 2
        }
        val rms = sqrt(sum / (n / 2).coerceAtLeast(1))
        val raw = (ln(1.0 + rms) / ln(8001.0)).toFloat()
        return ((raw - 0.5f) / 0.45f).coerceIn(0f, 1f)
    }

    private fun writeWavHeader(out: RandomAccessFile, dataBytes: Long) {
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes.toInt())
        }
        out.seek(0)
        out.write(header.array())
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 20 ms of audio. */
        private const val FRAME_BYTES = SAMPLE_RATE / 50 * 2
    }
}
