package com.nars.kointerpreter.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
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
     * Korean words from the glossary, sent to the built-in recognizer as hints.
     * "자율주행 로봇 = AMR" → "자율주행 로봇"; a plain line is used as-is.
     */
    val biasingTerms: List<String>
        get() = glossary.lines()
            .map { it.substringBefore('=').trim() }
            .filter { line -> line.isNotEmpty() && line.length <= 40 && line.any { it in '가'..'힣' } }
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
            .putString("glossary", s.glossary)
            .putBoolean("onDeviceRecognition", s.onDeviceRecognition)
            .putBoolean("muteBeeps", s.muteBeeps)
            .putString("micKey", s.micKey)
            .putInt("silenceMs", s.silenceMs)
            .putFloat("textScale", s.textScale)
            .apply()
    }
}
