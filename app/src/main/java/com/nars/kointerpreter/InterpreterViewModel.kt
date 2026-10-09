package com.nars.kointerpreter

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nars.kointerpreter.api.SpeechToText
import com.nars.kointerpreter.api.FreeTranslator
import com.nars.kointerpreter.api.MlKitTranslator
import com.nars.kointerpreter.api.OpenAiTranslator
import com.nars.kointerpreter.api.TranslationEngine
import com.nars.kointerpreter.api.Translator
import com.nars.kointerpreter.data.TranslationProvider
import com.nars.kointerpreter.api.Turn
import com.nars.kointerpreter.audio.AudioCapture
import com.nars.kointerpreter.audio.MicDevice
import com.nars.kointerpreter.audio.MicManager
import com.nars.kointerpreter.audio.Vad
import com.nars.kointerpreter.audio.WavEncoder
import com.nars.kointerpreter.data.Engine
import com.nars.kointerpreter.data.Settings
import com.nars.kointerpreter.data.SettingsRepository
import com.nars.kointerpreter.speech.KoreanRecognizer
import com.nars.kointerpreter.meeting.AacRecorder
import com.nars.kointerpreter.meeting.MeetingService
import com.nars.kointerpreter.meeting.MeetingStore
import com.nars.kointerpreter.meeting.SavedMeeting
import com.nars.kointerpreter.meeting.TranscriptLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** MEETING = Continuous plus a recording of the whole meeting and a saved transcript. */
enum class Mode { CONTINUOUS, PUSH_TO_TALK, MEETING }

enum class EntryStatus {
    /** Built-in engine: Korean is still being spoken; text is live and may change. */
    LISTENING,
    /** OpenAI engine: audio sent, waiting for the Korean text. */
    TRANSCRIBING,
    TRANSLATING,
    DONE,
    ERROR,
}

data class Entry(
    val id: Long,
    val korean: String = "",
    val english: String = "",
    val status: EntryStatus = EntryStatus.LISTENING,
    val error: String? = null,
    /** When the sentence started (wall-clock ms); used for meeting transcript timestamps. */
    val atMs: Long = 0L,
)

data class UiState(
    val mode: Mode = Mode.CONTINUOUS,
    val listening: Boolean = false,
    val hearingSpeech: Boolean = false,
    val micError: String? = null,
    /** Set while a meeting is being recorded (start time, for the timer). */
    val meetingStartedAt: Long? = null,
    /** True while a finished meeting is being written to storage. */
    val savingMeeting: Boolean = false,
)

sealed interface MeetingEvent {
    data class Saved(val meeting: SavedMeeting) : MeetingEvent
    data class Failed(val message: String) : MeetingEvent
}

class InterpreterViewModel(app: Application) : AndroidViewModel(app), KoreanRecognizer.Listener {

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

    private val _events = MutableSharedFlow<MeetingEvent>(extraBufferCapacity = 4)
    /** One-off notices for the UI, such as "meeting saved". */
    val events: SharedFlow<MeetingEvent> = _events.asSharedFlow()

    /** The key setup sheet is offered once per app launch, not every time the main screen reappears. */
    var askedForKeysThisLaunch = false

    /** What the mic is doing right now, and with which engine. */
    private enum class Capture { OFF, STARTING, CONTINUOUS, PTT_HOLDING, PTT_FINISHING }
    private var capture = Capture.OFF
    private var engine = Engine.BUILT_IN

    /**
     * Every utterance goes through this queue in spoken order. The Korean text may
     * still be on its way (OpenAI transcribes in parallel), but translations always
     * run one at a time and in order, so each one sees the previous as context.
     */
    private class Pending(val id: Long, val korean: Deferred<String>)
    private val queue = Channel<Pending>(Channel.UNLIMITED)

    init {
        viewModelScope.launch { for (p in queue) process(p) }
        // Know early whether the free translator's model is already on the phone.
        viewModelScope.launch { FreeTranslator.refreshState() }
    }

