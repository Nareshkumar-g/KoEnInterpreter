package com.nars.kointerpreter

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nars.kointerpreter.api.FreeTranslator
import com.nars.kointerpreter.audio.MicDevice
import com.nars.kointerpreter.audio.MicManager
import com.nars.kointerpreter.data.Settings
import com.nars.kointerpreter.data.SettingsRepository
import com.nars.kointerpreter.speech.KoreanRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Mode { CONTINUOUS, PUSH_TO_TALK }

enum class EntryStatus {
    /** Korean is still being spoken; text is live and may change. */
    LISTENING,
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
)

data class UiState(
    val mode: Mode = Mode.CONTINUOUS,
    val listening: Boolean = false,
    val hearingSpeech: Boolean = false,
    val micError: String? = null,
)

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

    /** What the mic is doing right now. */
    private enum class Capture { OFF, STARTING, CONTINUOUS, PTT_HOLDING, PTT_FINISHING }
    private var capture = Capture.OFF

    /** Finished sentences are translated one at a time, in spoken order. */
    private class Pending(val id: Long, val korean: String)
    private val queue = Channel<Pending>(Channel.UNLIMITED)

    init {
        viewModelScope.launch { for (p in queue) translate(p.id, p.korean) }
        // Fetch the Korean translation model (about 30 MB, once) so the first sentence isn't delayed.
        viewModelScope.launch { FreeTranslator.refreshState(); FreeTranslator.ensureModel() }
    }

    // ================================================================ controls

    fun setMode(mode: Mode) {
        if (mode == _ui.value.mode) return
        stopAll()
        _ui.update { it.copy(mode = mode) }
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
        queue.trySend(Pending(entryId, korean))
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
        capture = Capture.OFF
        nextSession?.cancel()
        setIdle()
        _ui.update { it.copy(micError = message) }
    }

    // ============================================================ translation

    private suspend fun translate(id: Long, korean: String) {
        try {
            val english = FreeTranslator.translate(korean, settings.value.glossary)
            updateEntry(id) { it.copy(english = english, status = EntryStatus.DONE, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateEntry(id) { it.copy(status = EntryStatus.ERROR, error = e.message ?: "Translation failed") }
        }
    }

    fun retry(id: Long) {
        val entry = _entries.value.firstOrNull { it.id == id } ?: return
        if (entry.korean.isBlank()) return
        updateEntry(id) { it.copy(status = EntryStatus.TRANSLATING, error = null, english = "") }
        viewModelScope.launch { translate(id, entry.korean) }
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
        .joinToString("\n\n") { "${it.korean}\n${it.english}" }

    private fun updateEntry(id: Long, f: (Entry) -> Entry) =
        _entries.update { list -> list.map { if (it.id == id) f(it) else it } }

    private fun removeEntry(id: Long) {
        _entries.update { list -> list.filterNot { it.id == id } }
    }

    override fun onCleared() {
        recognizer.destroy()
        micManager.release()
        muteBeeps(false)
    }
}
