package com.nars.kointerpreter.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.nars.kointerpreter.data.Lang

/**
 * Thin wrapper around Android's built-in speech recognizer (normally Google's),
 * set up for Korean and Indian English. Must be used from the main thread.
 *
 * One call to [start] = one recognition session, which ends by itself when the
 * speaker pauses. The caller restarts it to keep listening continuously.
 */
class SpeechListener(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onSpeechStart()
        fun onSpeechEnd()
        /** Loudness 0..1. */
        fun onLevel(level: Float)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(error: Int)
    }

    private var recognizer: SpeechRecognizer? = null
    private var createdOnDevice = false

    /** Ignore late/duplicate callbacks from an older session (some devices send both results and an error). */
    private var session = 0

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * [audio]: on Android 13+, recognize audio the app records itself (16 kHz mono PCM)
     * instead of opening the microphone, so the app can keep a recording at the same time.
     */
    fun start(lang: Lang, onDevice: Boolean, silenceMs: Int, biasing: List<String>, audio: ParcelFileDescriptor? = null) {
        val useOnDevice = onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (recognizer == null || createdOnDevice != useOnDevice) {
            destroy()
            recognizer = if (useOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            createdOnDevice = useOnDevice
        }
        val id = ++session
        recognizer!!.setRecognitionListener(callbacks(id))
        recognizer!!.startListening(intent(lang, onDevice, silenceMs, biasing, audio))
    }

    /** Finish the current session now; the final text still arrives via [Listener.onFinal]. */
    fun stop() {
        recognizer?.stopListening()
    }

    /** Abort the current session without results. */
    fun cancel() {
        session++
        recognizer?.cancel()
    }

    fun destroy() {
        session++
        recognizer?.destroy()
        recognizer = null
    }

    private fun intent(lang: Lang, onDevice: Boolean, silenceMs: Int, biasing: List<String>, audio: ParcelFileDescriptor?) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            val primary = if (lang == Lang.ENGLISH) ENGLISH else KOREAN
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, primary)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, primary)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
            // Hints only: some recognizers use their own pause detection.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs.toLong())
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs.toLong())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && biasing.isNotEmpty()) {
                // Nudges the recognizer toward names and jargon from the glossary.
                putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(biasing.take(50)))
            }
            if (audio != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
            }
        }

    private fun callbacks(id: Int) = object : RecognitionListener {
        private fun live() = id == session

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { if (live()) listener.onSpeechStart() }
        override fun onRmsChanged(rmsdB: Float) {
            // Typical range: about -2 dB (silence) to 10 dB (loud speech).
            if (live()) listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { if (live()) listener.onSpeechEnd() }

        override fun onError(error: Int) {
            if (!live()) return
            session++ // this session is over
            listener.onError(error)
        }

        override fun onResults(results: Bundle?) {
            if (!live()) return
            session++
            listener.onFinal(results.firstText())
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!live()) return
            val text = partialResults.firstText()
            if (text.isNotBlank()) listener.onPartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun Bundle?.firstText(): String =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

    companion object {
        const val KOREAN = "ko-KR"
        const val ENGLISH = "en-IN"

        /** Errors that just mean "nobody said anything" — restart quietly. */
        fun isSilence(error: Int) =
            error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

        /** Errors worth retrying automatically after a short pause. */
        fun isTransient(error: Int) = error in setOf(
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            11, // ERROR_SERVER_DISCONNECTED (API 31)
        )

        fun message(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, 11 ->
                "No connection to the speech service. Retrying…"
            SpeechRecognizer.ERROR_SERVER -> "The speech service had a problem. Retrying…"
            SpeechRecognizer.ERROR_AUDIO -> "Couldn't record audio. Is another app using the microphone?"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
            10 -> "Too many requests to the speech service. Wait a moment and try again."
            12, 13 -> "Korean or English isn't available for speech recognition. In your tablet's settings, open the speech recognition languages and download Korean and English (India), or turn off on-device recognition."
            else -> "Speech recognition error ($error)."
        }
    }
}
