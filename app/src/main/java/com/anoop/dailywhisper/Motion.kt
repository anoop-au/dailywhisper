package com.anoop.dailywhisper

import android.view.animation.PathInterpolator

/** Lovable `--ease-whisper` and `--duration-base`. */
object Motion {
    val interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    const val MS = 200L
}
