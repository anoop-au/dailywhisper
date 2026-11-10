package com.anoop.dailywhisper

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable

/**
 * The quiet gold frame that holds content (note cards, the Insights card, the note's reading well; bg_card.xml and
 * bg_well.xml draw the same): a soft [HALO_DP] halo of gilt_glow, then the panel inset inside it with a 1dp rim of
 * gilt_edge (or another [edge], e.g. full gilt for a highlighted note). Not for buttons, fields or the composer.
 */
class GoldEdge private constructor(layers: Array<Drawable>, val edgeColor: Int, val glowColor: Int) : LayerDrawable(layers) {
    companion object {
        const val HALO_DP = 2

        fun make(c: Context, radiusDp: Int, fill: Int, edge: Int = c.getColor(R.color.gilt_edge)): GoldEdge {
            val d = c.resources.displayMetrics.density
            val halo = (HALO_DP * d).toInt()
            val glow = c.getColor(R.color.gilt_glow)
            val outer = GradientDrawable().apply { cornerRadius = radiusDp * d; setColor(glow) }
            val inner = GradientDrawable().apply {
                cornerRadius = (radiusDp - HALO_DP) * d; setColor(fill); setStroke(maxOf(1, d.toInt()), edge)
            }
            return GoldEdge(arrayOf(outer, inner), edge, glow).apply { setLayerInset(1, halo, halo, halo, halo) }
        }
    }
}
