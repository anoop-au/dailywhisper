package com.anoop.dailywhisper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * The page wash: two elliptical radials (top-right periwinkle, bottom-left warm/magenta)
 * sized like the Lovable `.bg-velvet` frame, scaled from the measured 322-wide phone mock.
 */
class VelvetDrawable(private val bg: Int, private val glowA: Int, private val glowB: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawColor(bg)
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val scale = w / 322f
        drawGlow(canvas, w * 1.10f, h * -0.10f, 960f * scale, 640f * scale, glowA)
        drawGlow(canvas, w * -0.20f, h * 1.10f, 800f * scale, 576f * scale, glowB)
    }

    private fun drawGlow(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, color: Int) {
        if (rx <= 1f || ry <= 1f || Color.alpha(color) == 0) return
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(1f, ry / rx)
        paint.shader = RadialGradient(0f, 0f, rx, intArrayOf(color, Color.TRANSPARENT), floatArrayOf(0f, 0.6f), Shader.TileMode.CLAMP)
        canvas.drawCircle(0f, 0f, rx, paint)
        paint.shader = null
        canvas.restore()
    }

    override fun onBoundsChange(bounds: Rect) { invalidateSelf() }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
