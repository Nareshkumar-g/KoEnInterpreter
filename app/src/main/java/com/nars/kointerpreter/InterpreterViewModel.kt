package com.nars.kointerpreter

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nars.kointerpreter.api.FreeTranslator
import com.nars.kointerpreter.audio.MicDevice
import com.nars.kointerpreter.audio.MicManager
import com.nars.kointerpreter.audio.RecordingStore
import com.nars.kointerpreter.audio.VoiceRecorder
import com.nars.kointerpreter.data.Lang
import com.nars.kointerpreter.data.Settings
import com.nars.kointerpreter.data.SettingsRepository
import com.nars.kointerpreter.speech.SpeechListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Mode { CONTINUOUS, PUSH_TO_TALK }

enum class EntryStatus {
    /** Still being spoken; text is live and may change. */
    LISTENING,
    TRANSLATING,
    DONE,
    ERROR,
}

data class Entry(
    val id: Long,
    /** What was said, in the speaker's language. */
    val original: String = "",
    /** The translation into the other language. */
    val translation: String = "",
    /** Language [original] is in. */
    val lang: Lang = Lang.KOREAN,
    val status: EntryStatus = EntryStatus.LISTENING,
    val error: String? = null,
)

data class UiState(
    val mode: Mode = Mode.CONTINUOUS,
    val listening: Boolean = false,
    val hearingSpeech: Boolean = false,
    val micError: String? = null,
    /** Set while the voice is being recorded (start time, for the REC timer). */
    val recordingStartedAt: Long? = null,
)

/** A push-to-talk recording was saved (or couldn't be). */
sealed interface RecordingEvent {
    data class Saved(val uri: Uri, val durationMs: Long, val folder: String) : RecordingEvent
    data class Failed(val message: String) : RecordingEvent
}

class InterpreterViewModel(app: Application) : AndroidViewModel(app), SpeechListener.Listener {

    val settingsRepo = SettingsRepository(app)
    val settings: StateFlow<Settings> = settingsRepo.settings

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Voice loudness 0..1, updated many times a second; kept apart so only the wave redraws. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private var nextId = 1L

    /** What the mic is doing right now. */
    private enum class Capture { OFF, STARTING, CONTINUOUS, PTT_HOLDING, PTT_FINISHING }
    private var capture = Capture.OFF

    /** Finished sentences are translated one at a time, in spoken order. */
    private class Pending(val id: Long, val text: String, val lang: Lang)
    private val queue = Channel<Pending>(Channel.UNLIMITED)

    init {
        viewModelScope.launch { for (p in queue) translate(p.id, p.text, p.lang) }
        // Fetch the Korean translation model (about 30 MB, once) so the first sentence isn't delayed.
        viewModelScope.launch { FreeTranslator.refreshState(); FreeTranslator.ensureModel() }
    }

    // ================================================================ controls

    fun setMode(mode: Mode) {
        if (mode == _ui.value.mode) return
        stopAll()
        _ui.update { it.copy(mode = mode) }
    }

    /** Record the voice while listening (the REC button). */
    fun setRecordAudio(on: Boolean) {
        if (capture != Capture.OFF) return // takes effect from the next start
        settingsRepo.update { it.copy(recordAudio = on) }
    }

    /** Recording needs the recognizer to accept the app's own audio (Android 13+). */
    val canRecord: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Who's speaking now: Korean (translated to English) or English (translated to Korean). */
    fun setListen(listen: Lang) {
        if (listen == settings.value.listen) return
        stopAll()
        settingsRepo.update { it.copy(listen = listen) }
    }

    fun stopAll() {
        when (capture) {
            Capture.STARTING -> cancelStart()
            Capture.CONTINUOUS -> stopListening()
            Capture.PTT_HOLDING -> pttRelease()
            else -> Unit
        }
    }

    fun startContinuous() = begin(ptt = false)

    fun stopListening() {
        if (capture == Capture.STARTING) return cancelStart()
        if (capture != Capture.CONTINUOUS) return
        builtInStopContinuous()
    }

    fun pttPress() = begin(ptt = true)

    fun pttRelease() {
        if (capture == Capture.STARTING) return cancelStart()
        if (capture != Capture.PTT_HOLDING) return
        builtInPttRelease()
    }

    /** App went to the background: stop listening and hand the mic route back. */
    fun onBackground() {
        stopAll()
        micManager.clearRoute()
    }

    // ================================================================ microphone

    private val micManager = MicManager(app)

    /** External mics currently connected; updates live as they're plugged in or unplugged. */
    val externalMics: StateFlow<List<MicDevice>> = micManager.external

