package com.nars.kointerpreter.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

enum class KeyKind { OPENAI, ANTHROPIC }

sealed interface KeyCheck {
    /** The provider accepted the key. */
    data object Valid : KeyCheck
    /** The provider rejected the key (wrong, revoked, or copied incompletely). */
    data class Rejected(val message: String) : KeyCheck
    /** Couldn't reach the provider (offline, timeout); the key may still be fine. */
    data object Unverified : KeyCheck
}

/** Checks an API key with a free, read-only "list models" call. */
object KeyChecker {

    suspend fun check(kind: KeyKind, key: String): KeyCheck = withContext(Dispatchers.IO) {
        val request = when (kind) {
            KeyKind.OPENAI -> Request.Builder()
                .url("https://api.openai.com/v1/models")
                .header("Authorization", "Bearer $key")
            KeyKind.ANTHROPIC -> Request.Builder()
                .url("https://api.anthropic.com/v1/models")
                .header("x-api-key", key)
                .header("anthropic-version", "2023-06-01")
        }.get().build()

        try {
            Http.client.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> KeyCheck.Valid
                    resp.code == 401 || resp.code == 403 ->
                        KeyCheck.Rejected("This key was rejected. Check that you copied all of it.")
                    else -> KeyCheck.Unverified
                }
            }
        } catch (e: Exception) {
            KeyCheck.Unverified
        }
    }

    /** Light format hint before sending anything. Returns a warning, or null if it looks right. */
    fun formatWarning(kind: KeyKind, key: String): String? = when {
        key.isBlank() -> "Paste your key first."
        kind == KeyKind.ANTHROPIC && !key.startsWith("sk-ant-") ->
            "Anthropic keys usually start with \"sk-ant-\"."
        kind == KeyKind.OPENAI && key.startsWith("sk-ant-") ->
            "That looks like an Anthropic key. This step needs your OpenAI key."
        kind == KeyKind.OPENAI && !key.startsWith("sk-") ->
            "OpenAI keys usually start with \"sk-\"."
        else -> null
    }
}
