package com.anoop.dailywhisper

import java.net.URLEncoder
import org.json.JSONObject

/**
 * The thin in-app layer every speech-to-text provider plugs into. To add a provider: write one object that implements
 * [SpeechProvider] and add it to [SpeechRegistry.ALL]. The app then lists it under Settings → Providers, keeps its key
 * in SecretStore (by [SpeechProvider.keyName]), and SpeechSlots/LiveDictation route to it like the others.
 * Pure Kotlin (no Android) so tools/ws-harness can compile it.
 */
interface SpeechProvider {
    /** Stable id, used in settings keys. Never change it once released. */
    val id: String
    val label: String
    /** False only for the on-phone engine: no key, no network, used as the fallback. */
    val online: Boolean get() = true
    /** SecretStore name of its API key; null when none is needed. */
    val keyName: String? get() = null
    /** The "Use …" switch on its page. */
    val useKey: String get() = "use_$id"
    /** Short line on the Providers list, e.g. "Indian languages". */
    val tagline: String get() = ""
    /** Can dictate [language] (a SpeechSlots.LANGUAGES code) at all. */
    fun supports(language: String): Boolean
    /** Languages only this provider adds to the dictation list (code to plain name); codes already listed are ignored. */
    val extraLanguages: List<Pair<String, String>> get() = emptyList()
    /** Where the audio is processed with these options, in plain words ("the US"); null: it never leaves the phone. */
    fun place(o: SttOptions): String? = null
    /** Audio sent with these options stays in the user's [region] (a Residency code). */
    fun inRegion(region: String, o: SttOptions): Boolean = !online
    /** Online providers: the connection for one session, or null when these options can't dictate [language]. */
    fun protocol(key: String, language: String, pauseMs: Int, o: SttOptions): SttProtocol? = null
    /** The server says it's ready for audio (only checked when the protocol waits for it). */
    fun isReady(message: String): Boolean = true
    /** A fatal error from the server, as (HTTP-like status or 0, short code); null for anything else. */
    fun fatalError(message: String): Pair<Int, String>? = null

    companion object {
        val DEEPGRAM: SpeechProvider get() = DeepgramProvider
        val SARVAM: SpeechProvider get() = SarvamProvider
        val ASSEMBLYAI: SpeechProvider get() = AssemblyAiProvider
        val ON_DEVICE: SpeechProvider get() = OnDeviceProvider
    }
}

/** A provider's own settings for one session, read by key (the app passes the saved settings; defaults live here). */
class SttOptions(private val values: Map<String, Any?> = emptyMap()) {
    fun str(key: String, def: String): String = (values[key] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: def
    fun bool(key: String, def: Boolean): Boolean = values[key] as? Boolean ?: def
}

/** Every speech provider the app knows, in the default order they are tried. On this phone is always last. */
object SpeechRegistry {
    val ALL: List<SpeechProvider> by lazy { listOf(DeepgramProvider, SarvamProvider, AssemblyAiProvider, OnDeviceProvider) }

    val online: List<SpeechProvider> get() = ALL.filter { it.online }

    fun byId(id: String): SpeechProvider? = ALL.firstOrNull { it.id == id }

    /**
     * The order the mic tries them: the online providers in the user's saved order ([saved], comma-separated ids;
     * unknown ids are skipped, new providers join at the end), then the on-phone engine (only ever a fallback).
     */
    fun ordered(saved: String?): List<SpeechProvider> {
        val picked = saved.orEmpty().split(',').mapNotNull { byId(it.trim()) }.filter { it.online }.distinct()
        return picked + online.filter { it !in picked } + ALL.filter { !it.online }
    }

