package com.nars.kointerpreter.data

import android.content.Context
import com.nars.kointerpreter.api.KeyKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where Korean speech is turned into text. */
enum class Engine {
    /** Android's built-in recognizer: free, live text, great for everyday use. */
    BUILT_IN,
    /** OpenAI transcription: more robust in noisy places (factory floor, street, crowds). */
    OPENAI,
}

/** Who translates the Korean text into English. */
enum class TranslationProvider {
    /** Claude (Anthropic): most natural, context-aware translation. */
    CLAUDE,
    /** OpenAI GPT: one OpenAI key covers both recognition and translation. */
    OPENAI,
    /** Google ML Kit on the phone: free, no key, works offline; more literal, no context. */
    FREE,
}

data class Settings(
    val engine: Engine = Engine.BUILT_IN,
    val translator: TranslationProvider = TranslationProvider.CLAUDE,
    /** False until the user has picked a translator in the first-run setup. */
    val translatorChosen: Boolean = false,
    val openAiTranslationModel: String = DEFAULT_OPENAI_TRANSLATION_MODEL,
    val anthropicKey: String = "",
    val openAiKey: String = "",
    val claudeModel: String = DEFAULT_CLAUDE_MODEL,
    val sttModel: String = DEFAULT_STT_MODEL,
    /** Names, jargon, topic — improves both recognition and translation. */
    val glossary: String = "",
    // Everyday (built-in) options
    val onDeviceRecognition: Boolean = false,
    val muteBeeps: Boolean = true,
    // Noisy-place (OpenAI) options
    val sensitivity: Float = 3.0f,
    /** Chosen microphone: "" = the phone/tablet's built-in mic, otherwise an external mic's key. */
    val micKey: String = "",
    // Shared
    val silenceMs: Int = 800,
    val contextSize: Int = 4,
    val textScale: Float = 1.0f,
) {
    /**
     * API keys still missing for this recognizer + translator combination, in the order
     * the setup sheet asks for them (OpenAI first, then Anthropic).
     */
    fun keysNeeded(recognizer: Engine, provider: TranslationProvider = translator): List<KeyKind> = buildList {
        val needsOpenAi = recognizer == Engine.OPENAI || provider == TranslationProvider.OPENAI
        if (needsOpenAi && openAiKey.isBlank()) add(KeyKind.OPENAI)
        if (provider == TranslationProvider.CLAUDE && anthropicKey.isBlank()) add(KeyKind.ANTHROPIC)
    }

    /** "Claude (claude-sonnet-5-5)" — for transcripts and labels. */
    val translationLabel: String
        get() = when (translator) {
            TranslationProvider.CLAUDE -> "Claude ($claudeModel)"
            TranslationProvider.OPENAI -> "OpenAI ($openAiTranslationModel)"
            TranslationProvider.FREE -> "Free translator (Google ML Kit, on-device)"
        }

    /**
     * Korean words from the glossary, sent to the built-in recognizer as hints.
     * "자율주행 로봇 = AMR" → "자율주행 로봇"; a plain line is used as-is.
     */
    val biasingTerms: List<String>
        get() = glossary.lines()
            .map { it.substringBefore('=').trim() }
            .filter { line -> line.isNotEmpty() && line.length <= 40 && line.any { it in '가'..'힣' } }

    companion object {
        const val DEFAULT_CLAUDE_MODEL = "claude-sonnet-5-5"
        const val DEFAULT_STT_MODEL = "gpt-4o-transcribe"
        const val DEFAULT_OPENAI_TRANSLATION_MODEL = "gpt-6.1-sol"
        val OPENAI_TRANSLATION_MODELS = listOf("gpt-6.1-sol", "gpt-6-astra", "gpt-6-luna")
        val CLAUDE_MODELS = listOf("claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-4-5-20251001")
        val STT_MODELS = listOf("gpt-4o-transcribe", "gpt-4o-mini-transcribe", "whisper-1")
    }
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private fun load() = Settings(
        engine = runCatching { Engine.valueOf(prefs.getString("engine", null) ?: "") }.getOrDefault(Engine.BUILT_IN),
        translator = runCatching { TranslationProvider.valueOf(prefs.getString("translator", null) ?: "") }
            .getOrDefault(TranslationProvider.CLAUDE),
        // People who set up an Anthropic key before this option existed count as having chosen Claude.
        translatorChosen = prefs.getBoolean("translatorChosen", !prefs.getString("anthropicKey", "").isNullOrBlank()),
        openAiTranslationModel = prefs.getString("openAiTranslationModel", null) ?: Settings.DEFAULT_OPENAI_TRANSLATION_MODEL,
        anthropicKey = prefs.getString("anthropicKey", "") ?: "",
        openAiKey = prefs.getString("openAiKey", "") ?: "",
        claudeModel = prefs.getString("claudeModel", null) ?: Settings.DEFAULT_CLAUDE_MODEL,
        sttModel = prefs.getString("sttModel", null) ?: Settings.DEFAULT_STT_MODEL,
        glossary = prefs.getString("glossary", "") ?: "",
        onDeviceRecognition = prefs.getBoolean("onDeviceRecognition", false),
        muteBeeps = prefs.getBoolean("muteBeeps", true),
        sensitivity = prefs.getFloat("sensitivity", 3.0f),
        micKey = prefs.getString("micKey", "") ?: "",
        silenceMs = prefs.getInt("silenceMs", 800),
        contextSize = prefs.getInt("contextSize", 4),
        textScale = prefs.getFloat("textScale", 1.0f),
    )

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        _settings.value = s
        prefs.edit()
            .putString("engine", s.engine.name)
            .putString("translator", s.translator.name)
            .putBoolean("translatorChosen", s.translatorChosen)
            .putString("openAiTranslationModel", s.openAiTranslationModel.trim())
            .putString("anthropicKey", s.anthropicKey.trim())
            .putString("openAiKey", s.openAiKey.trim())
            .putString("claudeModel", s.claudeModel.trim())
            .putString("sttModel", s.sttModel.trim())
            .putString("glossary", s.glossary)
            .putBoolean("onDeviceRecognition", s.onDeviceRecognition)
            .putBoolean("muteBeeps", s.muteBeeps)
            .putFloat("sensitivity", s.sensitivity)
            .putString("micKey", s.micKey)
            .putInt("silenceMs", s.silenceMs)
            .putInt("contextSize", s.contextSize)
            .putFloat("textScale", s.textScale)
            .apply()
    }
}
