package com.anoop.dailywhisper

import android.content.Context
import android.widget.Toast

/**
 * Hook for live, in-app speech-to-text (separate from Vion, which stays the polished multi-app dictation).
 * A real implementation will stream mic audio to a live STT API and push partial/final text into the composer.
 */
interface LiveDictation {
    val isListening: Boolean
    fun start()
    fun stop()
}

/** Placeholder until the live STT API is wired. No mic or network access yet (no RECORD_AUDIO / INTERNET). */
class ComingSoonLiveDictation(private val context: Context) : LiveDictation {
    override val isListening: Boolean = false

    override fun start() {
        // TODO: request RECORD_AUDIO, open the live STT stream, append partial/final text to the composer.
        Toast.makeText(context, R.string.live_dictation_soon, Toast.LENGTH_SHORT).show()
    }

    override fun stop() {
        // TODO: close the STT stream and release the mic.
    }
}
