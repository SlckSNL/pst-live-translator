package com.pst.livetranslator

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Sends finalized English text to a user-controlled HTTPS gateway. Never embeds provider keys. */
class ContextTranslator {
    private val main = Handler(Looper.getMainLooper())
    fun translate(endpoint: String, token: String, text: String,
                  previousEnglish: List<String>, previousTurkish: List<String>,
                  callback: (String?) -> Unit) {
        if (!endpoint.startsWith("https://", ignoreCase = true)) {
            main.post { callback(null) }
            return
        }
        thread(name = "pst-context-translation") {
            var translation: String? = null
            try {
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 4000
                    connection.readTimeout = 9000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
                    val body = JSONObject()
                        .put("text", text)
                        .put("previous_english", JSONArray(previousEnglish))
                        .put("previous_turkish", JSONArray(previousTurkish))
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    if (connection.responseCode in 200..299) {
                        translation = JSONObject(connection.inputStream.bufferedReader().use {
                            it.readText()
                        }).optString("translation").trim().takeIf { it.isNotEmpty() }
                    }
                } finally { connection.disconnect() }
            } catch (_: Exception) { }
            val result = translation
            main.post { callback(result) }
        }
    }
}
