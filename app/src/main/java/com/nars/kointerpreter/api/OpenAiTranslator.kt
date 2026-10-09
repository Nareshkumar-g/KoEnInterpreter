package com.nars.kointerpreter.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Korean → English translation with OpenAI's Chat Completions API, streamed token by
 * token. Uses the same interpreter instructions as the Claude translator, so the two
 * produce the same style of result.
 */
class OpenAiTranslator(
    private val apiKey: String,
    private val model: String,
    private val glossary: String,
) : TranslationEngine {

    override suspend fun translate(
        korean: String,
        context: List<Turn>,
        onPartial: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("content-type", "application/json")
            .post(buildBody(korean, context).toString().toRequestBody("application/json".toMediaType()))
            .build()

        val out = StringBuilder()
        Http.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Http.failure("OpenAI", resp)
            val source = resp.body!!.source()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                json.optJSONObject("error")?.let { err ->
                    throw ApiException("OpenAI: " + err.optString("message", "stream error"))
                }
                val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                if (!delta.isNull("content")) {
                    val piece = delta.optString("content")
                    if (piece.isNotEmpty()) {
                        out.append(piece)
                        onPartial(out.toString().trimStart())
                    }
                }
            }
        }
        out.toString().trim()
    }

    private fun buildBody(korean: String, context: List<Turn>): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("max_completion_tokens", 4000) // includes any reasoning tokens
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "developer").put("content", Translator.systemPrompt(glossary)))
                    .put(JSONObject().put("role", "user").put("content", Translator.userPrompt(korean, context)))
            )
        // GPT-5/6 and o-series are reasoning models: keep reasoning short for live interpreting.
        if (model.startsWith("gpt-5") || model.startsWith("gpt-6") || model.matches(Regex("^o\\d.*"))) {
            body.put("reasoning_effort", "low")
        }
        return body
    }
}
