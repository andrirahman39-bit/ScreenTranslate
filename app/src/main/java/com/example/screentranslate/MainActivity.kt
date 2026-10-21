package com.example.screentranslate

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private lateinit var key: EditText
    private lateinit var lang: EditText
    private lateinit var model: EditText
    private val reqCapture = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        key = EditText(this).apply {
            hint = "Anthropic API key (sk-ant-...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(p.getString("key", ""))
        }
        lang = EditText(this).apply { hint = "Translate into"; setText(p.getString("lang", "English")) }
        model = EditText(this).apply { hint = "Model"; setText(p.getString("model", "claude-haiku-5-5")) }
        val start = Button(this).apply {
            text = "Start floating translator"
            setOnClickListener { save(); begin() }
        }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener { stopService(Intent(this@MainActivity, TranslateService::class.java)) }
        }
        val help = TextView(this).apply {
            text = "Tap the floating 译 bubble over any app to translate what's on screen. Drag it to move."
        }
        listOf(key, lang, model, start, stop, help).forEach { root.addView(it) }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun save() {
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
            .putString("key", key.text.toString().trim())
            .putString("lang", lang.text.toString().trim().ifEmpty { "English" })
            .putString("model", model.text.toString().trim().ifEmpty { "claude-haiku-5-5" })
            .apply()
    }

    private fun begin() {
        if (key.text.isBlank()) { toast("Enter your API key first"); return }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            toast("Allow 'Display over other apps', then tap Start again")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, TranslateService::class.java)
                .putExtra("code", resultCode).putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
