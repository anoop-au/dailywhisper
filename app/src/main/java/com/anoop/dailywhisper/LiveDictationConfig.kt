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

    /**
     * Languages nova-3 accepts (developers.deepgram.com/docs/models-languages-overview, Oct 2026), shown by name,
     * stored by code. "multi" = nova-3 multilingual (code-switching). Other models support fewer of these.
     */
    val LANGUAGES: List<Pair<String, String>> = listOf(
        "multi" to "Multilingual (auto, mixes languages)",
        "en" to "English",
        "en-AU" to "English (Australia)",
        "en-CA" to "English (Canada)",
        "en-IN" to "English (India)",
        "en-NZ" to "English (New Zealand)",
        "en-GB" to "English (UK)",
        "en-US" to "English (US)",
        "af" to "Afrikaans",
        "ar" to "Arabic",
        "hy" to "Armenian",
        "as" to "Assamese",
        "be" to "Belarusian",
        "bn" to "Bengali",
        "bs" to "Bosnian",
        "bg" to "Bulgarian",
        "ca" to "Catalan",
        "zh-HK" to "Chinese (Cantonese, Traditional)",
        "zh-CN" to "Chinese (Mandarin, Simplified)",
        "zh-TW" to "Chinese (Mandarin, Traditional)",
        "hr" to "Croatian",
        "cs" to "Czech",
        "da" to "Danish",
        "nl" to "Dutch",
        "et" to "Estonian",
        "fi" to "Finnish",
        "nl-BE" to "Flemish",
        "fr" to "French",
        "fr-CA" to "French (Canada)",
        "gl" to "Galician",
        "ka" to "Georgian",
        "de" to "German",
        "de-CH" to "German (Switzerland)",
        "el" to "Greek",
        "gu" to "Gujarati",
        "he" to "Hebrew",
        "hi" to "Hindi",
        "hu" to "Hungarian",
        "id" to "Indonesian",
        "it" to "Italian",
        "ja" to "Japanese",
        "kn" to "Kannada",
        "kk" to "Kazakh",
        "ko" to "Korean",
        "lv" to "Latvian",
        "lt" to "Lithuanian",
        "mk" to "Macedonian",
        "ms" to "Malay",
        "mr" to "Marathi",
        "mn" to "Mongolian",
        "ne" to "Nepali",
        "no" to "Norwegian",
        "ps" to "Pashto",
        "fa" to "Persian",
        "pl" to "Polish",
        "pt" to "Portuguese",
        "pt-BR" to "Portuguese (Brazil)",
        "pt-PT" to "Portuguese (Portugal)",
        "pa" to "Punjabi",
        "ro" to "Romanian",
        "ru" to "Russian",
        "sr" to "Serbian",
        "sk" to "Slovak",
        "sl" to "Slovenian",
        "es" to "Spanish",
        "es-419" to "Spanish (Latin America)",
        "sv" to "Swedish",
        "tl" to "Tagalog",
        "ta" to "Tamil",
        "te" to "Telugu",
        "th" to "Thai",
        "tr" to "Turkish",
        "uk" to "Ukrainian",
        "ur" to "Urdu",
        "vi" to "Vietnamese",
        "xh" to "Xhosa",
        "zu" to "Zulu",
    )

    fun languageName(code: String): String = LANGUAGES.firstOrNull { it.first == code }?.second ?: code

    /** Maps an old free-text language (v0.1.5) to a listed code, case-insensitively; null if unknown. */
    fun canonicalLanguage(typed: String): String? = LANGUAGES.firstOrNull { it.first.equals(typed.trim(), ignoreCase = true) }?.first

    /** Region -> host. Regional hosts never route outside their region (see Residency for what the user is told). */
    val REGIONS: List<Triple<String, String, String>> = listOf(
        Triple("au", "Australia", "api.au.deepgram.com"),
        Triple("us", "US (global)", "api.deepgram.com"),
        Triple("eu", "EU", "api.eu.deepgram.com"),
        Triple("in", "India", "api.in.deepgram.com"),
    )

    /** Settings keys used before v0.1.5 (generic WebSocket setup); removed once on upgrade. Never the API key. */
    val OLD_KEYS = listOf("stt_url", "stt_auth", "stt_auth_name", "stt_sample_rate", "stt_chunk_ms",
        "stt_start_msg", "stt_end_msg", "stt_text_field", "stt_final_field", "stt_final_value")

    /**
     * The streaming URL. Blank model/language fall back to the defaults; unknown region falls back to Australia.
     * mip_opt_out=true: Deepgram keeps nothing after the request and, on a regional host, never leaves that region.
     */
    fun url(region: String, model: String, language: String, smartFormat: Boolean): String {
        val host = (REGIONS.firstOrNull { it.first == region } ?: REGIONS.first { it.first == DEFAULT_REGION }).third
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val m = model.trim().ifEmpty { DEFAULT_MODEL }
        val l = language.trim().ifEmpty { DEFAULT_LANGUAGE }
        return "wss://$host/v1/listen?model=${enc(m)}&language=${enc(l)}&smart_format=$smartFormat" +
            "&encoding=linear16&sample_rate=$SAMPLE_RATE&channels=1&interim_results=true&mip_opt_out=true"
    }

    /** Deepgram auth: "Authorization: Token <key>". The key goes nowhere else. */
    fun headers(key: String): Map<String, String> = if (key.isEmpty()) emptyMap() else mapOf("Authorization" to "Token $key")
}
