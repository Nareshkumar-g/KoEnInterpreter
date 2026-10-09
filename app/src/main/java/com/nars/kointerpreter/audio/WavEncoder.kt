package com.nars.kointerpreter.audio

import java.io.ByteArrayOutputStream

object WavEncoder {
    /** Wraps 16-bit mono PCM samples in a standard RIFF/WAV container. */
    fun encode(pcm: ShortArray, sampleRate: Int = Vad.SAMPLE_RATE): ByteArray {
        val dataBytes = pcm.size * 2
        val out = ByteArrayOutputStream(44 + dataBytes)
        fun str(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun int32(v: Int) {
            out.write(v and 0xff); out.write(v shr 8 and 0xff)
            out.write(v shr 16 and 0xff); out.write(v shr 24 and 0xff)
        }
        fun int16(v: Int) {
            out.write(v and 0xff); out.write(v shr 8 and 0xff)
        }
        str("RIFF"); int32(36 + dataBytes); str("WAVE")
        str("fmt "); int32(16); int16(1); int16(1)
        int32(sampleRate); int32(sampleRate * 2); int16(2); int16(16)
        str("data"); int32(dataBytes)
        val buf = ByteArray(dataBytes)
        for (i in pcm.indices) {
            val v = pcm[i].toInt()
            buf[2 * i] = (v and 0xff).toByte()
            buf[2 * i + 1] = (v shr 8 and 0xff).toByte()
        }
        out.write(buf)
        return out.toByteArray()
    }
}
