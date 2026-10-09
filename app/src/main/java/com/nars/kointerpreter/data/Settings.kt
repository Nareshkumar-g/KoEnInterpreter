package com.nars.kointerpreter.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A language spoken in the conversation. */
enum class Lang {
    KOREAN,
    ENGLISH;

    val other: Lang get() = if (this == KOREAN) ENGLISH else KOREAN

    companion object {
        /** Which language a piece of text is in, judged by its letters: 한글 → Korean, Latin → English. */
        fun ofText(text: String): Lang? {
            val hangul = text.count { it in '가'..'힣' || it in 'ㄱ'..'ㅣ' }
            val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
            return when {
                hangul == 0 && latin == 0 -> null
                hangul >= latin -> KOREAN
                else -> ENGLISH
            }
        }
    }
}

data class Settings(
    /** Language of whoever is speaking now: Korean is translated to English, English to Korean. */
    val listen: Lang = Lang.KOREAN,
    /** Also record the voice to an audio file while listening (Android 13+). */
    val recordAudio: Boolean = true,
    /** Names, jargon, topic — improves both recognition and translation. */
    val glossary: String = "",
    val onDeviceRecognition: Boolean = false,
    val muteBeeps: Boolean = true,
    /** Chosen microphone: "" = the phone/tablet's built-in mic, otherwise an external mic's key. */
    val micKey: String = "",
    val silenceMs: Int = 800,
    val textScale: Float = 1.0f,
) {
    /**
     * Glossary words sent to the recognizer as hints: the Korean side when listening for
     * Korean, the English side for English. "자율주행 로봇 = AMR" → "자율주행 로봇" / "AMR".
     */
    val biasingTerms: List<String>
        get() = glossary.lines()
            .flatMap { it.split('=') }
            .map { it.trim() }
            .filter { term -> term.isNotEmpty() && term.length <= 40 && Lang.ofText(term) == listen }
            .distinct()
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    init {
        // Drop API keys and options saved by earlier versions that used Claude / OpenAI.
        prefs.edit().apply {
            listOf(
                "engine", "translator", "translatorChosen", "openAiTranslationModel", "anthropicKey",
                "openAiKey", "claudeModel", "sttModel", "sensitivity", "contextSize",
            ).forEach { remove(it) }
        }.apply()
    }

    private fun load() = Settings(
        listen = runCatching { Lang.valueOf(prefs.getString("listen", null) ?: "") }.getOrDefault(Lang.KOREAN),
        recordAudio = prefs.getBoolean("recordAudio", true),
        glossary = prefs.getString("glossary", "") ?: "",
        onDeviceRecognition = prefs.getBoolean("onDeviceRecognition", false),
        muteBeeps = prefs.getBoolean("muteBeeps", true),
        micKey = prefs.getString("micKey", "") ?: "",
        silenceMs = prefs.getInt("silenceMs", 800),
        textScale = prefs.getFloat("textScale", 1.0f),
    )

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        _settings.value = s
        prefs.edit()
            .putString("listen", s.listen.name)
            .putBoolean("recordAudio", s.recordAudio)
            .putString("glossary", s.glossary)
            .putBoolean("onDeviceRecognition", s.onDeviceRecognition)
            .putBoolean("muteBeeps", s.muteBeeps)
            .putString("micKey", s.micKey)
            .putInt("silenceMs", s.silenceMs)
            .putFloat("textScale", s.textScale)
            .apply()
    }
}
