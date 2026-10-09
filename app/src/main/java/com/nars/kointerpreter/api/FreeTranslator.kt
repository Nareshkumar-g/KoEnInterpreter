package com.nars.kointerpreter.api

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.nars.kointerpreter.data.Lang
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TranslationException(message: String) : IOException(message)

/**
 * Free Korean ⇄ English translation with Google's ML Kit, entirely on the phone:
 * no account, no API key, no cost, and it works offline once the language model
 * (about 30 MB) has been downloaded.
 *
 * It translates each sentence on its own (it can't use the conversation as context).
 */
object FreeTranslator {

    enum class ModelState { UNKNOWN, NOT_DOWNLOADED, DOWNLOADING, READY, FAILED }

    private val _state = MutableStateFlow(ModelState.UNKNOWN)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private fun client(from: String, to: String) = Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build()
    )
    private val koreanToEnglish by lazy { client(TranslateLanguage.KOREAN, TranslateLanguage.ENGLISH) }
    private val englishToKorean by lazy { client(TranslateLanguage.ENGLISH, TranslateLanguage.KOREAN) }
    private val mutex = Mutex()

    /** Checks whether the Korean model is already on the phone. */
    suspend fun refreshState() {
        if (_state.value == ModelState.DOWNLOADING) return
        val downloaded = runCatching {
            RemoteModelManager.getInstance()
                .isModelDownloaded(TranslateRemoteModel.Builder(TranslateLanguage.KOREAN).build())
                .await()
        }.getOrDefault(false)
        _state.value = if (downloaded) ModelState.READY else ModelState.NOT_DOWNLOADED
    }

    /** Downloads the model if needed (Wi-Fi or mobile data). Returns true when ready. */
    suspend fun ensureModel(): Boolean = mutex.withLock {
        if (_state.value == ModelState.READY) return@withLock true
        _state.value = ModelState.DOWNLOADING
        // Both directions use the same Korean model (English is built in).
        val ok = runCatching {
            koreanToEnglish.downloadModelIfNeeded().await()
            englishToKorean.downloadModelIfNeeded().await()
        }.isSuccess
        _state.value = if (ok) ModelState.READY else ModelState.FAILED
        ok
    }

    /**
     * Translates one sentence from [from] into the other language, swapping in the
     * glossary's "Korean = English" terms first.
     */
    suspend fun translate(text: String, from: Lang, glossary: String): String {
        if (!ensureModel()) {
            throw TranslationException("Couldn't download the translator. Check your internet connection, then tap Retry.")
        }
        return when (from) {
            Lang.KOREAN -> koreanToEnglish.translate(Glossary.apply(text, glossary))
            Lang.ENGLISH -> englishToKorean.translate(Glossary.applyReverse(text, glossary))
        }.await().trim()
    }
}

/**
 * Applies "Korean = English" lines from the glossary before translating, so names
 * and jargon come out the way the user wants even with the basic translator.
 */
object Glossary {
    fun pairs(glossary: String): List<Pair<String, String>> = glossary.lines()
        .mapNotNull { line ->
            val parts = line.split('=', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val ko = parts[0].trim()
            val en = parts[1].trim()
            if (ko.isEmpty() || en.isEmpty() || ko.none { it in '가'..'힣' }) null else ko to en
        }
        .sortedByDescending { it.first.length } // longest first, so "자율주행 로봇" wins over "로봇"

    fun apply(korean: String, glossary: String): String =
        pairs(glossary).fold(korean) { text, (ko, en) -> text.replace(ko, en) }

    /** English → Korean direction: "AMR" in English speech becomes "자율주행 로봇" before translating. */
    fun applyReverse(english: String, glossary: String): String =
        pairs(glossary)
            .sortedByDescending { it.second.length }
            .fold(english) { text, (ko, en) ->
                text.replace(Regex("(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(en) + "(?![\\p{L}\\p{N}])"), ko)
            }
}

/** Minimal Task → coroutine bridge (avoids pulling in another library). */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
