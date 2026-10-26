package com.anoop.dailywhisper

import java.net.URLEncoder

/**
 * Live dictation is Deepgram streaming (/v1/listen). Only region, model, language and smart formatting
 * are settings; everything else is fixed here. Pure Kotlin (no Android imports) so the JVM harness in
 * tools/ws-harness tests exactly this code.
 */
object LiveDictationConfig {
    // Fixed protocol details (Deepgram live streaming).
    const val SAMPLE_RATE = 16000                 // mono PCM16 little-endian ("linear16")
    const val CHUNK_MS = 100                      // audio per binary frame
    const val START_MESSAGE = ""                  // nothing to send on connect
    const val END_MESSAGE = """{"type":"CloseStream"}""" // flush last finals, then the server closes
    const val TEXT_PATH = "channel.alternatives.0.transcript"
    const val FINAL_FIELD = "is_final"
    const val FINAL_VALUE = "true"

    // Setting defaults.
    const val DEFAULT_REGION = "au"
    const val DEFAULT_MODEL = "nova-3"            // model family; Deepgram's version=latest picks its newest release
    const val DEFAULT_LANGUAGE = "en"
    const val DEFAULT_SMART_FORMAT = true

    /** Region -> host. Regional hosts never route outside their region. */
    val REGIONS: List<Triple<String, String, String>> = listOf(
        Triple("au", "Australia", "api.au.deepgram.com"),
        Triple("us", "US (global)", "api.deepgram.com"),
        Triple("eu", "EU", "api.eu.deepgram.com"),
    )

    /** Settings keys used before v0.1.5 (generic WebSocket setup); removed once on upgrade. Never the API key. */
    val OLD_KEYS = listOf("stt_url", "stt_auth", "stt_auth_name", "stt_sample_rate", "stt_chunk_ms",
        "stt_start_msg", "stt_end_msg", "stt_text_field", "stt_final_field", "stt_final_value")

    /** The streaming URL. Blank model/language fall back to the defaults; unknown region falls back to Australia. */
    fun url(region: String, model: String, language: String, smartFormat: Boolean): String {
        val host = (REGIONS.firstOrNull { it.first == region } ?: REGIONS.first { it.first == DEFAULT_REGION }).third
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val m = model.trim().ifEmpty { DEFAULT_MODEL }
        val l = language.trim().ifEmpty { DEFAULT_LANGUAGE }
        return "wss://$host/v1/listen?model=${enc(m)}&language=${enc(l)}&smart_format=$smartFormat" +
            "&encoding=linear16&sample_rate=$SAMPLE_RATE&channels=1&interim_results=true"
    }

    /** Deepgram auth: "Authorization: Token <key>". The key goes nowhere else. */
    fun headers(key: String): Map<String, String> = if (key.isEmpty()) emptyMap() else mapOf("Authorization" to "Token $key")
}
