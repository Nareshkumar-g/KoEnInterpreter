package com.nars.kointerpreter.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.IOException

/** Streams 20 ms frames of 16 kHz mono PCM from the microphone while collected. */
object AudioCapture {

    /** @param device the mic to record from; null lets Android choose. */
    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO before collecting
    fun frames(device: AudioDeviceInfo? = null): Flow<ShortArray> = flow {
        val minBuf = AudioRecord.getMinBufferSize(
            Vad.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            // VOICE_RECOGNITION: tuned for ASR, no aggressive call-style processing.
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            Vad.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, Vad.SAMPLE_RATE) // ~0.5 s buffer
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IOException("Microphone unavailable (is another app using it?)")
        }
        // Pick the mic the user chose (phone mic, USB receiver, Bluetooth...).
        if (device != null) recorder.setPreferredDevice(device)
        recorder.startRecording()
        try {
            while (currentCoroutineContext().isActive) {
                val frame = ShortArray(Vad.FRAME_SAMPLES)
                var off = 0
                while (off < frame.size) {
                    val n = recorder.read(frame, off, frame.size - off)
                    if (n < 0) throw IOException("Microphone read error $n")
                    off += n
                }
                emit(frame)
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }.flowOn(Dispatchers.IO)
}