    // ================================================================ controls

    fun setMode(mode: Mode) {
        if (mode == _ui.value.mode) return
        stopAll()
        _ui.update { it.copy(mode = mode) }
    }

    /** Switch between Everyday (built-in) and Noisy place (OpenAI). */
    fun setEngine(e: Engine) {
        if (e == settings.value.engine) return
        stopAll()
        settingsRepo.update { it.copy(engine = e) }
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
        if (engine == Engine.BUILT_IN) builtInStopContinuous() else openAiStopContinuous()
    }

    fun pttPress() = begin(ptt = true)

    fun pttRelease() {
        if (capture == Capture.STARTING) return cancelStart()
        if (capture != Capture.PTT_HOLDING) return
        if (engine == Engine.BUILT_IN) builtInPttRelease() else openAiPttRelease()
    }

    /** App went to the background: stop listening and hand the mic route back. */
    fun onBackground() {
        // A meeting keeps recording in the background (the meeting service keeps the mic allowed).
        if (meetingRecorder != null) return
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
    /** Mic that Noisy-place mode records from (pinned so the right mic is used). */
    private var recordDevice: AudioDeviceInfo? = null

    /**
     * Routes audio to the chosen mic, then starts the chosen engine. Routing a
     * Bluetooth mic takes up to a second the first time; the route is kept
     * afterwards, so later presses start instantly.
     */
    private fun begin(ptt: Boolean) {
        if (capture != Capture.OFF) return
        capture = Capture.STARTING
        val meeting = !ptt && _ui.value.mode == Mode.MEETING
        // Meetings record the audio themselves, so they use the OpenAI recognizer on that same
        // audio (the built-in recognizer can't share the microphone with a recorder).
        engine = if (meeting) Engine.OPENAI else settings.value.engine
        val wantsExternal = settings.value.micKey.isNotBlank()
        val external = if (wantsExternal) micManager.findInput(settings.value.micKey) else null
        // Pin the built-in mic too, so a plugged-in USB mic doesn't take over when the phone mic was chosen.
        recordDevice = external ?: micManager.builtInMic()
        _ui.update { it.copy(listening = true, micError = null) }

        startJob = viewModelScope.launch {
            micManager.ensureRoute(external)
            if (capture != Capture.STARTING) return@launch
            capture = Capture.OFF
            when {
                engine == Engine.BUILT_IN && ptt -> builtInPttPress()
                engine == Engine.BUILT_IN -> builtInStartContinuous()
                ptt -> openAiPttPress()
                else -> openAiStartContinuous(meeting)
            }
            if (capture == Capture.OFF) {
                setIdle() // the engine couldn't start; it has already shown why
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

    // ============================================== Everyday: built-in recognizer

    private val recognizer = KoreanRecognizer(app, this)
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
        startSession()
    }

    private fun builtInStopContinuous() {
        capture = Capture.OFF
        nextSession?.cancel()
        setIdle()
        if (liveId != null) {
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
        startSession()
    }

    private fun builtInPttRelease() {
        capture = Capture.PTT_FINISHING
        nextSession?.cancel()
        setIdle()
        recognizer.stop()
        finishTimeout = viewModelScope.launch {
            delay(2500)
            recognizer.cancel()
            finishPtt(partial)
        }
    }

    private fun startSession() {
        partial = ""
        val s = settings.value
        recognizer.start(s.onDeviceRecognition, s.silenceMs, s.biasingTerms)
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
            it.copy(micError = "Speech recognition isn't available on this phone. Install or enable the Google app, or switch to Noisy place.")
        }
        return false
    }

    override fun onSpeechStart() {
        if (engine == Engine.BUILT_IN && capture == Capture.CONTINUOUS) _ui.update { it.copy(hearingSpeech = true) }
    }

    override fun onSpeechEnd() {
        if (engine == Engine.BUILT_IN && capture == Capture.CONTINUOUS) _ui.update { it.copy(hearingSpeech = false) }
    }

    override fun onLevel(level: Float) {
        if (capture == Capture.CONTINUOUS || capture == Capture.PTT_HOLDING) _level.value = level
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
        val silence = KoreanRecognizer.isSilence(error)
        val transient = KoreanRecognizer.isTransient(error)
        when (capture) {
            Capture.CONTINUOUS -> {
                if (partial.isNotBlank()) finalizeLive(partial)
                when {
                    silence -> restartSoon()
                    transient -> {
                        _ui.update { it.copy(micError = KoreanRecognizer.message(error)) }
                        recognizer.destroy() // a fresh instance recovers from "busy"/client errors
                        restartSoon(1000)
                    }
                    else -> fail(KoreanRecognizer.message(error))
                }
            }
            Capture.PTT_HOLDING -> {
                appendPtt(partial)
                when {
                    silence -> restartSoon()
                    transient -> { recognizer.destroy(); restartSoon(800) }
                    else -> fail(KoreanRecognizer.message(error))
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
        updateEntry(id) { it.copy(korean = text) }
    }

    /** The sentence is complete: lock in the Korean and queue it for translation. */
    private fun finalizeLive(text: String) {
        finishTimeout?.cancel()
        val id = liveId
        liveId = null
        partial = ""
        val korean = text.trim()
        if (korean.isBlank()) {
            if (id != null) removeEntry(id)
            return
        }
        val entryId = id ?: newEntry(EntryStatus.LISTENING)
        updateEntry(entryId) { it.copy(korean = korean, status = EntryStatus.TRANSLATING) }
        queue.trySend(Pending(entryId, CompletableDeferred(korean)))
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

    // ================================================ Noisy place: OpenAI engine

    private var captureJob: Job? = null
    private var pttBuffer: ArrayList<ShortArray>? = null
    /** Audio kept until translated, so "Retry" can re-send it if transcription failed. */
    private val audioForRetry = HashMap<Long, ShortArray>()

    private fun openAiStartContinuous(meeting: Boolean = false) {
        capture = Capture.CONTINUOUS
        setListening()
        if (meeting) startMeeting()
        val s = settings.value
        val vad = Vad(sensitivity = s.sensitivity, silenceMs = s.silenceMs)
        captureJob = viewModelScope.launch {
            try {
                AudioCapture.frames(recordDevice).collect { frame ->
                    meetingRecorder?.write(frame)
                    val segment = vad.process(frame)
                    _level.value = normalize(vad.level)
                    // UiState is a data class: equal values are dropped, so this only emits on change.
                    _ui.update { it.copy(hearingSpeech = vad.isInSpeech) }
                    if (segment != null) submitAudio(segment)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(micError = e.message ?: "Microphone error") }
            } finally {
                // Whatever was being said when Stop was pressed still gets translated.
                vad.flush()?.let { submitAudio(it) }
                finishMeeting()
                if (capture == Capture.CONTINUOUS) capture = Capture.OFF
                captureJob = null
                setIdle()
            }
        }
    }

    private fun openAiStopContinuous() {
        capture = Capture.OFF
        captureJob?.cancel()
    }

    private fun openAiPttPress() {
        capture = Capture.PTT_HOLDING
        val buffer = ArrayList<ShortArray>()
        pttBuffer = buffer
        setListening()
        captureJob = viewModelScope.launch {
            try {
                AudioCapture.frames(recordDevice).collect { frame ->
                    buffer.add(frame)
                    _level.value = normalize(Vad.rms(frame).let { kotlin.math.ln(1f + it) / kotlin.math.ln(8001f) })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(micError = e.message ?: "Microphone error") }
            }
        }
    }

    private fun openAiPttRelease() {
        val job = captureJob
        val buffer = pttBuffer
        captureJob = null
        pttBuffer = null
        capture = Capture.OFF
        viewModelScope.launch {
            job?.cancelAndJoin()
            setIdle()
            if (buffer != null && buffer.size * Vad.FRAME_MS >= 400) {
                val pcm = ShortArray(buffer.sumOf { it.size })
                var off = 0
                for (f in buffer) { f.copyInto(pcm, off); off += f.size }
                submitAudio(pcm)
            }
        }
    }

    /** Maps the VAD's log loudness (noise ≈ 0.5, speech ≈ 0.9) onto 0..1 for the wave. */
    private fun normalize(raw: Float) = ((raw - 0.5f) / 0.45f).coerceIn(0f, 1f)

    private fun submitAudio(pcm: ShortArray, reuseId: Long? = null) {
        // The sentence started pcm.size samples ago (16 samples per ms).
        val id = reuseId ?: newEntry(EntryStatus.TRANSCRIBING, System.currentTimeMillis() - pcm.size / 16)
        if (reuseId != null) updateEntry(id) { it.copy(status = EntryStatus.TRANSCRIBING, error = null) }
        audioForRetry[id] = pcm
        val s = settings.value
        val prompt = sttPrompt(s)
        val stt = SpeechToText(s.openAiKey.trim(), s.sttModel.trim())
        val text = viewModelScope.async(Dispatchers.IO) { stt.transcribe(WavEncoder.encode(pcm), prompt) }
        queue.trySend(Pending(id, text))
    }

    /** Recent Korean text + glossary helps the recognizer with names and jargon. */
    private fun sttPrompt(s: Settings): String {
        val recent = _entries.value.filter { it.korean.isNotBlank() }.takeLast(2).joinToString(" ") { it.korean }
        return listOf(s.glossary.trim(), recent).filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun fail(message: String) {
        capture = Capture.OFF
        nextSession?.cancel()
        setIdle()
        _ui.update { it.copy(micError = message) }
    }

    // ================================================================ meetings

    private var meetingRecorder: AacRecorder? = null
    private var meetingStartedAt = 0L
    private var meetingFirstId = 0L
    /** Saving outlives the ViewModel, so a meeting is still saved if the app is closed. */
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun startMeeting() {
        val ctx = getApplication<Application>()
        val now = System.currentTimeMillis()
        val recorder = runCatching { AacRecorder(MeetingStore.newRecordingFile(ctx, now)) }.getOrElse { e ->
            _ui.update { it.copy(micError = "Couldn't start the recording (${e.message}). Translating without recording.") }
            return
        }
        meetingRecorder = recorder
        meetingStartedAt = now
        meetingFirstId = nextId
        runCatching { MeetingService.start(ctx) }
        _ui.update { it.copy(meetingStartedAt = now) }
    }

    /** Closes the recording, waits for the last translations, then saves audio + transcript. */
    private fun finishMeeting(waitForTranslations: Boolean = true) {
        val recorder = meetingRecorder ?: return
        meetingRecorder = null
        val startedAt = meetingStartedAt
        val firstId = meetingFirstId
        val ctx = getApplication<Application>()
        val s = settings.value
        _ui.update { it.copy(meetingStartedAt = null, savingMeeting = true) }

        saveScope.launch {
            val audioFile = withContext(Dispatchers.IO) { recorder.finish() }
            runCatching { MeetingService.stop(ctx) }
            if (waitForTranslations) {
                var waited = 0
                while (waited < 60_000 && _entries.value.any { it.id >= firstId && it.status.isPending() }) {
                    delay(300)
                    waited += 300
                }
            }
            val lines = _entries.value
                .filter { it.id >= firstId && it.korean.isNotBlank() }
                .map { TranscriptLine(it.atMs, it.korean, it.english) }
            val saved = withContext(Dispatchers.IO) {
                val text = MeetingStore.transcriptText(
                    startedAt, recorder.durationMs, "OpenAI (${s.sttModel})", s.translationLabel, lines,
                )
                SavedMeeting(
                    audio = MeetingStore.saveAudio(ctx, audioFile),
                    transcript = MeetingStore.saveTranscript(ctx, MeetingStore.baseName(startedAt), text),
                    durationMs = recorder.durationMs,
                    folderHint = MeetingStore.folderHint(),
                )
            }
            _ui.update { it.copy(savingMeeting = false) }
            _events.tryEmit(
                if (saved.audio == null && saved.transcript == null) {
                    MeetingEvent.Failed("Couldn't save the meeting. Check that your phone has free storage.")
                } else {
                    MeetingEvent.Saved(saved)
                }
            )
        }
    }

    private fun EntryStatus.isPending() =
        this == EntryStatus.LISTENING || this == EntryStatus.TRANSCRIBING || this == EntryStatus.TRANSLATING

    // ============================================================ translation

    private suspend fun process(p: Pending) {
        val korean = try {
            p.korean.await().trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateEntry(p.id) { it.copy(status = EntryStatus.ERROR, error = e.message ?: "Transcription failed") }
            return
        }
        if (korean.isBlank() || SpeechToText.looksLikeHallucination(korean)) {
            removeEntry(p.id)
            return
        }
        updateEntry(p.id) { it.copy(korean = korean, status = EntryStatus.TRANSLATING) }
        translate(p.id, korean)
    }

    private suspend fun translate(id: Long, korean: String) {
        val s = settings.value
        val context = _entries.value
            .filter { it.id < id && it.status == EntryStatus.DONE }
            .takeLast(s.contextSize)
            .map { Turn(it.korean, it.english) }
        try {
            val translation: TranslationEngine = when (s.translator) {
                TranslationProvider.CLAUDE -> Translator(s.anthropicKey.trim(), s.claudeModel.trim(), s.glossary)
                TranslationProvider.OPENAI -> OpenAiTranslator(s.openAiKey.trim(), s.openAiTranslationModel.trim(), s.glossary)
                TranslationProvider.FREE -> MlKitTranslator(s.glossary)
            }
            val english = translation
                .translate(korean, context) { partialText ->
                    updateEntry(id) { it.copy(english = partialText) }
                }
            updateEntry(id) { it.copy(english = english, status = EntryStatus.DONE, error = null) }
            audioForRetry.remove(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateEntry(id) { it.copy(status = EntryStatus.ERROR, error = e.message ?: "Translation failed") }
        }
    }

    fun retry(id: Long) {
        val entry = _entries.value.firstOrNull { it.id == id } ?: return
        if (entry.korean.isNotBlank()) {
            updateEntry(id) { it.copy(status = EntryStatus.TRANSLATING, error = null, english = "") }
            viewModelScope.launch { translate(id, entry.korean) }
        } else {
            audioForRetry[id]?.let { submitAudio(it, reuseId = id) }
        }
    }

    // ============================================================= transcript

    private fun newEntry(status: EntryStatus, atMs: Long = System.currentTimeMillis()): Long {
        val id = nextId++
        _entries.update { it + Entry(id, status = status, atMs = atMs) }
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

    /** Drops audio kept for retries once Undo is no longer possible. */
    fun forgetAudio(removed: List<Entry>) {
        removed.forEach { audioForRetry.remove(it.id) }
    }

    fun transcriptText(): String = _entries.value
        .filter { it.status == EntryStatus.DONE }
        .joinToString("\n\n") { "${it.korean}\n${it.english}" }

    private fun updateEntry(id: Long, f: (Entry) -> Entry) =
        _entries.update { list -> list.map { if (it.id == id) f(it) else it } }

    private fun removeEntry(id: Long) {
        _entries.update { list -> list.filterNot { it.id == id } }
        audioForRetry.remove(id)
    }

    override fun onCleared() {
        // App closed mid-meeting: save what was recorded (translations still running are dropped).
        captureJob?.cancel()
        finishMeeting(waitForTranslations = false)
        recognizer.destroy()
        micManager.release()
        muteBeeps(false)
    }
}
