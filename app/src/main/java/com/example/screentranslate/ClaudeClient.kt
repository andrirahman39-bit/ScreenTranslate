package com.example.screentranslate

import android.graphics.Bitmap
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object ClaudeClient {
    private val http = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    fun translate(apiKey: String, model: String, language: String, shot: Bitmap): String {
        val useModel = if (model.isBlank() || model.startsWith("claude")) "gemini-3.5-flash" else model

        val maxEdge = 1568
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(shot.width, shot.height))
        val bmp = if (scale < 1f)
            Bitmap.createScaledBitmap(shot, (shot.width * scale).toInt(), (shot.height * scale).toInt(), true)
        else shot
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

        val prompt = "Translate all meaningful text visible in this screenshot into $language. " +
            "Keep the reading order and rough grouping (chat bubbles, menu items, paragraphs). " +
            "Ignore status bar items like time, battery and signal. " +
            "Output only the translation, no commentary."

        val reqParts = JSONArray()
            .put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "image/jpeg").put("data", b64)))
            .put(JSONObject().put("text", prompt))
        val body = JSONObject().put("contents",
            JSONArray().put(JSONObject().put("parts", reqParts)))

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$useModel:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return "API error ${resp.code}: $txt"
            val cands = JSONObject(txt).optJSONArray("candidates")
                ?: return "No result: $txt"
            val outParts = cands.getJSONObject(0).optJSONObject("content")
                ?.optJSONArray("parts") ?: return "No text returned: $txt"
            val sb = StringBuilder()
            for (i in 0 until outParts.length()) {
                sb.append(outParts.getJSONObject(i).optString("text"))
            }
            return sb.toString().trim()
        }
    }
}
