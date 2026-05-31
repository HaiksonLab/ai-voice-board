package com.haikson.aivoiceboard

import android.content.Context

// Analogous to config.ini in the Windows AHK version.
class PrefsManager(context: Context) {

    private val prefs = context.getSharedPreferences(
        "${context.packageName}_preferences", Context.MODE_PRIVATE
    )

    val apiKey: String get() = prefs.getString("api_key", DEFAULT_API_KEY) ?: DEFAULT_API_KEY
    val model: String get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
    val prompt: String get() = prefs.getString("prompt", DEFAULT_PROMPT) ?: DEFAULT_PROMPT
    val proxy: String get() = prefs.getString("proxy", DEFAULT_PROXY) ?: DEFAULT_PROXY
    val fmtEnable: Boolean get() = prefs.getBoolean("fmt_enable", true)

    companion object {
        const val DEFAULT_API_KEY = ""
        const val DEFAULT_MODEL  = "gpt-4o-transcribe"
        const val DEFAULT_PROXY  = ""
        const val DEFAULT_PROMPT = "Техническое обсуждение на русском и английском. Возможны разговорная речь, смешение русского и английского, программирование, Android, embedded, веб-разработка, сети и электроника. Термины: CLAUDE.md, API, REST API, JSON, HTTP, HTTPS, WebSocket, TCP, UDP, MQTT, UART, SPI, I2C, BLE, Bluetooth, Wi-Fi, GPS, ESP32, ESP8266, PlatformIO, Arduino, Kotlin, Java, C++, JavaScript, TypeScript, Node.js, Express, Nginx, Redis, BullMQ, PostgreSQL, MySQL, MariaDB, Prisma, Docker, Linux, Android, protobuf, AES, CTR, nonce, firmware, backend, frontend, mesh, LoRa, Meshtastic, Voron. Числа и технические значения по возможности записываются цифрами."
    }
}
