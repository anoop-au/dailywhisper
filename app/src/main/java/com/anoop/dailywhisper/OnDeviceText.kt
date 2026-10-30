package com.anoop.dailywhisper

/**
 * Tidies what the on-device speech packs write. The English pack writes LIKE THIS WITH NO PUNCTUATION (LibriSpeech
 * style); the sentence packs write plain lower-case text. Finished sentences get a capital and a full stop, the way
 * Deepgram's smart formatting would. Pure Kotlin so the JVM tests check it.
 */
object OnDeviceText {
    private val END = setOf('.', '?', '!', '…', '।', '|')

    fun tidy(raw: String, final: Boolean, english: Boolean = false): String {
        var t = raw.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return ""
        val shouting = t.any { it.isLetter() } && t.none { it.isLowerCase() } && t.any { it in 'A'..'Z' }
        if (shouting) t = t.lowercase()
        // English "i" on its own, and its contractions (i'm, i'll, i'd, i've).
        if (english) t = t.replace(Regex("(?<![\\p{L}'])i(?=$|[\\s'’,.?!])"), "I")
        if (final) {
            t = t.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            if (t.last() !in END) t += "."
        }
        return t
    }
}

/**
 * Evens out the microphone level before on-device recognition. Android's VOICE_RECOGNITION source (used for every
 * provider) is deliberately left without automatic gain, and the small English pack gets much worse on quiet speech
 * (box test, speech 20 dB quieter: word errors on Indian English 38% → 68%; with this boost 40%, and normal-level speech unchanged). The gain follows
 * the loudest recent sound (falling back slowly, about 5% a second), aims those peaks at half of full scale, rises
 * slowly (so a pause doesn't pump up the background noise), drops at once when it gets loud, never clips, and never
 * goes above [maxGain] (1 = off). Pure Kotlin so the JVM tests check it.
 */
class AutoGain(private val maxGain: Float, private val sampleRate: Int = 16000) {
    private var envelope = 0f
    var gain = 1f
        private set

    /** Scales [s] in place and returns it. */
    fun apply(s: FloatArray): FloatArray {
        if (maxGain <= 1f || s.isEmpty()) return s
        var peak = 0f
        for (v in s) { val a = kotlin.math.abs(v); if (a > peak) peak = a }
        val per100ms = s.size / (sampleRate / 10.0)
        envelope = maxOf(peak, envelope * Math.pow(DECAY_PER_100MS.toDouble(), per100ms).toFloat())
        val want = (TARGET / maxOf(envelope, 1e-4f)).coerceIn(1f, maxGain)
        gain = if (want > gain) gain + (1 - Math.pow(1.0 - RISE_PER_100MS, per100ms).toFloat()) * (want - gain) else want
        if (peak * gain > CEILING) gain = CEILING / peak
        if (gain != 1f) for (i in s.indices) s[i] *= gain
        return s
    }

    companion object {
        const val TARGET = 0.5f
        const val CEILING = 0.99f
        const val DECAY_PER_100MS = 0.995f
        const val RISE_PER_100MS = 0.05
    }
}
