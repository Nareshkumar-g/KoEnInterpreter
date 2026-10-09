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

/** One earlier exchange, sent as context so pronouns, names and topic carry over. */
data class Turn(val korean: String, val english: String)

/** Anything that can translate one Korean utterance to English, streaming as it goes. */
interface TranslationEngine {
    suspend fun translate(korean: String, context: List<Turn>, onPartial: (String) -> Unit): String
}

/** Korean → English translation with the Claude Messages API, streamed token by token. */
class Translator(
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
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(buildBody(korean, context).toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = Http.client.newCall(request)
        val out = StringBuilder()
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw Http.failure("Claude", resp)
            val source = resp.body!!.source()
            // Server-sent events: we only need the "data:" lines.
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val json = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() ?: continue
                when (json.optString("type")) {
                    "content_block_delta" -> {
                        val delta = json.optJSONObject("delta") ?: continue
                        if (delta.optString("type") == "text_delta") {
                            out.append(delta.optString("text"))
                            onPartial(out.toString().trimStart())
                        }
                    }
                    "error" -> throw ApiException(
                        "Claude: " + (json.optJSONObject("error")?.optString("message") ?: "stream error")
                    )
                    "message_stop" -> break
                }
            }
        }
        out.toString().trim()
    }

    internal fun buildBody(korean: String, context: List<Turn>): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", if (model.contains("opus")) 4000 else 1500)
            .put("stream", true)
            .put("system", systemPrompt(glossary))
            .put(
                "messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", userPrompt(korean, context))
                )
            )
        // Keep latency low for live interpreting. Settings per the Claude docs:
        //  - Sonnet 5.5: no up-front thinking ("between_tools") at low effort.
        //  - Opus 5.5: thinking can't be disabled; low effort keeps it brief.
        //  - Haiku 4.5: no effort parameter; plain request.
        when {
            model.startsWith("claude-sonnet-5-5") -> {
                body.put("output_config", JSONObject().put("effort", "low"))
                body.put("thinking", JSONObject().put("type", "between_tools"))
            }
            model.startsWith("claude-opus-5-5") -> {
                body.put("output_config", JSONObject().put("effort", "low"))
            }
        }
        return body
    }

    companion object {
        fun systemPrompt(glossary: String): String = buildString {
            append(
                """
                You are an expert Korean-to-English interpreter working live. You receive Korean text produced by automatic speech recognition of someone speaking, and you render it into natural, fluent, accurate English as a skilled professional interpreter would.

                Rules:
                - Output ONLY the English translation. No quotes, labels, notes, romanization or explanations.
                - Convey meaning, intent and tone, not word-for-word structure. Express Korean politeness levels and honorifics through natural English register (e.g. formal vs casual), not literal words like "honorable".
                - Korean often omits the subject. Infer it from context and use the natural English pronoun; if genuinely unclear, choose the most likely reading.
                - The speech recognition may contain errors: wrong homophones, missing particles, mis-split sentences. Use the conversation context to work out what was actually said, and translate the intended meaning.
                - Keep names, numbers, dates, units, money and technical terms exact. Romanize Korean personal names in standard form (e.g. Kim Min-jun).
                - Translate idioms and set phrases to their natural English equivalents.
                - If the utterance is an incomplete fragment, translate it as a fragment. Never add content that was not said.
                - If it is only a filler or backchannel (네, 음, 아, 그러니까), give the natural short English equivalent ("Yes.", "Um.", "I see.", "So...").
                """.trimIndent()
            )
            if (glossary.isNotBlank()) {
                append("\n\nContext and glossary provided by the user (use these terms and spellings):\n")
                append(glossary.trim())
            }
        }

        fun userPrompt(korean: String, context: List<Turn>): String = buildString {
            if (context.isNotEmpty()) {
                append("Earlier in this conversation (context only, do not translate again):\n")
                for (t in context) {
                    append("KO: ").append(t.korean).append('\n')
                    append("EN: ").append(t.english).append('\n')
                }
                append('\n')
            }
            append("Translate this new utterance:\n<korean>\n")
            append(korean.trim())
            append("\n</korean>")
        }
    }
}