    /** [ordered] with [p] moved to the front of the online providers, as saved ids. */
    fun moveFirst(saved: String?, p: SpeechProvider): String =
        (listOf(p) + ordered(saved).filter { it != p && it.online }).joinToString(",") { it.id }
}

/** Speech slot "Deepgram": nova-3 streaming. See LiveDictationConfig for the wire details. */
object DeepgramProvider : SpeechProvider {
    const val KEY_MODEL = "dg_model"
    const val KEY_REGION = "dg_region"
    const val KEY_SMART_FORMAT = "dg_smart_format"
    override val id = "deepgram"
    override val label = "Deepgram"
    override val keyName = "stt_api_key" // SecretStore.STT_API_KEY; name kept from v0.1.5
    override fun supports(language: String) = LiveDictationConfig.LANGUAGES.any { it.first == language }
    private fun region(o: SttOptions) = o.str(KEY_REGION, LiveDictationConfig.DEFAULT_REGION)
    override fun place(o: SttOptions) = Residency.placeOf(region(o))
    override fun inRegion(region: String, o: SttOptions) = Residency.inRegion(region, region(o))
    override fun protocol(key: String, language: String, pauseMs: Int, o: SttOptions) = SttProtocol.deepgram(
        key, region(o), o.str(KEY_MODEL, LiveDictationConfig.DEFAULT_MODEL), language,
        o.bool(KEY_SMART_FORMAT, LiveDictationConfig.DEFAULT_SMART_FORMAT), pauseMs,
    )
}

/** Sarvam AI realtime (India): Malayalam and the other Indian languages, Auto and code-mixed output. */
object SarvamProvider : SpeechProvider {
    const val KEY_MODEL = "sarvam_model"
    const val KEY_MODE = "sarvam_mode"
    const val KEY_CODEMIX = "sarvam_codemix"
    override val id = "sarvam"
    override val label = "Sarvam AI"
    override val keyName = "stt_api_key_2" // SecretStore.SLOT2_API_KEY
    override val tagline = "Indian languages"
    override fun supports(language: String) = SarvamConfig.supports(language)
    override val extraLanguages = listOf(
        "ml" to "Malayalam", "or" to "Odia", "kok" to "Konkani", "ks" to "Kashmiri", "sd" to "Sindhi", "sa" to "Sanskrit",
        "sat" to "Santali", "mni" to "Manipuri", "brx" to "Bodo", "mai" to "Maithili", "doi" to "Dogri",
    )
    override fun place(o: SttOptions) = "India"
    override fun inRegion(region: String, o: SttOptions) = Residency.sarvamInRegion(region)
    override fun protocol(key: String, language: String, pauseMs: Int, o: SttOptions) = SttProtocol.sarvam(
        key, o.str(KEY_MODEL, SarvamConfig.DEFAULT_MODEL),
        SarvamConfig.mode(o.str(KEY_MODE, SarvamConfig.DEFAULT_MODE), o.bool(KEY_CODEMIX, false), language), language, pauseMs,
    )
    override fun isReady(message: String) =
        runCatching { JSONObject(message).optString("event") }.getOrNull() == SarvamConfig.READY_EVENT
    override fun fatalError(message: String): Pair<Int, String>? {
        val o = runCatching { JSONObject(message) }.getOrNull() ?: return null
        if (o.optString("event") != "error" || !o.optBoolean("is_fatal", false)) return null
        return o.optInt("status_code", 0) to o.optString("code", "error")
    }
}

/**
 * AssemblyAI Universal Streaming (v3 WebSocket), bring your own key. Raw PCM16 at 16 kHz in binary frames, the key in
 * the Authorization header (no "Bearer"), "Begin" when ready, "Turn" messages (a turn is final once it is formatted),
 * {"type":"Terminate"} to end. Processed in the US or the EU (its data-residency hosts). No Malayalam.
 * Source: assemblyai.com/docs/api-reference/streaming-api (Nov 2026).
 */
object AssemblyAiProvider : SpeechProvider {
    const val KEY_MODEL = "aai_model"
    const val KEY_REGION = "aai_region"
    const val DEFAULT_MODEL = "universal-3-6-pro"
    const val DEFAULT_REGION = "us"
    const val END_MESSAGE = """{"type":"Terminate"}"""
    override val id = "assemblyai"
    override val label = "AssemblyAI"
    override val keyName = "stt_api_key_assemblyai"
    override val tagline = "Chinese, Japanese, Hindi and 29 more"

    /** Models (stored id to label) and the languages each takes ([langs]: its language_codes; null = English only). */
    class Model(val id: String, val name: String, val langs: Set<String>)
    private val PRO_36 = setOf("af", "ar", "yue", "ca", "da", "nl", "en", "et", "fi", "fr", "gl", "de", "he", "hi", "it", "ja",
        "ko", "zh", "mr", "no", "nn", "fa", "pt", "ro", "ru", "es", "sv", "tr", "ur", "vi", "xh", "zu")
    private val PRO_35 = PRO_36 - setOf("af", "yue", "et", "gl", "ko", "mr", "nn", "fa", "ro", "ru", "ur", "xh", "zu")
    val MODELS: List<Model> = listOf(
        Model("universal-3-6-pro", "Universal-3.6 Pro (default, 32 languages)", PRO_36),
        Model("universal-3-5-pro", "Universal-3.5 Pro", PRO_35),
        Model("universal-streaming-multilingual", "Universal Streaming Multilingual (6 languages)", setOf("en", "es", "fr", "de", "it", "pt")),
        Model("universal-streaming-english", "Universal Streaming English", setOf("en")),
    )
    val REGIONS: List<Pair<String, String>> = listOf("us" to "US", "eu" to "EU")

