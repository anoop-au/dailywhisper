package com.anoop.dailywhisper

import java.net.URLEncoder

/**
 * Live-dictation defaults (Deepgram, Australian region), the one-time upgrade from v0.1.3's generic
 * defaults, and how the request URL + headers are built. Pure Kotlin (no Android imports) so the
 * JVM harness in tools/ws-harness tests exactly this code.
 */
object LiveDictationConfig {
    // Setting keys (shared with Prefs in Settings.kt).
    const val K_URL = "stt_url"
    const val K_AUTH = "stt_auth"
    const val K_AUTH_NAME = "stt_auth_name"
    const val K_START = "stt_start_msg"
    const val K_END = "stt_end_msg"
    const val K_TEXT = "stt_text_field"
    const val K_FINAL_FIELD = "stt_final_field"
    const val K_FINAL_VALUE = "stt_final_value"

    // Deepgram query string used for the default URL and when upgrading a bare Deepgram URL.
    const val DEEPGRAM_QUERY = "encoding=linear16&sample_rate={sample_rate}&channels=1&interim_results=true"
    const val DEEPGRAM_URL = "wss://api.au.deepgram.com/v1/listen?$DEEPGRAM_QUERY"

    /** New defaults (v0.1.4+). Sample rate 16000, chunk 100 ms and the empty start message are unchanged. */
    val DEFAULTS: Map<String, String> = mapOf(
        K_URL to DEEPGRAM_URL,
        K_AUTH to "token",                 // Authorization: Token <key>
        K_AUTH_NAME to "x-api-key",
        K_START to "",
        K_END to """{"type":"CloseStream"}""",
        K_TEXT to "channel.alternatives.0.transcript",
        K_FINAL_FIELD to "is_final",
        K_FINAL_VALUE to "true",
    )

    /** v0.1.3 defaults, used only to recognise untouched values when upgrading. */
    private val V013_DEFAULTS: Map<String, String> = mapOf(
        K_URL to "", K_AUTH to "bearer", K_AUTH_NAME to "x-api-key", K_START to "", K_END to "",
        K_TEXT to "text", K_FINAL_FIELD to "is_final", K_FINAL_VALUE to "true",
    )

    /** Fields the upgrade may change. Start message stays as typed (empty is still the default). Never the API key. */
    private val MIGRATED = listOf(K_URL, K_AUTH, K_END, K_TEXT, K_FINAL_FIELD, K_FINAL_VALUE)

    private val BARE_DEEPGRAM_URL = Regex("""^wss://api(\.[a-z]{2})?\.deepgram\.com/v1/listen/?$""", RegexOption.IGNORE_CASE)

    /**
     * One-time upgrade from v0.1.3. [stored] holds only values actually saved on the phone.
     * Returns key -> new value to save. A stored value is replaced if it is empty, equals the old default,
     * or (URL) is a bare Deepgram listen URL with no query string (its host/region is kept).
     * Anything else the user typed is left alone.
     */
    fun migrateFromV013(stored: Map<String, String?>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (k in MIGRATED) {
            if (!stored.containsKey(k)) continue // not saved: the new default applies automatically
            val v = stored[k]?.trim().orEmpty()
            val new = DEFAULTS.getValue(k)
            when {
                v == new -> {}
                v.isEmpty() || v == V013_DEFAULTS[k] -> out[k] = new
                k == K_URL && BARE_DEEPGRAM_URL.matches(v) -> out[k] = v.trimEnd('/') + "?" + DEEPGRAM_QUERY
            }
        }
        return out
    }

    /**
     * Builds the final URL and extra handshake headers. Throws IllegalArgumentException with a friendly
     * message if something needed is missing. The key only ever goes into the header / parameter chosen.
     */
    fun buildRequest(urlSetting: String, sampleRate: Int, authMode: String, authName: String, key: String): Pair<String, Map<String, String>> {
        var url = urlSetting.trim().replace("{sample_rate}", sampleRate.toString())
        require(url.isNotEmpty()) { "Add your streaming URL in Settings → Live dictation" }
        val name = authName.trim()
        val headers = LinkedHashMap<String, String>()
        if (key.isNotEmpty()) when (authMode) {
            "bearer" -> headers["Authorization"] = "Bearer $key"
            "token" -> headers["Authorization"] = "Token $key"
            "header" -> { require(name.isNotEmpty()) { "Set the header name in Settings → Live dictation" }; headers[name] = key }
            "query" -> {
                require(name.isNotEmpty()) { "Set the parameter name in Settings → Live dictation" }
                url += (if ('?' in url) "&" else "?") + URLEncoder.encode(name, "UTF-8") + "=" + URLEncoder.encode(key, "UTF-8")
            }
        }
        return url to headers
    }
}