    /** "" = the phone/tablet's built-in mic, otherwise an external mic's key. */
    fun setMic(key: String) {
        if (key == settings.value.micKey) return
        stopAll()
        micManager.clearRoute()
        settingsRepo.update { it.copy(micKey = key) }
    }

    private var startJob: Job? = null
    /** Mic push-to-talk records from: the chosen external mic, or the built-in one. */
    private var pttDevice: AudioDeviceInfo? = null

    /**
     * Routes audio to the chosen mic, then starts the recognizer. Routing a
     * Bluetooth mic takes up to a second the first time; the route is kept
     * afterwards, so later presses start instantly.
     */
    private fun begin(ptt: Boolean) {
        if (capture != Capture.OFF) return
        capture = Capture.STARTING
        val wantsExternal = settings.value.micKey.isNotBlank()
        val external = if (wantsExternal) micManager.findInput(settings.value.micKey) else null
        pttDevice = external ?: micManager.builtInMic()
        _ui.update { it.copy(listening = true, micError = null) }

        startJob = viewModelScope.launch {
            micManager.ensureRoute(external)
            if (capture != Capture.STARTING) return@launch
            capture = Capture.OFF
            if (ptt) builtInPttPress() else builtInStartContinuous()
            if (capture == Capture.OFF) {
                setIdle() // the recognizer couldn't start; it has already shown why
            } else if (wantsExternal && external == null) {
                _ui.update { it.copy(micError = "Your external mic isn't connected, so the built-in mic is being used.") }
            }
        }
    }

    private fun cancelStart() {
        startJob?.cancel()
        capture = Capture.OFF
        setIdle()
    }

    private fun setListening() {
        _ui.update { it.copy(listening = true, hearingSpeech = capture == Capture.PTT_HOLDING, micError = null) }
    }

    private fun setIdle() {
        _level.value = 0f
        muteBeeps(false)
        _ui.update { it.copy(listening = false, hearingSpeech = false) }
    }

    // ===================================================== built-in recognizer

    private val recognizer = SpeechListener(app, this)
    private var nextSession: Job? = null
    private var finishTimeout: Job? = null

    /** Card showing the sentence currently being spoken. */
    private var liveId: Long? = null
    /** Latest partial text of the current recognition session. */
    private var partial = ""
    /** Push-to-talk: finished pieces while the button is held (the recognizer splits on pauses). */
    private val pttText = StringBuilder()

    private fun builtInStartContinuous() {
        if (!checkRecognizer()) return
        capture = Capture.CONTINUOUS
        muteBeeps(true)
        setListening()
        startRecording()
        startSession()
    }

    private fun builtInStopContinuous() {
        capture = Capture.OFF
        nextSession?.cancel()
        setIdle()
        val speaking = liveId != null
        // Ends the recognizer's audio too (when recording), so it finishes the current sentence.
        finishRecording()
        if (speaking) {
            // Let the recognizer deliver the sentence that was being spoken, but don't wait forever.
            recognizer.stop()
            finishTimeout = viewModelScope.launch {
                delay(2500)
                recognizer.cancel()
                finalizeLive(partial)
            }
        } else {
            recognizer.cancel()
        }
    }

    private fun builtInPttPress() {
        if (!checkRecognizer()) return
        capture = Capture.PTT_HOLDING
        pttText.clear()
        liveId = null
        muteBeeps(true)
        setListening()
        startRecording()
        startSession()
    }

    private fun builtInPttRelease() {
        capture = Capture.PTT_FINISHING
        nextSession?.cancel()
        setIdle()
        recognizer.stop()
        finishRecording()
        finishTimeout = viewModelScope.launch {
            delay(2500)
            recognizer.cancel()
            finishPtt(partial)
        }
    }

    private fun startSession() {
        partial = ""
        voiced = false
        val s = settings.value
        recognizer.start(s.listen, s.onDeviceRecognition, s.silenceMs, s.biasingTerms, recorder?.newRecognizerInput())
    }

    // ================================================ push-to-talk recording

    /** Records the voice while the push-to-talk button is held; the recognizer listens to the same audio. */
    private var recorder: VoiceRecorder? = null
    private var recordingStartedAt = 0L

    private val _recordings = MutableSharedFlow<RecordingEvent>(extraBufferCapacity = 4)
    /** "Recording saved" notices for the UI. */
    val recordings: SharedFlow<RecordingEvent> = _recordings.asSharedFlow()

