package com.nars.kointerpreter.api

import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String) : IOException(message)

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Turns a non-2xx response into a readable error ("OpenAI 401: Incorrect API key"). */
    fun failure(service: String, response: Response): ApiException {
        val raw = response.body?.string().orEmpty()
        val msg = runCatching {
            val err = JSONObject(raw).optJSONObject("error")
            err?.optString("message")?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: raw.take(200).ifBlank { response.message }
        return ApiException("$service ${response.code}: $msg")
    }
}
