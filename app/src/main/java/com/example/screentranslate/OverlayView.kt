package com.example.screentranslate // <- change to your package

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View

class OverlayView(ctx: Context) : View(ctx) {
    data class Item(val rect: Rect, val text: String)

    private var items: List<Item> = emptyList()
    private val bg = Paint().apply { color = Color.argb(225, 0, 0, 0) }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    fun setItems(list: List<Item>) {
        items = list
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        for (item in items) {
            val r = item.rect
            val left = r.left.coerceAtLeast(0)
            val width = maxOf(r.width(), 320).coerceAtMost(width - left)
            if (width <= 0) continue

            // Shrink the font until the text fits roughly in the original box height
            var size = r.height().coerceIn(26, 44).toFloat()
            var layout: StaticLayout
            while (true) {
                tp.textSize = size
                layout = StaticLayout.Builder
                    .obtain(item.text, 0, item.text.length, tp, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .build()
                if (layout.height <= r.height() * 1.6f || size <= 20f) break
                size -= 2f
            }
            c.save()
            c.translate(left.toFloat(), r.top.toFloat())
            c.drawRect(-4f, -2f, width + 4f, layout.height + 2f, bg)
            layout.draw(c)
            c.restore()
        }
    }
}