    /** Saving outlives the ViewModel, so a recording is still saved if the app closes. */
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun startRecording() {
        // The recognizer can only listen to the app's own audio on Android 13+.
        if (!canRecord || !settings.value.recordAudio) return
        val ctx = getApplication<Application>()
        val rec = VoiceRecorder(RecordingStore.newTempFile(ctx), pttDevice)
        rec.onLevel = { level -> mainHandler.post { onRecorderLevel(level) } }
        try {
            rec.start()
        } catch (e: Exception) {
            _ui.update { it.copy(micError = "Couldn't start the recording (${e.message}). Translating without recording.") }
            return
        }
        recorder = rec
        recordingStartedAt = System.currentTimeMillis()
        _ui.update { it.copy(recordingStartedAt = recordingStartedAt) }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    /** Continuous + recording: whether someone is talking, judged from the recorded audio. */
    private var voiced = false
    private var lastVoiceAt = 0L

    /**
     * While recording, the recognizer hears the app's audio stream, which it doesn't split
     * into sentences by itself. So in Continuous mode the app ends each sentence when the
     * speaker pauses; the recognizer then delivers it and a new sentence starts.
     */
    private fun onRecorderLevel(level: Float) {
        if (recorder == null) return
        _level.value = level
        if (capture != Capture.CONTINUOUS) return
        val now = SystemClock.elapsedRealtime()
        if (level > VOICE_LEVEL) {
            lastVoiceAt = now
            if (!voiced) { voiced = true; _ui.update { it.copy(hearingSpeech = true) } }
        } else if (voiced && now - lastVoiceAt > settings.value.silenceMs + 300) {
            voiced = false
            _ui.update { it.copy(hearingSpeech = false) }
            recorder?.endRecognizerInput()
        }
    }

    /** Ends the audio the recognizer hears, then finishes and saves the recording. */
    private fun finishRecording() {
        val rec = recorder ?: return
        recorder = null
        _ui.update { it.copy(recordingStartedAt = null) }
        rec.endRecognizerInput()
        val startedAt = recordingStartedAt
        val ctx = getApplication<Application>()
        saveScope.launch {
            val file = rec.stop() ?: return@launch
            if (rec.durationMs < 300) { file.delete(); return@launch } // an accidental tap
            val uri = RecordingStore.save(ctx, file, startedAt)
            _recordings.tryEmit(
                if (uri != null) RecordingEvent.Saved(uri, rec.durationMs, RecordingStore.folderHint())
                else RecordingEvent.Failed("Couldn't save the recording. Check that the tablet has free storage.")
            )
        }
    }

    private fun restartSoon(delayMs: Long = 150) {
        nextSession?.cancel()
        nextSession = viewModelScope.launch {
            delay(delayMs)
            if (capture == Capture.CONTINUOUS || capture == Capture.PTT_HOLDING) startSession()
        }
    }

    private fun checkRecognizer(): Boolean {
        if (recognizer.isAvailable()) return true
        _ui.update {
            it.copy(micError = "Speech recognition isn't available on this phone. Install or enable the Google app.")
        }
        return false
    }

    override fun onSpeechStart() {
        if (capture == Capture.CONTINUOUS) _ui.update { it.copy(hearingSpeech = true) }
    }

    override fun onSpeechEnd() {
        if (capture == Capture.CONTINUOUS) _ui.update { it.copy(hearingSpeech = false) }
    }

    override fun onLevel(level: Float) {
        // While recording, the recorder reports the level from the same audio.
        if (recorder == null && (capture == Capture.CONTINUOUS || capture == Capture.PTT_HOLDING)) _level.value = level
    }

    override fun onPartial(text: String) {
        partial = text
        val ptt = capture == Capture.PTT_HOLDING || capture == Capture.PTT_FINISHING
        showLive(if (ptt && pttText.isNotEmpty()) "$pttText $text" else text)
    }

    override fun onFinal(text: String) {
        val t = text.ifBlank { partial }
        when (capture) {
            Capture.CONTINUOUS -> { finalizeLive(t); restartSoon() }
            // Stopped on a pause while the button is still held: keep listening.
            Capture.PTT_HOLDING -> { appendPtt(t); restartSoon() }
            Capture.PTT_FINISHING -> finishPtt(t)
            Capture.OFF -> finalizeLive(t) // Stop was pressed mid-sentence
            Capture.STARTING -> Unit
        }
    }

    override fun onError(error: Int) {
        val silence = SpeechListener.isSilence(error)
        val transient = SpeechListener.isTransient(error)
        when (capture) {
            Capture.CONTINUOUS -> {
                if (partial.isNotBlank()) finalizeLive(partial)
                when {
                    silence -> restartSoon()
                    transient -> {
                        _ui.update { it.copy(micError = SpeechListener.message(error)) }
                        recognizer.destroy() // a fresh instance recovers from "busy"/client errors
                        restartSoon(1000)
                    }
                    else -> fail(SpeechListener.message(error))
                }
            }
            Capture.PTT_HOLDING -> {
                appendPtt(partial)
                when {
                    silence -> restartSoon()
                    transient -> { recognizer.destroy(); restartSoon(800) }
                    else -> fail(SpeechListener.message(error))
                }
            }
            Capture.PTT_FINISHING -> finishPtt(partial)
            Capture.OFF -> finalizeLive(partial)
            Capture.STARTING -> Unit
        }
    }

    private fun showLive(text: String) {
        if (text.isBlank()) return
        if (_ui.value.micError != null) _ui.update { it.copy(micError = null) }
        val id = liveId ?: newEntry(EntryStatus.LISTENING).also { liveId = it }
        updateEntry(id) { it.copy(original = text, lang = settings.value.listen) }
    }

    /** The sentence is complete: lock in the text and queue it for translation. */
    private fun finalizeLive(text: String) {
        finishTimeout?.cancel()
        val id = liveId
        liveId = null
        partial = ""
        val said = text.trim()
        val lang = settings.value.listen
        if (said.isBlank()) {
            if (id != null) removeEntry(id)
            return
        }
        val entryId = id ?: newEntry(EntryStatus.LISTENING)
        updateEntry(entryId) { it.copy(original = said, lang = lang, status = EntryStatus.TRANSLATING) }
        queue.trySend(Pending(entryId, said, lang))
    }

    private fun appendPtt(text: String) {
        if (text.isNotBlank()) {
            if (pttText.isNotEmpty()) pttText.append(' ')
            pttText.append(text.trim())
        }
        partial = ""
    }

    private fun finishPtt(last: String) {
        if (capture != Capture.PTT_FINISHING) return
        capture = Capture.OFF
        appendPtt(last)
        val full = pttText.toString()
        pttText.clear()
        finalizeLive(full)
    }

    /**
     * Many phones beep each time the recognizer starts and stops, which is
     * distracting in continuous mode. Muting media volume while listening hides it.
     */
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var beepsMuted = false

    private fun muteBeeps(mute: Boolean) {
        if (mute && !settings.value.muteBeeps) return
        if (mute == beepsMuted) return
        runCatching {
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                0,
            )
            beepsMuted = mute
        }
    }

