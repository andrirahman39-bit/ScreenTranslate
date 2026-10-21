package com.example.screentranslate // <- change to your package

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.Executors

class TranslateService : Service() {

    companion object {
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val ACTION_STOP = "stop"
        private const val CHANNEL = "translate"
        private const val TAG = "TranslateService"
        private val CJK = Regex("[\\u4e00-\\u9fff]")

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, TranslateService::class.java)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var overlay: OverlayView? = null
    private var running = false
    private var busy = false
    private val intervalMs = 1500L

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running || intent == null) return START_NOT_STICKY

        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, notif)

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA) ?: return START_NOT_STICKY

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        // Required on Android 14+ before creating the virtual display
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm) // start while the game is already in landscape
        reader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2)
        display = projection!!.createVirtualDisplay(
            "screen-translate", dm.widthPixels, dm.heightPixels, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null
        )

        overlay = OverlayView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28)
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(overlay, lp)

        running = true
        main.postDelayed(tick, 1000)
        return START_NOT_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (!busy) capture()
            main.postDelayed(this, intervalMs)
        }
    }

    private fun capture() {
        busy = true
        // Hide our own overlay so OCR sees the original text, not our English
        overlay?.visibility = View.INVISIBLE
        main.postDelayed({ grab() }, 150)
    }

    private fun grab() {
        val img = try { reader?.acquireLatestImage() } catch (e: Exception) { null }
        if (img == null) { // screen unchanged, nothing new to read
            overlay?.visibility = View.VISIBLE
            busy = false
            return
        }
        val p = img.planes[0]
        val rowPad = p.rowStride - p.pixelStride * img.width
        val tmp = Bitmap.createBitmap(img.width + rowPad / p.pixelStride, img.height, Bitmap.Config.ARGB_8888)
        tmp.copyPixelsFromBuffer(p.buffer)
        val bmp = Bitmap.createBitmap(tmp, 0, 0, img.width, img.height)
        tmp.recycle()
        img.close()
        overlay?.visibility = View.VISIBLE

        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { res ->
                val blocks = res.textBlocks.filter { it.boundingBox != null && CJK.containsMatchIn(it.text) }
                if (blocks.isEmpty()) { show(emptyList()); return@addOnSuccessListener }
                worker.execute {
                    try {
                        val key = getSharedPreferences("settings", MODE_PRIVATE)
                            .getString("gemini_key", "") ?: "" // <- adapt to where your app stores the key
                        val src = blocks.map { it.text.replace("\n", "") }
                        val out = GeminiClient.translate(key, src)
                        show(blocks.mapIndexed { i, b -> OverlayView.Item(b.boundingBox!!, out[i]) })
                    } catch (e: Exception) {
                        Log.e(TAG, "translate failed", e)
                        main.post { busy = false }
                    }
                }
            }
            .addOnFailureListener { busy = false }
    }

    private fun show(items: List<OverlayView.Item>) {
        main.post { overlay?.setItems(items); busy = false }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen translator", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, TranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return b.setContentTitle("Screen translator running")
            .setContentText("Tap Stop to turn off")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        display?.release()
        reader?.close()
        projection?.stop()
        recognizer.close()
        worker.shutdown()
        super.onDestroy()
    }
}
Request(bmp: Bitmap) {
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
}
