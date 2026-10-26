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

    // Setting defaults.
    const val DEFAULT_MODEL = "saaras:v3-realtime" // Sarvam's default for this endpoint
    const val DEFAULT_MODE = "transcribe"

    val MODELS: List<Pair<String, String>> = listOf(
        "saaras:v3-realtime" to "Saaras v3 realtime (default)",
        "saaras:v4" to "Saaras v4 (newest)",
    )

    val MODES: List<Pair<String, String>> = listOf(
        "transcribe" to "Transcribe (in the language's own script)",
        "codemix" to "Code-mixed (English words stay in English)",
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
     * "multi" (auto / mixed) -> "auto". Any English variant -> "en-IN" (Saaras covers Indian and global English).
     */
    fun languageCode(appCode: String): String? {
        val c = appCode.trim()
        if (c == "multi") return "auto"
        return LANGUAGES[c.substringBefore('-').lowercase()]
    }

    fun supports(appCode: String): Boolean = languageCode(appCode) != null

    /** The streaming URL. Unknown model/mode fall back to the defaults. [base] is only overridden by the test harness. */
    fun url(model: String, mode: String, language: String, pauseMs: Int, base: String = "wss://$HOST"): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val m = MODELS.firstOrNull { it.first == model.trim() }?.first ?: DEFAULT_MODEL
        val md = MODES.firstOrNull { it.first == mode.trim() }?.first ?: DEFAULT_MODE
        val l = languageCode(language) ?: "auto"
        val pause = pauseMs.coerceIn(SpeechSlots.MIN_PAUSE_MS, SpeechSlots.MAX_PAUSE_MS)
        return "$base$PATH?language_code=${enc(l)}&model=${enc(m)}&mode=${enc(md)}&stream_type=balanced" +
            "&endpointing=vad&silence_duration_ms=$pause&encoding=linear16&sample_rate=${LiveDictationConfig.SAMPLE_RATE}"
    }

    fun headers(key: String): Map<String, String> = if (key.isEmpty()) emptyMap() else mapOf("api-subscription-key" to key)

    /** One audio chunk as Sarvam's JSON audio message. */
    fun audioMessage(buf: ByteArray, offset: Int, length: Int): String =
        """{"event":"audio_input","audio":"""" + Base64.getEncoder().encodeToString(buf.copyOfRange(offset, offset + length)) + "\"}"
}
