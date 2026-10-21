package com.example.screentranslate // <- change to your package

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object GeminiClient {
    // If this model name stops working, check Google's current free-tier model list.
    private const val MODEL = "gemini-2.5-flash"
    private val cache = ConcurrentHashMap<String, String>()

    /** Blocking: call from a background thread. Returns translations in the same order as [texts]. */
    fun translate(apiKey: String, texts: List<String>): List<String> {
        if (cache.size > 3000) cache.clear()
        val missing = texts.filter { !cache.containsKey(it) }.distinct()
        if (missing.isNotEmpty()) {
            val result = request(apiKey, missing)
            missing.forEachIndexed { i, src -> cache[src] = result.getOrElse(i) { src } }
        }
        return texts.map { cache[it] ?: it }
    }

    private fun request(apiKey: String, texts: List<String>): List<String> {
        val prompt = "Translate each Chinese game UI / dialogue string in this JSON array " +
            "into natural, short English. Keep names consistent. " +
            "Return ONLY a JSON array of strings with the same length and order.\n" +
            JSONArray(texts).toString()

        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$apiKey")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.readText()
            throw RuntimeException("Gemini ${conn.responseCode}: $err")
        }
        val resp = conn.inputStream.bufferedReader().readText()
        val text = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        val arr = JSONArray(text)
        return List(arr.length()) { arr.getString(it) }
    }
}
