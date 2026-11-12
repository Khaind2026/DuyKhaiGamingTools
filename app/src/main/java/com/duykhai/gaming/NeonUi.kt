package com.duykhai.gaming

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView
import kotlin.math.min

/** Native Android neon/space art. No external images, SDKs or network assets. */
object NeonUi {
    val cyan: Int = Color.rgb(27, 226, 255)
    val purple: Int = Color.rgb(184, 68, 255)
    val green: Int = Color.rgb(76, 247, 107)
    val muted: Int = Color.rgb(175, 189, 222)
    val white: Int = Color.WHITE
    val ink: Int = Color.rgb(6, 10, 30)

    fun dp(c: Context, amount: Int): Int = (amount * c.resources.displayMetrics.density + .5f).toInt()

    fun card(c: Context, edge: Int = purple): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
            Color.rgb(45, 20, 106), Color.rgb(11, 11, 41), Color.rgb(46, 11, 93)
        )).apply {
            cornerRadius = dp(c, 17).toFloat()
            setStroke(dp(c, 2), edge)
        }

    fun button(c: Context): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            Color.rgb(235, 103, 255), Color.rgb(162, 20, 239), Color.rgb(108, 13, 189)
        )).apply {
            cornerRadius = dp(c, 11).toFloat()
            setStroke(dp(c, 2), Color.rgb(255, 182, 255))
        }

    fun text(c: Context, value: String, sp: Float, color: Int = white, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = value
            textSize = sp
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    fun glowButton(c: Context, value: String): TextView = text(c, value, 14f, white, true).apply {
        background = button(c)
        gravity = Gravity.CENTER
        minHeight = dp(c, 46)
        setPadding(dp(c, 8), dp(c, 9), dp(c, 8), dp(c, 9))
    }
}

/** Stylized space-crewmate motif matching the supplied visual reference. */
class CrewFigureView(
    context: Context,
    private val suit: Int,
    private val emblem: String = "",
    private val crowned: Boolean = false
) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    private fun bodyBox(c: Canvas, color: Int, l: Float, t: Float, r: Float, b: Float, rad: Float) {
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = color
        c.drawRoundRect(l, t, r, b, rad, rad, p)
    }

    private fun drawOne(c: Canvas) {
        // Coordinates use a normalized 100 x 120 internal canvas.
        p.setShadowLayer(8f, 0f, 0f, suit)
        bodyBox(c, Color.rgb(8, 9, 25), 9f, 19f, 92f, 108f, 24f)
        p.clearShadowLayer()
        bodyBox(c, suit, 16f, 12f, 83f, 98f, 27f)
        bodyBox(c, suit, 16f, 79f, 40f, 116f, 10f)
        bodyBox(c, suit, 58f, 79f, 82f, 116f, 10f)
        bodyBox(c, Color.rgb(18, 22, 58), 28f, 30f, 91f, 72f, 22f)
        p.shader = LinearGradient(30f, 33f, 88f, 70f,
            Color.rgb(237, 253, 255), Color.rgb(5, 142, 233), Shader.TileMode.CLAMP)
        c.drawRoundRect(32f, 34f, 86f, 66f, 19f, 19f, p)
        p.shader = null
        bodyBox(c, Color.argb(195, 255, 255, 255), 39f, 38f, 61f, 43f, 6f)
        if (crowned) {
            val path = Path().apply {
                moveTo(25f, 17f)
                lineTo(20f, -13f)
                lineTo(39f, 0f)
                lineTo(52f, -20f)
                lineTo(65f, 0f)
                lineTo(83f, -13f)
                lineTo(80f, 17f)
                close()
            }
            p.color = Color.rgb(255, 208, 47)
            p.style = Paint.Style.FILL
            c.drawPath(path, p)
            bodyBox(c, Color.rgb(255, 168, 16), 26f, 11f, 80f, 20f, 4f)
        }
        if (emblem.isNotBlank()) {
            p.color = Color.WHITE
            p.textSize = 22f
            p.isFakeBoldText = true
            p.setShadowLayer(4f, 0f, 1f, Color.BLACK)
            c.drawText(emblem, 38f, 94f, p)
            p.clearShadowLayer()
            p.isFakeBoldText = false
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width / 110f, height / 140f)
        canvas.save()
        canvas.translate((width - 100f * scale) / 2f, (height - 122f * scale) / 2f)
        canvas.scale(scale, scale)
        drawOne(canvas)
        canvas.restore()
    }
}

/** Illustration above the home dashboard, with planet and a little crew. */
class CrewHeroView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        // Cosmic planet in the background.
        p.color = Color.argb(130, 54, 145, 240)
        p.setShadowLayer(25f, 0f, 0f, Color.rgb(48, 161, 255))
        canvas.drawCircle(w * .81f, h * .32f, h * .24f, p)
        p.clearShadowLayer()
        p.color = Color.argb(135, 13, 43, 90)
        canvas.drawCircle(w * .84f, h * .29f, h * .20f, p)
        drawCrew(canvas, w * .06f, h * .35f, h * .006f, Color.rgb(248, 189, 25), false)
        drawCrew(canvas, w * .37f, h * .08f, h * .0091f, Color.rgb(247, 38, 77), true)
        drawCrew(canvas, w * .74f, h * .36f, h * .0063f, Color.rgb(27, 216, 242), false)
        p.color = Color.argb(220, 42, 26, 95)
        val base = Path().apply {
            moveTo(0f, h * .95f)
            lineTo(w * .2f, h * .86f)
            lineTo(w * .36f, h * .94f)
            lineTo(w * .60f, h * .84f)
            lineTo(w * .81f, h * .94f)
            lineTo(w, h * .87f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        canvas.drawPath(base, p)
    }

    private fun drawCrew(canvas: Canvas, x: Float, y: Float, scale: Float, suit: Int, crown: Boolean) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        p.color = Color.rgb(8, 10, 31)
        canvas.drawRoundRect(5f, 7f, 88f, 104f, 30f, 30f, p)
        p.color = suit
        canvas.drawRoundRect(13f, 11f, 81f, 93f, 26f, 26f, p)
        canvas.drawRoundRect(11f, 74f, 36f, 107f, 8f, 8f, p)
        canvas.drawRoundRect(58f, 74f, 82f, 107f, 8f, 8f, p)
        p.color = Color.rgb(19, 30, 75)
        canvas.drawRoundRect(23f, 28f, 88f, 70f, 22f, 22f, p)
        p.shader = LinearGradient(32f, 30f, 86f, 63f,
            Color.rgb(226, 255, 255), Color.rgb(11, 150, 238), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(28f, 32f, 83f, 65f, 19f, 19f, p)
        p.shader = null
        p.color = Color.WHITE
        canvas.drawRoundRect(39f, 37f, 61f, 42f, 5f, 5f, p)
        if (crown) {
            p.color = Color.rgb(255, 204, 26)
            canvas.drawPath(Path().apply {
                moveTo(22f, 19f); lineTo(17f, -17f); lineTo(40f, -3f)
                lineTo(51f, -24f); lineTo(66f, -3f); lineTo(85f, -19f)
                lineTo(81f, 19f); close()
            }, p)
        }
        canvas.restore()
    }
}
