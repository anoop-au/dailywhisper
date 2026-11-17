package com.anoop.dailywhisper

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * The divider between notes (v0.1.29, Anoop: "a thin line, golden… like an art"): a hairline of gold that fades in from
 * both ends, parts in the middle, and holds a tiny diamond there. Framework drawing only; [color] is the theme's gold.
 */
class GoldRule(private val color: Int, private val lineWidthPx: Float, private val diamondPx: Float, private val gapPx: Float,
               private val lineAlpha: Int, private val diamondAlpha: Int) : Drawable() {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val diamond = Path()
    private var shaderFor = -1

    private fun withAlpha(a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0) return
        val w = b.width().toFloat()
        if (shaderFor != b.width()) {
            // transparent → gold → (gap) → gold → transparent
            line.shader = LinearGradient(b.left.toFloat(), 0f, b.right.toFloat(), 0f,
                intArrayOf(withAlpha(0), withAlpha(lineAlpha), withAlpha(lineAlpha), withAlpha(0)),
                floatArrayOf(0f, 0.38f, 0.62f, 1f), Shader.TileMode.CLAMP)
            shaderFor = b.width()
        }
        val cy = b.exactCenterY(); val cx = b.left + w / 2
        val top = cy - lineWidthPx / 2; val bottom = cy + lineWidthPx / 2
        val half = if (diamondPx > 0) diamondPx / 2 + gapPx else 0f
        canvas.drawRect(b.left.toFloat(), top, cx - half, bottom, line)
        canvas.drawRect(cx + half, top, b.right.toFloat(), bottom, line)
        if (diamondPx > 0) {
            val r = diamondPx / 2
            diamond.rewind()
            diamond.moveTo(cx, cy - r); diamond.lineTo(cx + r, cy); diamond.lineTo(cx, cy + r); diamond.lineTo(cx - r, cy); diamond.close()
            dot.color = withAlpha(diamondAlpha)
            canvas.drawPath(diamond, dot)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicHeight(): Int = maxOf(diamondPx, lineWidthPx).toInt() + 2
}
