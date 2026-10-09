package com.nars.kointerpreter.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Korean speech-to-text through OpenAI's transcription endpoint. */
class SpeechToText(private val apiKey: String, private val model: String) {

    /**
     * @param prompt previous Korean sentences + glossary. The model uses it to keep
     *        names and terms consistent and to follow the flow of conversation.
     */
    suspend fun transcribe(wav: ByteArray, prompt: String): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("language", "ko")
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .apply { if (prompt.isNotBlank()) addFormDataPart("prompt", prompt.takeLast(800)) }
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        Http.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Http.failure("OpenAI", resp)
            JSONObject(resp.body!!.string()).optString("text").trim()
        }
    }

    companion object {
        /**
         * Whisper-family models sometimes "hear" YouTube outro phrases in silence or
         * noise. Drop short results that are only these.
         */
        private val HALLUCINATION_MARKERS = listOf(
            "시청해", "시청 해", "구독과 좋아요", "구독 좋아요", "좋아요와 구독",
            "자막 제공", "자막 by", "다음 영상에서", "MBC 뉴스", "KBS 뉴스",
        )

        fun looksLikeHallucination(text: String): Boolean {
            val t = text.trim()
            if (t.isEmpty()) return true
            if (t.length > 40) return false
            return HALLUCINATION_MARKERS.any { t.contains(it, ignoreCase = true) }
        }
    }
}
