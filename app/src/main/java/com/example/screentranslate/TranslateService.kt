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
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class TranslateService : Service() {
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vDisplay: VirtualDisplay? = null
    private var bubble: TextView? = null
    private var bubbleBg: GradientDrawable? = null
    private val main = Handler(Looper.getMainLooper())
    private var w = 0
    private var h = 0
    private var last: Bitmap? = null
    private var busy = false
    private val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private val overlays = mutableListOf<View>()
    private val overlayRects = mutableListOf<FloatArray>() // top, left, bottom, right (0..1)

    private var auto = false
    private var lastSig: IntArray? = null
    private var prevSig: IntArray? = null
    private var lastCallAt = 0L
    private var lastPeek = 0L
    private val poller = object : Runnable {
        override fun run() {
            if (auto) {
                if (!busy) pollOnce()
                main.postDelayed(this, 1200)
            }
        }
    }

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
            .setContentText("Tap bubble: translate or clear. Hold bubble: auto mode")
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
                last = if (rowPad == 0) full else Bitmap.createBitmap(full, 0, 0, w, h)
            }
        }
        return last
    }

    // Brightness grid of the whole screen. With masked=true, cells under our own overlays are ignored.
    private fun signature(b: Bitmap, masked: Boolean): IntArray {
        val n = 48
        val s = IntArray(n * n)
        for (r in 0 until n) for (c in 0 until n) {
            val fx = (c + 0.5f) / n
            val fy = (r + 0.5f) / n
            if (masked && overlayRects.any { fy >= it[0] && fy <= it[2] && fx >= it[1] && fx <= it[3] }) {
                s[r * n + c] = -1; continue
            }
            var sum = 0
            for (dy in -1..1) for (dx in -1..1) {
                val px = ((fx + dx * 0.25f / n) * b.width).toInt().coerceIn(0, b.width - 1)
                val py = ((fy + dy * 0.25f / n) * b.height).toInt().coerceIn(0, b.height - 1)
                val p = b.getPixel(px, py)
                sum += (Color.red(p) * 3 + Color.green(p) * 6 + Color.blue(p)) / 10
            }
            s[r * n + c] = sum / 9
        }
        return s
    }

    private fun diff(a: IntArray, b: IntArray): Float {
        var n = 0; var m = 0
        for (i in a.indices) {
            if (a[i] < 0 || b[i] < 0) continue
            m++
            if (abs(a[i] - b[i]) > 10) n++
        }
        return if (m == 0) 0f else n.toFloat() / m
    }

    private fun pollOnce() {
        val bmp = grab() ?: return
        val cur = signature(bmp, true)
        val ref = lastSig
        val changed = ref == null || diff(cur, ref) > 0.02f
        val prev = prevSig
        val stable = prev != null && diff(cur, prev) < 0.01f
        prevSig = cur
        val now = SystemClock.uptimeMillis()
        if (now - lastCallAt < 5000) return
        if (changed && stable) translate()
        else if (overlays.isNotEmpty() && now - lastPeek >= 3000) peek()
    }

    // Briefly hide our overlays to check if the text underneath changed.
    private fun peek() {
        busy = true
        lastPeek = SystemClock.uptimeMillis()
        setOverlaysVisible(false)
        bubble?.visibility = View.INVISIBLE
        main.postDelayed({
            val bmp = grab()
            val ref = lastSig
            val changed = bmp != null && ref != null && diff(signature(bmp, false), ref) > 0.02f
            if (changed && bmp != null) {
                startRequest(bmp)
            } else {
                setOverlaysVisible(true)
                bubble?.visibility = View.VISIBLE
                busy = false
            }
        }, 200)
    }

    private fun toggleAuto() {
        auto = !auto
        updateBubble()
        if (auto) {
            lastSig = null; prevSig = null
            main.removeCallbacks(poller)
            main.post(poller)
            toast("Auto translate ON")
        } else {
            main.removeCallbacks(poller)
            clearOverlays()
            toast("Auto translate OFF")
        }
    }

    private fun updateBubble() {
        bubble?.text = if (auto) "A" else "译"
        bubbleBg?.setColor(if (auto) 0xFF2E7D32.toInt() else 0xFFD97757.toInt())
    }

    private fun showBubble() {
        val dp = resources.displayMetrics.density
        val size = (56 * dp).toInt()
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFD97757.toInt()) }
        val v = TextView(this).apply {
            text = "译"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 22f
            background = bg
        }
        bubbleBg = bg
        val lp = WindowManager.LayoutParams(
            size, size, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = h / 3 }
        v.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false; var downAt = 0L
            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false
                        downAt = SystemClock.uptimeMillis()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) moved = true
                        lp.x = ox + dx; lp.y = oy + dy
                        wm.updateViewLayout(view, lp)
                    }
                    MotionEvent.ACTION_UP -> if (!moved) {
                        if (SystemClock.uptimeMillis() - downAt > 600) toggleAuto()
                        else if (overlays.isNotEmpty() && !busy) clearOverlays()
                        else translate()
                    }
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
        setOverlaysVisible(false)
        main.postDelayed({
            val bmp = grab()
            if (bmp == null) {
                bubble?.visibility = View.VISIBLE
                setOverlaysVisible(true)
                busy = false
                toast("No frame captured yet, try again")
                return@postDelayed
            }
            startRequest(bmp)
        }, 250)
    }

    private fun startRequest(bmp: Bitmap) {
        bubble?.visibility = View.VISIBLE
        lastSig = signature(bmp, false)
        prevSig = null
        lastCallAt = SystemClock.uptimeMillis()
        bubble?.text = "…"
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        Thread {
            var err: String? = null
            val blocks: List<ClaudeClient.Block> = try {
                ClaudeClient.translateBoxes(
                    p.getString("key", "") ?: "",
                    p.getString("model", "") ?: "",
                    p.getString("lang", "English") ?: "English",
                    bmp
                )
            } catch (e: Exception) { err = e.message ?: "Error"; emptyList() }
            main.post {
                if (err != null) { clearOverlays(); toast(err!!) } else showOverlays(blocks)
                updateBubble()
                busy = false
                lastCallAt = SystemClock.uptimeMillis()
                lastPeek = lastCallAt
            }
        }.start()
    }

    private fun showOverlays(blocks: List<ClaudeClient.Block>) {
        clearOverlays()
        val dp = resources.displayMetrics.density
        val padX = (4 * dp).toInt()
        val padY = (2 * dp).toInt()
        for (b in blocks) {
            var bw = ((b.right - b.left) * w).toInt() + 2 * padX
            var bh = ((b.bottom - b.top) * h).toInt() + 2 * padY
            bw = bw.coerceIn((40 * dp).toInt(), w)
            bh = bh.coerceIn((22 * dp).toInt(), h)
            val x = ((b.left * w).toInt() - padX).coerceAtMost(w - bw).coerceAtLeast(0)
            val y = ((b.top * h).toInt() - padY).coerceAtMost(h - bh).coerceAtLeast(0)
            val tv = TextView(this).apply {
                text = b.text
                setTextColor(Color.WHITE)
                setBackgroundColor(0xF0101418.toInt())
                gravity = Gravity.CENTER_VERTICAL
                setPadding(padX, 0, padX, 0)
                setAutoSizeTextTypeUniformWithConfiguration(8, 22, 1, TypedValue.COMPLEX_UNIT_SP)
            }
            val lp = WindowManager.LayoutParams(
                bw, bh, overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }
            try {
                wm.addView(tv, lp)
                overlays.add(tv)
                overlayRects.add(floatArrayOf(y.toFloat() / h, x.toFloat() / w, (y + bh).toFloat() / h, (x + bw).toFloat() / w))
            } catch (_: Exception) {}
        }
    }

    private fun setOverlaysVisible(v: Boolean) {
        overlays.forEach { it.visibility = if (v) View.VISIBLE else View.INVISIBLE }
    }

    private fun clearOverlays() {
        overlays.forEach { try { wm.removeView(it) } catch (_: Exception) {} }
        overlays.clear()
        overlayRects.clear()
    }

    private fun toast(s: String) = Toast.makeText(this, s.take(200), Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        auto = false
        main.removeCallbacks(poller)
        clearOverlays()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        vDisplay?.release()
        reader?.close()
        projection?.stop()
        super.onDestroy()
    }
}= LinearLayout(this).apply {
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
        auto = false
        main.removeCallbacks(poller)
        hidePanel()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        vDisplay?.release()
        reader?.close()
        projection?.stop()
        super.onDestroy()
    }
}
