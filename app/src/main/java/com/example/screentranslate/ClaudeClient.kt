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
    data class Block(val text: String, val top: Float, val left: Float, val bottom: Float, val right: Float)

    private val http = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    fun translateBoxes(apiKey: String, model: String, language: String, shot: Bitmap): List<Block> {
        val useModel = if (model.isBlank() || model.startsWith("claude")) "gemini-3.5-flash" else model

        val maxEdge = 1568
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(shot.width, shot.height))
        val bmp = if (scale < 1f)
            Bitmap.createScaledBitmap(shot, (shot.width * scale).toInt(), (shot.height * scale).toInt(), true)
        else shot
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

        val prompt = "Find the meaningful text in this screenshot (dialogue, subtitles, menus, buttons, chat, articles). " +
            "Skip the status bar and any text that is already in $language. " +
            "Return ONLY a JSON array. Each item: {\"box_2d\":[ymin,xmin,ymax,xmax],\"text\":\"<translation into $language>\"}. " +
            "box_2d is a tight box around the ORIGINAL text, normalized to 0-1000. " +
            "Group each sentence, paragraph or button label as one item; do not split one sentence into pieces. " +
            "If there is nothing to translate, return []."

        val reqParts = JSONArray()
            .put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "image/jpeg").put("data", b64)))
            .put(JSONObject().put("text", prompt))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", reqParts)))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$useModel:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("API error ${resp.code}: ${txt.take(300)}")
            val cands = JSONObject(txt).optJSONArray("candidates")
                ?: throw RuntimeException("No result: ${txt.take(300)}")
            val outParts = cands.getJSONObject(0).optJSONObject("content")
                ?.optJSONArray("parts") ?: throw RuntimeException("No text returned")
            val sb = StringBuilder()
            for (i in 0 until outParts.length()) sb.append(outParts.getJSONObject(i).optString("text"))
            val clean = sb.toString().replace("```json", "").replace("```", "").trim()
            val arr = try { JSONArray(clean) } catch (e: Exception) {
                throw RuntimeException("Could not read result: ${clean.take(200)}")
            }
            val blocks = ArrayList<Block>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val bx = o.optJSONArray("box_2d") ?: continue
                if (bx.length() < 4) continue
                val t = o.optString("text").trim()
                if (t.isEmpty()) continue
                blocks.add(Block(
                    t,
                    (bx.getDouble(0) / 1000.0).toFloat(), (bx.getDouble(1) / 1000.0).toFloat(),
                    (bx.getDouble(2) / 1000.0).toFloat(), (bx.getDouble(3) / 1000.0).toFloat()
                ))
            }
            return blocks
        }
    }
}