    private fun fail(message: String) {
        finishRecording()
        capture = Capture.OFF
        nextSession?.cancel()
        setIdle()
        _ui.update { it.copy(micError = message) }
    }

    // ============================================================ translation

    private suspend fun translate(id: Long, text: String, from: Lang) {
        try {
            val translated = FreeTranslator.translate(text, from, settings.value.glossary)
            updateEntry(id) { it.copy(translation = translated, status = EntryStatus.DONE, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateEntry(id) { it.copy(status = EntryStatus.ERROR, error = e.message ?: "Translation failed") }
        }
    }

    fun retry(id: Long) {
        val entry = _entries.value.firstOrNull { it.id == id } ?: return
        if (entry.original.isBlank()) return
        updateEntry(id) { it.copy(status = EntryStatus.TRANSLATING, error = null, translation = "") }
        viewModelScope.launch { translate(id, entry.original, entry.lang) }
    }

    // ============================================================= transcript

    private fun newEntry(status: EntryStatus): Long {
        val id = nextId++
        _entries.update { it + Entry(id, status = status) }
        return id
    }

    /** Clears the transcript and returns what was removed, so the UI can offer Undo. */
    fun clear(): List<Entry> {
        val keep = liveId
        val removed = _entries.value.filter { it.id != keep }
        _entries.update { list -> list.filter { it.id == keep } }
        return removed
    }

    fun restore(removed: List<Entry>) {
        _entries.update { current ->
            val ids = current.mapTo(HashSet()) { it.id }
            (removed.filterNot { it.id in ids } + current).sortedBy { it.id }
        }
    }

    fun transcriptText(): String = _entries.value
        .filter { it.status == EntryStatus.DONE }
        .joinToString("\n\n") { "${it.original}\n${it.translation}" }

    private fun updateEntry(id: Long, f: (Entry) -> Entry) =
        _entries.update { list -> list.map { if (it.id == id) f(it) else it } }

    private fun removeEntry(id: Long) {
        _entries.update { list -> list.filterNot { it.id == id } }
    }

    private companion object {
        /** Recorder loudness (0..1) above which someone is talking. */
        const val VOICE_LEVEL = 0.35f
    }

    override fun onCleared() {
        finishRecording()
        recognizer.destroy()
        micManager.release()
        muteBeeps(false)
    }
}
