package com.jarvis.ev

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Jarvis tarzı dönen "arc reactor" HUD. mode: 0 bekleme, 1 dinleme, 2 araştırma, 3 konuşma */
class ReactorView(context: Context) : View(context) {
    var mode = 0
    @Volatile var level = 0f
    private var angle = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private fun al(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 8f
        val sp = when (mode) { 2 -> 3.5f; 1 -> 2f; 3 -> 1.6f; else -> 1f }
        val col = when (mode) {
            3 -> Color.parseColor("#FFC857")
            2 -> Color.parseColor("#B388FF")
            else -> Color.parseColor("#00E5FF")
        }
        val pulse = 0.5f + 0.5f * sin(Math.toRadians((angle * 3).toDouble())).toFloat()

        // dış parıltı
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, r, intArrayOf(al(col, (50 + pulse * 70 + level * 110).toInt()), al(col, 0)), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.shader = null

        // dış çentikler
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = al(col, 170)
        for (i in 0 until 72) {
            val a = Math.toRadians((i * 5 + angle * 0.3f).toDouble())
            val r1 = if (i % 6 == 0) r * 0.90f else r * 0.94f
            c.drawLine(cx + cos(a).toFloat() * r1, cy + sin(a).toFloat() * r1,
                cx + cos(a).toFloat() * r * 0.98f, cy + sin(a).toFloat() * r * 0.98f, p)
        }

        // dönen yaylar
        p.strokeWidth = 5f
        p.color = al(col, 230)
        rect.set(cx - r * 0.80f, cy - r * 0.80f, cx + r * 0.80f, cy + r * 0.80f)
        for (k in 0..2) c.drawArc(rect, -angle * 1.5f + k * 120f, 70f, false, p)

        // kesikli halka
        p.strokeWidth = 3f
        p.color = al(col, 150)
        p.pathEffect = DashPathEffect(floatArrayOf(10f, 14f), angle * 2)
        c.drawCircle(cx, cy, r * 0.64f, p)
        p.pathEffect = null

        // ince halka
        p.strokeWidth = 1.5f
        p.color = al(col, 120)
        c.drawCircle(cx, cy, r * 0.50f, p)

        // reaktör bobinleri
        p.strokeWidth = 14f
        p.color = al(col, 200)
        rect.set(cx - r * 0.38f, cy - r * 0.38f, cx + r * 0.38f, cy + r * 0.38f)
        for (k in 0 until 10) c.drawArc(rect, k * 36f + angle * 0.5f, 24f, false, p)

        // çekirdek
        p.style = Paint.Style.FILL
        p.color = al(col, 120 + (pulse * 100).toInt())
        c.drawCircle(cx, cy, r * (0.17f + 0.04f * pulse + 0.08f * level), p)
        p.color = Color.WHITE
        c.drawCircle(cx, cy, r * 0.07f, p)

        level *= 0.9f
        angle = (angle + 0.6f * sp) % 3600f
        postInvalidateOnAnimation()
    }
}
