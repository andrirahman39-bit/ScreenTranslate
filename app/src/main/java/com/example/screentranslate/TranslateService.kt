package com.example.screentranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class TranslateService : Service() {
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vDisplay: VirtualDisplay? = null
    private var bubble: TextView? = null
    private var panel: View? = null
    private val main = Handler(Looper.getMainLooper())
    private var w = 0
    private var h = 0
    private var last: Bitmap? = null
    private var busy = false
    private val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val code = intent.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, main)
        }
        setupCapture()
        showBubble()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("st", "Screen Translate", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "st")
            .setContentTitle("Screen Translate is running")
            .setContentText("Tap the floating bubble to translate")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun setupCapture() {
        val b = wm.currentWindowMetrics.bounds
        w = b.width(); h = b.height()
        val dpi = resources.displayMetrics.densityDpi
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        vDisplay = projection!!.createVirtualDisplay(
            "st", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, null
        )
    }

    private fun grab(): Bitmap? {
        val img = reader?.acquireLatestImage()
        if (img != null) {
            img.use {
                val pl = it.planes[0]
                val rowPad = pl.rowStride - pl.pixelStride * w
                val full = Bitmap.createBitmap(w + rowPad / pl.pixelStride, h, Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(pl.buffer)
                last = Bitmap.createBitmap(full, 0, 0, w, h)
            }
        }
        return last
    }

    private fun showBubble() {
        val dp = resources.displayMetrics.density
        val size = (56 * dp).toInt()
        val v = TextView(this).apply {
            text = "译"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 22f
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFD97757.toInt()) }
        }
        val lp = WindowManager.LayoutParams(
            size, size, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = h / 3 }
        v.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) moved = true
                        lp.x = ox + dx; lp.y = oy + dy
                        wm.updateViewLayout(view, lp)
                    }
                    MotionEvent.ACTION_UP -> if (!moved) translate()
                }
                return true
            }
        })
        wm.addView(v, lp)
        bubble = v
    }

    private fun translate() {
        if (busy) return
        busy = true
        bubble?.visibility = View.INVISIBLE
        hidePanel()
        main.postDelayed({
            val bmp = grab()
            bubble?.visibility = View.VISIBLE
            if (bmp == null) {
                busy = false
                Toast.makeText(this, "No frame captured yet, try again", Toast.LENGTH_SHORT).show()
                return@postDelayed
            }
            bubble?.text = "…"
            val p = getSharedPreferences("cfg", MODE_PRIVATE)
            Thread {
                val result = try {
                    ClaudeClient.translate(
                        p.getString("key", "") ?: "",
                        p.getString("model", "claude-haiku-5-5") ?: "claude-haiku-5-5",
                        p.getString("lang", "English") ?: "English",
                        bmp
                    )
                } catch (e: Exception) { "Error: ${e.message}" }
                main.post { bubble?.text = "译"; showPanel(result); busy = false }
            }.start()
        }, 200)
    }

    private fun showPanel(text: String) {
        hidePanel()
        val dp = resources.displayMetrics.density
        val tv = TextView(this).apply {
            this.text = text; setTextColor(Color.WHITE); textSize = 16f
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            setTextIsSelectable(true)
        }
        val close = Button(this).apply { this.text = "Close"; setOnClickListener { hidePanel() } }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0111827.toInt())
            addView(ScrollView(context).apply { addView(tv) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(close, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val lp = WindowManager.LayoutParams(
            MATCH_PARENT, (h * 0.45).toInt(), overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        wm.addView(box, lp)
        panel = box
    }

    private fun hidePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
    }

    override fun onDestroy() {
        hidePanel()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        vDisplay?.release()
        reader?.close()
        projection?.stop()
        super.onDestroy()
    }
}