    private fun model(o: SttOptions) = MODELS.firstOrNull { it.id == o.str(KEY_MODEL, DEFAULT_MODEL) } ?: MODELS.first()
    private fun region(o: SttOptions) = o.str(KEY_REGION, DEFAULT_REGION).takeIf { r -> REGIONS.any { it.first == r } } ?: DEFAULT_REGION

    /** The app's dictation code as an AssemblyAI language code: "en-AU" → "en", "zh-CN" → "zh", "zh-HK" → "yue". */
    fun languageCode(language: String): String? {
        val l = language.trim()
        if (l.equals("zh-HK", ignoreCase = true)) return "yue"
        val base = l.substringBefore('-').lowercase()
        return base.takeIf { it in PRO_36 }
    }

    /** Deepgram's "multi" means "any of its languages": AssemblyAI does that natively (no steering). */
    override fun supports(language: String) = language == SpeechSlots.AUTO_FALLBACK || languageCode(language) != null
    override val extraLanguages = listOf(
        "af" to "Afrikaans", "ar" to "Arabic", "fa" to "Persian", "he" to "Hebrew", "mr" to "Marathi", "ur" to "Urdu",
        "xh" to "Xhosa", "zu" to "Zulu",
    )
    override fun place(o: SttOptions) = if (region(o) == "eu") "the EU" else "the US"
    override fun inRegion(region: String, o: SttOptions) = Residency.region(region).home == region(o)

    fun url(model: String, language: String, pauseMs: Int, region: String): String? {
        val m = MODELS.firstOrNull { it.id == model } ?: MODELS.first()
        val multi = language == SpeechSlots.AUTO_FALLBACK
        val code = if (multi) null else languageCode(language) ?: return null
        if (code != null && code !in m.langs) return null
        if (multi && m.langs.size == 1) return null
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val pause = pauseMs.coerceIn(SpeechSlots.MIN_PAUSE_MS, SpeechSlots.MAX_PAUSE_MS)
        val host = if (region == "eu") "streaming.eu.assemblyai.com" else "streaming.us.assemblyai.com"
        val steer = if (code != null && m.id.contains("-pro")) "&language_codes=${enc("[\"$code\"]")}" else ""
        return "wss://$host/v3/ws?sample_rate=${LiveDictationConfig.SAMPLE_RATE}&encoding=pcm_s16le&speech_model=${enc(m.id)}" +
            "&format_turns=true&max_turn_silence=$pause$steer"
    }

    override fun protocol(key: String, language: String, pauseMs: Int, o: SttOptions): SttProtocol? {
        val u = url(model(o).id, language, pauseMs, region(o)) ?: return null
        return SttProtocol(this, u, if (key.isEmpty()) emptyMap() else mapOf("Authorization" to key), END_MESSAGE,
            jsonAudio = false, waitForReady = true, parser = Parser())
    }

    override fun isReady(message: String) = runCatching { JSONObject(message).optString("type") }.getOrNull() == "Begin"

    override fun fatalError(message: String): Pair<Int, String>? {
        val o = runCatching { JSONObject(message) }.getOrNull() ?: return null
        val type = o.optString("type")
        if (type != "Error" && !o.has("error")) return null
        return 0 to (o.optString("error").ifEmpty { "error" })
    }

    /** "Turn" messages: the turn's words so far; final once end_of_turn and formatted (so a turn is never typed twice). */
    class Parser : TranscriptParser("transcript", "end_of_turn", "true") {
        override fun parse(message: String): Update? {
            val o = runCatching { JSONObject(message) }.getOrNull() ?: return null
            if (o.optString("type") != "Turn") return null
            val fin = o.optBoolean("end_of_turn", false) && o.optBoolean("turn_is_formatted", true)
            return Update(o.optString("transcript"), fin, null)
        }
    }
}

/** On this phone (Sherpa-ONNX speech packs): no key, audio never leaves the phone. Only used as the fallback. */
object OnDeviceProvider : SpeechProvider {
    override val id = "ondevice"
    override val label = "On this phone"
    override val online = false
    override val tagline = "No internet needed"
    override fun supports(language: String) = SpeechPackCatalog.packsFor(language).isNotEmpty()
}
