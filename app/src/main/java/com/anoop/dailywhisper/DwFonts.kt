package com.anoop.dailywhisper

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Inter for titles, controls and journal entries, with bundled Noto Sans Malayalam as a fallback
 * so Malayalam renders the same on every phone. Both faces are SIL OFL (see assets/licenses).
 */
object DwFonts {
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
