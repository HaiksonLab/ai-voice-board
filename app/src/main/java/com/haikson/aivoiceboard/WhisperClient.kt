package com.haikson.aivoiceboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

// Analogous to WhisperTranscribe() in the AHK script.
class WhisperClient(private val prefs: PrefsManager) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .apply {
                val rawProxy = prefs.proxy
                if (rawProxy.isNotEmpty()) {
                    runCatching {
                        val uri = URI(rawProxy)
                        proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(uri.host, uri.port)))
                    }
                }
            }
            .build()
    }

    // Returns transcribed text on success, or throws on network / API error.
    suspend fun transcribe(wavFile: File): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", wavFile.name,
                wavFile.asRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", prefs.model)
            .addFormDataPart("response_format", "text")
            .apply {
                if (prefs.prompt.isNotEmpty())
                    addFormDataPart("prompt", prefs.prompt)
            }
            .build()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .header("Authorization", "Bearer ${prefs.apiKey}")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()?.trim() ?: ""

        if (!response.isSuccessful) {
            throw Exception("API ${response.code}: $responseBody")
        }

        responseBody
    }
}
