package com.duykhai.gaming

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max

/**
 * Lightweight hand-drawn space / crewmate gaming backdrop.
 * All artwork is vector-rendered, offline, and never obscures controls.
 */
class SpaceBackdropDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun fill(canvas: Canvas, color: Int, a: Float, b: Float, c: Float, d: Float, radius: Float) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawRoundRect(a, b, c, d, radius, radius, paint)
    }

    private fun crew(canvas: Canvas, x: Float, y: Float, scale: Float, color: Int) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        // A whimsical tiny spaceship crewmate silhouette, not a game screenshot.
        fill(canvas, Color.argb(160, 0, 0, 0), 5f, 11f, 86f, 116f, 35f)
        fill(canvas, color, 12f, 2f, 80f, 96f, 33f)
        fill(canvas, color, 10f, 77f, 36f, 120f, 12f)
        fill(canvas, color, 56f, 77f, 81f, 120f, 12f)
        fill(canvas, Color.rgb(15, 42, 75), 27f, 19f, 83f, 60f, 22f)
        fill(canvas, Color.rgb(126, 233, 255), 30f, 23f, 78f, 55f, 18f)
        fill(canvas, Color.argb(180, 255, 255, 255), 35f, 27f, 58f, 33f, 10f)
        canvas.restore()
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        paint.shader = LinearGradient(
            0f, 0f, w, max(h, 800f),
            intArrayOf(Color.rgb(20, 7, 55), Color.rgb(42, 13, 90), Color.rgb(6, 13, 46), Color.rgb(42, 7, 85)),
            floatArrayOf(0f, .25f, .60f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        val starColor = Color.rgb(142, 235, 255)
        for (i in 0..115) {
            val px = (((i * 83 + 17) % 353) / 353f) * w
            val py = (((i * 223 + 49) % 997) / 997f) * h
            paint.color = if (i % 5 == 0) Color.argb(150, 232, 121, 255) else
                Color.argb(145 + (i * 7 % 100), 172, 235, 255)
            canvas.drawCircle(px, py, if (i % 9 == 0) 2.4f else 1.2f, paint)
        }
        paint.color = Color.argb(42, 91, 162, 255)
        canvas.drawCircle(w * .84f, 195f, 100f, paint)
        paint.color = Color.argb(65, 63, 206, 255)
        canvas.drawCircle(w * .84f, 195f, 76f, paint)
        paint.color = Color.argb(66, 242, 65, 212)
        canvas.drawCircle(w * .11f, h * .80f, 150f, paint)
        crew(canvas, w - 135f, 72f, .75f, Color.rgb(255, 70, 119))
        crew(canvas, 7f, h - 175f, .72f, Color.rgb(79, 212, 243))
        crew(canvas, w - 92f, h - 145f, .52f, Color.rgb(192, 82, 251))
    }

    override fun setAlpha(alpha: Int) { }
    override fun setColorFilter(colorFilter: ColorFilter?) { }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
