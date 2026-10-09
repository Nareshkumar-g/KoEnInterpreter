package com.nars.kointerpreter.meeting

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Records 16 kHz mono PCM frames into a compact AAC .m4a file (about 15 MB per
 * hour). Encoding runs on its own background thread; [write] never blocks the mic.
 */
class AacRecorder(val file: File, private val sampleRate: Int = 16_000) {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var muxerStarted = false
    @Volatile private var samplesWritten = 0L
    private val info = MediaCodec.BufferInfo()

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    private val frames = Channel<ShortArray>(capacity = 2000) // ~40 s of slack
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val worker = scope.launch {
        for (frame in frames) encode(frame)
        endOfStream()
    }

    /** Recorded length so far, in milliseconds. */
    val durationMs: Long get() = samplesWritten * 1000 / sampleRate


    fun write(frame: ShortArray) {
        frames.trySend(frame)
    }

    /** Flushes everything, closes the file and returns it. Safe to call once. */
    suspend fun finish(): File {
        frames.close()
        worker.join()
        return file
    }

    /** Blocking version for shutdown paths that can't suspend. */
    fun finishBlocking(): File = runBlocking { finish() }

    private fun encode(pcm: ShortArray) {
        var offset = 0
        while (offset < pcm.size) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) { drain(false); continue }
            val buffer = codec.getInputBuffer(index) ?: continue
            buffer.clear()
            val count = minOf(pcm.size - offset, buffer.remaining() / 2)
            for (i in 0 until count) {
                val s = pcm[offset + i].toInt()
                buffer.put((s and 0xff).toByte())
                buffer.put((s shr 8 and 0xff).toByte())
            }
            codec.queueInputBuffer(index, 0, count * 2, presentationUs(), 0)
            samplesWritten += count
            offset += count
            drain(false)
        }
    }

    private fun endOfStream() {
        try {
            var index: Int
            do { index = codec.dequeueInputBuffer(10_000); if (index < 0) drain(false) } while (index < 0)
            codec.queueInputBuffer(index, 0, 0, presentationUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val out = codec.getOutputBuffer(index)
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (out != null && info.size > 0 && muxerStarted && !isConfig) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        muxer.writeSampleData(track, out, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    private fun presentationUs() = samplesWritten * 1_000_000L / sampleRate
}
