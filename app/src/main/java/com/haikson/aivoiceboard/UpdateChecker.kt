package com.haikson.aivoiceboard

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class UpdateInfo(val version: String, val apkUrl: String, val releaseUrl: String)

// Checks GitHub Releases for a newer version over a direct connection (no proxy).
class UpdateChecker {

    companion object {
        const val REPO_URL = "https://github.com/HaiksonLab/ai-voice-board"
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/HaiksonLab/ai-voice-board/releases/latest"

        // true if [latest] is a strictly higher semantic version than [current].
        fun isNewer(latest: String, current: String): Boolean {
            val a = parse(latest); val b = parse(current)
            val n = maxOf(a.size, b.size)
            for (i in 0 until n) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        private fun parse(v: String): List<Int> =
            v.trim().removePrefix("v").split(".", "-")
                .mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
    }

    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    // Fetches the latest release; throws on network/parse error.
    fun fetchLatest(): UpdateInfo {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "")
            if (tag.isEmpty()) throw Exception("No tag_name in response")
            val releaseUrl = json.optString("html_url", "")
            var apkUrl = ""
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name", "").endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url", "")
                        break
                    }
                }
            }
            return UpdateInfo(tag.removePrefix("v"), apkUrl, releaseUrl)
        }
    }
}
