package com.anoop.dailywhisper

import java.net.URLEncoder
import java.util.Base64

/**
 * Speech slot 2: Sarvam AI realtime streaming speech-to-text (GET wss://api.sarvam.ai/speech-to-text-realtime/ws).
 * Source: docs.sarvam.ai/api-reference/speech-to-text/transcribe/realtime/ws (Oct 2026).
 *
 * - Auth: "api-subscription-key: <key>" header on the WebSocket upgrade. The key goes nowhere else.
 * - Audio: raw PCM16 mono 16 kHz ("linear16"), sent as JSON text frames {"event":"audio_input","audio":"<base64>"}.
 * - Server: session.begin (ready), transcript.partial / transcript.final {"text": ...}, error {is_fatal, status_code}.
 *   With language_code=auto, partials and finals also carry "language" (BCP-47, e.g. "ml-IN"; finals add
 *   language_confidence). Sarvam calls this "adaptive automatic language detection".
 * - End: {"event":"end"}: the server finalizes the last utterance and closes.
 * - Pause: silence_duration_ms (end-of-turn silence with endpointing=vad), the same "Pause before a sentence ends" setting.
 * Sarvam runs in India only (Residency names India for it). Pure Kotlin so tools/ws-harness tests exactly this code.
 */
object SarvamConfig {
    const val HOST = "api.sarvam.ai"
    const val PATH = "/speech-to-text-realtime/ws"
    const val END_MESSAGE = """{"event":"end"}"""
    const val TEXT_PATH = "text"
    const val FINAL_FIELD = "event"
    const val FINAL_VALUE = "transcript.final"
    const val READY_EVENT = "session.begin"
    const val LANGUAGE_PATH = "language"
    /** language_code value for Sarvam's own detection on the realtime endpoint (the legacy /speech-to-text/ws uses "unknown"). */
    const val AUTO_CODE = "auto"

    // Setting defaults.
    const val DEFAULT_MODEL = "saaras:v3-realtime" // Sarvam's default for this endpoint
    const val DEFAULT_MODE = "transcribe"
    /**
     * Sarvam's "mode" value for code-mixed output: English words stay in English, the rest in its own script
     * (e.g. "എന്റെ phone number ഇതാണ്"). Supported on this endpoint by both saaras:v3-realtime and saaras:v4. Sarvam
     * applies the mode to finals only; partials are always plain transcription (docs.sarvam.ai realtime ws, Oct 2026).
     */
    const val CODEMIX_MODE = "codemix"

    val MODELS: List<Pair<String, String>> = listOf(
        "saaras:v3-realtime" to "Saaras v3 realtime (default)",
        "saaras:v4" to "Saaras v4 (newest)",
    )

    val MODES: List<Pair<String, String>> = listOf(
        "transcribe" to "Transcribe (in the language's own script)",
        CODEMIX_MODE to "Code-mixed (English words stay in English)",
        "translit" to "Roman letters (transliterated)",
        "verbatim" to "Verbatim (every um and ah)",
        "translate" to "Translate to English",
    )

    /** Sarvam's languages (BCP-47 with -IN), keyed by the base code used in the app's dictation language list. */
    val LANGUAGES: Map<String, String> = linkedMapOf(
        "en" to "en-IN", "hi" to "hi-IN", "bn" to "bn-IN", "kn" to "kn-IN", "ml" to "ml-IN", "mr" to "mr-IN",
        "or" to "or-IN", "pa" to "pa-IN", "ta" to "ta-IN", "te" to "te-IN", "gu" to "gu-IN", "as" to "as-IN",
        "ur" to "ur-IN", "ne" to "ne-IN", "kok" to "kok-IN", "ks" to "ks-IN", "sd" to "sd-IN", "sa" to "sa-IN",
        "sat" to "sat-IN", "mni" to "mni-IN", "brx" to "brx-IN", "mai" to "mai-IN", "doi" to "doi-IN",
    )

    /**
     * The Sarvam language code for the app's dictation language, or null if Sarvam doesn't have it.
     * "Auto (Sarvam AI)" and Deepgram's "multi" (when Sarvam is the fallback) -> "auto". Any English variant -> "en-IN"
     * (Saaras covers Indian and global English).
     */
    fun languageCode(appCode: String): String? {
        val c = appCode.trim()
        if (c == SpeechSlots.AUTO_SARVAM || c == "multi") return AUTO_CODE
        return LANGUAGES[c.substringBefore('-').lowercase()]
    }

    fun supports(appCode: String): Boolean = languageCode(appCode) != null

    /**
     * The mode sent to Sarvam. Live dictation's "Keep English words in English" switch ([codemix]) applies only when the
     * Language is "Auto (Sarvam AI)": then it sends "codemix". Otherwise (switch off, or any other language) it's the
     * speech slot 2 "Output" setting, unchanged (default "transcribe", Sarvam's own default).
     */
    fun mode(output: String, codemix: Boolean, language: String): String =
        if (codemix && language.trim() == SpeechSlots.AUTO_SARVAM) CODEMIX_MODE else output

    /** The streaming URL. Unknown model/mode fall back to the defaults. [base] is only overridden by the test harness. */
    fun url(model: String, mode: String, language: String, pauseMs: Int, base: String = "wss://$HOST"): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val m = MODELS.firstOrNull { it.first == model.trim() }?.first ?: DEFAULT_MODEL
        val md = MODES.firstOrNull { it.first == mode.trim() }?.first ?: DEFAULT_MODE
        val l = languageCode(language) ?: AUTO_CODE
        val pause = pauseMs.coerceIn(SpeechSlots.MIN_PAUSE_MS, SpeechSlots.MAX_PAUSE_MS)
        return "$base$PATH?language_code=${enc(l)}&model=${enc(m)}&mode=${enc(md)}&stream_type=balanced" +
            "&endpointing=vad&silence_duration_ms=$pause&encoding=linear16&sample_rate=${LiveDictationConfig.SAMPLE_RATE}"
    }

    /** A detected BCP-47 code ("ml-IN") as a language name ("Malayalam"); null if blank. */
    fun detectedName(bcp47: String?): String? {
        val c = bcp47?.trim().orEmpty()
        if (c.isEmpty()) return null
        val base = LANGUAGES.entries.firstOrNull { it.value.equals(c, ignoreCase = true) }?.key ?: c.substringBefore('-').lowercase()
        return SpeechSlots.languageName(base).takeIf { it != base } ?: c
    }

    fun headers(key: String): Map<String, String> = if (key.isEmpty()) emptyMap() else mapOf("api-subscription-key" to key)

    /** One audio chunk as Sarvam's JSON audio message. */
    fun audioMessage(buf: ByteArray, offset: Int, length: Int): String =
        """{"event":"audio_input","audio":"""" + Base64.getEncoder().encodeToString(buf.copyOfRange(offset, offset + length)) + "\"}"
}
