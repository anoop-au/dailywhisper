package com.anoop.dailywhisper

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Inter for controls and meta lines; Noto Serif (v0.1.28 redesign) for the wordmark, headings and the notes themselves.
 * Both fall back to bundled Noto Sans Malayalam, so Malayalam renders the same on every phone, then to the phone's own
 * fonts (Chinese, Hindi…). All faces are SIL OFL (see assets/licenses). Typefaces are built once per process.
 */
object DwFonts {
    /** Noto Serif Regular: note text on Home, the note screen and lists. */
    fun serif(ctx: Context): Typeface = cached("serif") { serifFallback(ctx, R.font.noto_serif_regular, italic = false, R.font.noto_sans_malayalam_regular) }
    /** Noto Serif Italic: the "Daily Whisper" wordmark, the Latest pull-quote, screen titles. */
    fun serifItalic(ctx: Context): Typeface = cached("serif_italic") { serifFallback(ctx, R.font.noto_serif_italic, italic = true, R.font.noto_sans_malayalam_regular) }

    private val cache = HashMap<String, Typeface>()
    private fun cached(key: String, make: () -> Typeface): Typeface = synchronized(cache) { cache.getOrPut(key, make) }

    private fun serifFallback(ctx: Context, res: Int, italic: Boolean, malayalamRes: Int): Typeface = try {
        val font = Font.Builder(ctx.resources, res)
            .setSlant(if (italic) android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC else android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT)
            .build()
        val malayalam = FontFamily.Builder(Font.Builder(ctx.resources, malayalamRes).build()).build()
        Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build()).addCustomFallback(malayalam)
            .setSystemFallback("serif").build()
    } catch (_: Exception) {
        ctx.resources.getFont(res)
    }

    fun regular(ctx: Context): Typeface = fallback(ctx, R.font.inter_regular, R.font.noto_sans_malayalam_regular)
    fun medium(ctx: Context): Typeface = fallback(ctx, R.font.inter_medium, R.font.noto_sans_malayalam_regular)
    fun semibold(ctx: Context): Typeface = fallback(ctx, R.font.inter_semibold, R.font.noto_sans_malayalam_semibold)
    fun bold(ctx: Context): Typeface = fallback(ctx, R.font.inter_bold, R.font.noto_sans_malayalam_semibold)

    fun apply(root: View) {
        val ctx = root.context
        applyTo(root, regular(ctx), medium(ctx), semibold(ctx))
    }

    private fun applyTo(view: View, regular: Typeface, medium: Typeface, semibold: Typeface) {
        if (view is TextView) {
            val style = view.typeface?.style ?: Typeface.NORMAL
            val weight = view.typeface?.weight ?: 400
            view.typeface = when {
                style and Typeface.BOLD != 0 || weight >= 600 -> semibold
                weight >= 500 -> medium
                else -> regular
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyTo(view.getChildAt(i), regular, medium, semibold)
        }
    }

    private fun fallback(ctx: Context, interRes: Int, malayalamRes: Int): Typeface = try {
        val inter = FontFamily.Builder(Font.Builder(ctx.resources, interRes).build()).build()
        val malayalam = FontFamily.Builder(Font.Builder(ctx.resources, malayalamRes).build()).build()
        Typeface.CustomFallbackBuilder(inter).addCustomFallback(malayalam).setSystemFallback("sans-serif").build()
    } catch (_: Exception) {
        ctx.resources.getFont(interRes)
    }
}
