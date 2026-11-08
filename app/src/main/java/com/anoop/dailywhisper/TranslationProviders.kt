package com.anoop.dailywhisper

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * The thin in-app layer every live-translation engine plugs into. To add one: write one object that implements
 * [TranslationProvider] and add it to [TranslationRegistry.ALL] (a plain settings page with its switch and key is made
 * for an online one). NoteTranslator.translateLive asks [TranslationRegistry.route] which engines to try for a pair, in
 * order (Settings → Providers → Translation order; the phone's own always last), and takes the first answer, all on its
 * single translation thread (so sentences still come back in order).
 */
interface TranslationProvider {
    val id: String
    val label: String
    /** Sends text off the phone (needs internet, a key and the user's OK). */
    val online: Boolean
    /** SecretStore name of its API key; null when none is needed. */
    val keyName: String? get() = null
    /** Its "Use …" switch (online ones). */
    val useKey: String get() = "use_tr_$id"
    /** Can translate [source] → [target] at all (perhaps after a download). Codes as in TranslateConfig. */
    fun supports(source: String, target: String): Boolean
    /** Set up and allowed to run for this pair now (key, consent, models on the phone…). Any thread. */
    fun usable(c: Context, source: String, target: String): Boolean
    /** Translation thread only. The translation, or null text with an optional plain [Result.notice] saying why. */
    fun translate(t: NoteTranslator, text: String, source: String, target: String, cancelled: () -> Boolean): Result

    class Result(val text: String?, val notice: String? = null)
}

object TranslationRegistry {
    val ALL: List<TranslationProvider> by lazy {
        listOf(AssemblyAiTranslation, SarvamTranslation, OpenRouterTranslation, OpusMtTranslation, MlKitTranslation)
    }
    /** The default order (Anoop, 9 Nov 2026): AssemblyAI, Sarvam AI (Indian languages), OpenRouter, then the phone. */
    const val DEFAULT_ORDER = "assemblyai,sarvam,openrouter"

    fun byId(id: String): TranslationProvider? = ALL.firstOrNull { it.id == id }

    val online: List<TranslationProvider> get() = ALL.filter { it.online }

    /**
     * The order engines are tried: the online ones in the user's saved order ([saved], comma-separated ids; unknown
     * ids skipped, new ones appended), then the phone's own (Opus-MT, ML Kit), always last: the last resort.
     */
    fun ordered(saved: String?): List<TranslationProvider> {
        val picked = saved.orEmpty().split(',').mapNotNull { byId(it.trim()) }.filter { it.online }.distinct()
        return picked + online.filter { it !in picked } + ALL.filter { !it.online }
    }

    /** [ordered] with [p] moved to the front of the online engines, as saved ids. */
    fun moveFirst(saved: String?, p: TranslationProvider): String =
        (listOf(p) + ordered(saved).filter { it != p && it.online }).joinToString(",") { it.id }

    /**
     * Engines to try for [source] → [target], in [order]: only ones that support the pair and are [usable]; online ones
     * only when [online]. Pure (tests).
     */
    fun route(source: String, target: String, online: Boolean, order: List<TranslationProvider>,
              usable: (TranslationProvider) -> Boolean): List<TranslationProvider> {
        if (source == target) return emptyList()
        return order.filter { it.supports(source, target) && (!it.online || online) && usable(it) }
    }

    /** A language some engine can translate (TranslateConfig's 59, plus Sarvam AI's Indian languages). */
    fun knows(code: String): Boolean = TranslateConfig.lang(code) != null || code in SarvamTranslation.LANGS

    fun languageName(code: String): String = TranslateConfig.lang(code)?.name ?: SarvamTranslation.EXTRA_NAMES[code] ?: code

    /** "Live translation into" choices: every language above, by name. */
    fun choices(): List<Pair<String, String>> =
        (TranslateConfig.options() + SarvamTranslation.EXTRA_NAMES.toList()).distinctBy { it.first }.sortedBy { it.second }
}

/** One HTTPS POST with a JSON body (translation thread). (status, body), or null when unreachable. Never logs the key. */
internal object TranslationHttp {
    fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int, who: String): Pair<Int, String>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = timeoutMs; readTimeout = timeoutMs; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val status = conn.responseCode
            if (status !in 200..299) { Log.w("DailyWhisper", "$who answered HTTP $status"); return status to "" }
            status to conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w("DailyWhisper", "$who unreachable: ${e.javaClass.simpleName}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** "Give up after" for every online translator (Settings → Providers). */
    fun timeoutMs(c: Context): Int = c.settings()[Prefs.TR_TIMEOUT] * 1000
}

/** Chat-completion translation shared by AssemblyAI's LLM Gateway and OpenRouter: the prompt, the body, the reply. */
object ChatTranslation {
    fun prompt(source: String, target: String): String {
        val s = TranslationRegistry.languageName(source); val t = TranslationRegistry.languageName(target)
        return "You translate dictated speech from $s to $t. Reply with the $t translation only: no quotes, notes, " +
            "explanations or the original. Keep names and numbers. If it is already $t, repeat it unchanged."
    }

    fun body(text: String, source: String, target: String, model: String, extra: JSONObject.() -> Unit = {}): String = JSONObject()
        .put("model", model)
        .put("temperature", 0)
        .put("max_tokens", (text.length * 4 + 64).coerceAtMost(2048))
        .put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", prompt(source, target)))
            .put(JSONObject().put("role", "user").put("content", text)))
        .apply(extra)
        .toString()

    /** The translation in a reply, cleaned (surrounding quotes dropped); null when there's none. */
    fun parse(reply: String): String? {
        val o = runCatching { JSONObject(reply) }.getOrNull() ?: return null
        val content = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim()
        if (content.isNullOrEmpty()) return null
        val q = listOf("\"" to "\"", "“" to "”", "「" to "」", "'" to "'")
        return q.firstOrNull { (a, b) -> content.length > 2 && content.startsWith(a) && content.endsWith(b) }
            ?.let { (a, b) -> content.removePrefix(a).removeSuffix(b).trim() } ?: content
    }
}

/**
 * AssemblyAI's LLM Gateway (OpenAI-style chat completions), with the AssemblyAI key and region already set up for
 * speech (Settings → Providers → AssemblyAI) and the same "send it there" OK. Text only. US or EU host, by that
 * region. The gateway keeps nothing (zero data retention) and its model providers don't train on it.
 * Source: assemblyai.com/docs/llm-gateway (Nov 2026).
 */
object AssemblyAiTranslation : TranslationProvider {
    const val KEY_MODEL = "aai_tr_model"
    const val DEFAULT_MODEL = "gemini-2.5-flash-lite" // $0.10 / $0.40 per million tokens in / out, +10% in US/EU (Nov 2026)
    override val id = "assemblyai"
    override val label = "AssemblyAI"
    override val online = true
    override val keyName = AssemblyAiProvider.keyName // the same key as AssemblyAI speech

    fun url(region: String) =
        if (region == "eu") "https://llm-gateway.eu.assemblyai.com/v1/chat/completions" else "https://llm-gateway.assemblyai.com/v1/chat/completions"

    override fun supports(source: String, target: String) = source.isNotBlank() && target.isNotBlank()

    override fun usable(c: Context, source: String, target: String): Boolean =
        c.settings().getBoolean(useKey, true) && SecretStore.has(c, keyName) && c.providerAllowed(SpeechProvider.ASSEMBLYAI)

    fun describe(status: Int): String? = when (status) {
        401, 403 -> "Live translation: AssemblyAI refused the key. Check it in Settings → Providers → AssemblyAI"
        429 -> "Live translation: AssemblyAI is busy (rate limit). Trying the next translator"
        else -> null
    }

    override fun translate(t: NoteTranslator, text: String, source: String, target: String, cancelled: () -> Boolean): TranslationProvider.Result {
        val c = t.context
        val key = SecretStore.get(c, keyName)
        if (key.isEmpty() || cancelled()) return TranslationProvider.Result(null)
        val p = c.settings()
        val model = p.getString(KEY_MODEL, null)?.trim().orEmpty().ifEmpty { DEFAULT_MODEL }
        val region = p.getString(AssemblyAiProvider.KEY_REGION, null) ?: AssemblyAiProvider.DEFAULT_REGION
        val r = TranslationHttp.post(url(region), mapOf("Authorization" to key), ChatTranslation.body(text, source, target, model),
            TranslationHttp.timeoutMs(c), label) ?: return TranslationProvider.Result(null)
        if (r.first !in 200..299) return TranslationProvider.Result(null, describe(r.first))
        return TranslationProvider.Result(ChatTranslation.parse(r.second))
    }
}

/**
 * Sarvam AI text translation (POST api.sarvam.ai/translate), with the Sarvam key already set up for speech and the same
 * "send it to India" OK. Only for pairs with an Indian language (English ↔ Indian, or Indian ↔ Indian). mayura:v1
 * covers English and 10 Indian languages (with styles); sarvam-translate:v1 all 22 scheduled languages (formal only).
 * Source: docs.sarvam.ai/api-reference-docs/text/translate (Nov 2026).
 */
object SarvamTranslation : TranslationProvider {
    const val URL_TRANSLATE = "https://api.sarvam.ai/translate"
    const val KEY_MODEL = "sarvam_tr_model"
    const val KEY_MODE = "sarvam_tr_mode"
    const val AUTO_MODEL = "auto"
    const val MAYURA = "mayura:v1"
    const val SARVAM_TRANSLATE = "sarvam-translate:v1"
    override val id = "sarvam"
    override val label = "Sarvam AI"
    override val online = true
    override val keyName = SarvamProvider.keyName // the same key as Sarvam AI speech

    /** mayura:v1's Indian languages (app codes; Odia is "or" here, "od-IN" to Sarvam). */
    val MAYURA_LANGS = setOf("bn", "gu", "hi", "kn", "ml", "mr", "or", "pa", "ta", "te")
    /** Every Indian language Sarvam translates (sarvam-translate:v1). */
    val LANGS: Set<String> = MAYURA_LANGS + setOf("as", "brx", "doi", "kok", "ks", "mai", "mni", "ne", "sa", "sat", "sd", "ur")
    /** Names for the ones the phone's translators don't list. */
    val EXTRA_NAMES: Map<String, String> = mapOf(
        "as" to "Assamese", "brx" to "Bodo", "doi" to "Dogri", "kok" to "Konkani", "ks" to "Kashmiri", "mai" to "Maithili",
        "mni" to "Manipuri", "ne" to "Nepali", "or" to "Odia", "pa" to "Punjabi", "sa" to "Sanskrit", "sat" to "Santali", "sd" to "Sindhi",
    )
    val MODELS = listOf(AUTO_MODEL to "Automatic (default)", MAYURA to "Mayura v1 (10 languages, styles)",
        SARVAM_TRANSLATE to "Sarvam-Translate v1 (22 languages)")
    val MODES = listOf("formal" to "Formal (default)", "modern-colloquial" to "Everyday", "classic-colloquial" to "Classic everyday",
        "code-mixed" to "Mixed with English")

    /** Sarvam's code for an app code, or null when it can't take it. */
    fun code(app: String): String? = when (app) {
        "en" -> "en-IN"
        "or" -> "od-IN"
        in LANGS -> "$app-IN"
        else -> null
    }

    /** Only pairs with an Indian language, both sides ones Sarvam knows. */
    override fun supports(source: String, target: String) =
        (source in LANGS || target in LANGS) && code(source) != null && code(target) != null

    /** The model for this pair: mayura:v1 when it covers both sides (unless set otherwise), else sarvam-translate:v1. */
    fun model(setting: String, source: String, target: String): String {
        val mayuraOk = listOf(source, target).all { it == "en" || it in MAYURA_LANGS }
        return when {
            setting == MAYURA && mayuraOk -> MAYURA
            setting == SARVAM_TRANSLATE -> SARVAM_TRANSLATE
            mayuraOk -> MAYURA
            else -> SARVAM_TRANSLATE
        }
    }

    fun body(text: String, source: String, target: String, model: String, mode: String): String? {
        val max = if (model == MAYURA) 1000 else 2000
        if (text.length > max) return null
        return JSONObject().put("input", text).put("source_language_code", code(source)).put("target_language_code", code(target))
            .put("model", model).put("mode", if (model == MAYURA) mode else "formal").toString()
    }

    fun parse(reply: String): String? =
        runCatching { JSONObject(reply).optString("translated_text").trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    override fun usable(c: Context, source: String, target: String): Boolean =
        c.settings().getBoolean(useKey, true) && SecretStore.has(c, keyName) && c.sarvamAllowed()

    fun describe(status: Int): String? = when (status) {
        401, 403 -> "Live translation: Sarvam AI refused the key. Check it in Settings → Providers → Sarvam AI"
        429 -> "Live translation: Sarvam AI is out of quota or busy. Trying the next translator"
        else -> null
    }

    override fun translate(t: NoteTranslator, text: String, source: String, target: String, cancelled: () -> Boolean): TranslationProvider.Result {
        val c = t.context
        val key = SecretStore.get(c, keyName)
        if (key.isEmpty() || cancelled()) return TranslationProvider.Result(null)
        val p = c.settings()
        val model = model(p.getString(KEY_MODEL, null) ?: AUTO_MODEL, source, target)
        val body = body(text, source, target, model, p.getString(KEY_MODE, null) ?: "formal") ?: return TranslationProvider.Result(null)
        val r = TranslationHttp.post(URL_TRANSLATE, SarvamConfig.headers(key), body, TranslationHttp.timeoutMs(c), label)
            ?: return TranslationProvider.Result(null)
        if (r.first !in 200..299) return TranslationProvider.Result(null, describe(r.first))
        return TranslationProvider.Result(parse(r.second))
    }
}

/** Opus-MT on the phone (ONNX Runtime), when the pair's models are downloaded and its crash guard allows. */
object OpusMtTranslation : TranslationProvider {
    override val id = "opus"
    override val label = "Opus-MT"
    override val online = false
    override fun supports(source: String, target: String) = OpusMt.route(source, target) != null
    override fun usable(c: Context, source: String, target: String) =
        OpusPacks.readyRoute(c, source, target) != null && OpusPacks.guard(c).blocked() == null
    override fun translate(t: NoteTranslator, text: String, source: String, target: String, cancelled: () -> Boolean) =
        TranslationProvider.Result(t.liveOpus(text, source, target, cancelled))
}

/** Google ML Kit on the phone: English ↔ Chinese and ~55 more (not Malayalam); its ~30 MB pack is fetched on first use. */
object MlKitTranslation : TranslationProvider {
    override val id = "mlkit"
    override val label = "ML Kit"
    override val online = false
    override fun supports(source: String, target: String) = TranslateConfig.mlKit(source) && TranslateConfig.mlKit(target)
    override fun usable(c: Context, source: String, target: String) = true // downloads its pack itself, with a notice
    override fun translate(t: NoteTranslator, text: String, source: String, target: String, cancelled: () -> Boolean): TranslationProvider.Result {
        val (out, why) = t.mlKitLive(text, source, target, cancelled)
        return TranslationProvider.Result(out, why)
    }
}

/**
 * OpenRouter (openrouter.ai), bring your own key: any language pair, through the chat-completions API with a cheap,
 * quick model by default (a setting). Off until a key is saved and the user says yes to sending dictated sentences (text
 * only, never audio or notes) there. Every request asks providers not to keep or train on it (data_collection: deny).
 */
object OpenRouterTranslation : TranslationProvider {
    const val URL_CHAT = "https://openrouter.ai/api/v1/chat/completions"
    const val KEY_MODEL = "or_model"
    const val DEFAULT_MODEL = "google/gemini-2.5-flash-lite" // $0.10 / $0.40 per million tokens in / out (Nov 2026)
    /** Old (bd1e938) settings, only read by SettingsMigration. */
    const val OLD_KEY_FIRST = "or_first"
    const val OLD_KEY_TIMEOUT = "or_timeout_s"
    const val CONSENT = "consent_tr_openrouter" // in "state": the user said yes to sending sentences to OpenRouter
    override val id = "openrouter"
    override val label = "OpenRouter"
    override val online = true
    override val keyName = "tr_api_key_openrouter"

    override fun supports(source: String, target: String) = source.isNotBlank() && target.isNotBlank()

    override fun usable(c: Context, source: String, target: String): Boolean =
        c.settings().getBoolean(useKey, true) && SecretStore.has(c, keyName) && c.uiState().getBoolean(CONSENT, false)

    fun prompt(source: String, target: String): String = ChatTranslation.prompt(source, target)

    /** The request body (no key in it); asks providers not to keep or train on it. */
    fun body(text: String, source: String, target: String, model: String): String =
        ChatTranslation.body(text, source, target, model.ifBlank { DEFAULT_MODEL }) {
            put("provider", JSONObject().put("data_collection", "deny"))
        }

    fun parse(reply: String): String? = ChatTranslation.parse(reply)

    /** Plain words for a refused request (never the key). */
    fun describe(status: Int): String? = when (status) {
        401, 403 -> "Live translation: OpenRouter refused the key. Check it in Settings → Providers → OpenRouter"
        402 -> "Live translation: the OpenRouter account is out of credits"
        429 -> "Live translation: OpenRouter is busy (rate limit). Trying the next translator"
        else -> null
    }

    override fun translate(t: NoteTranslator, text: String, source: String, target: String, cancelled: () -> Boolean): TranslationProvider.Result {
        val c = t.context
        val key = SecretStore.get(c, keyName)
        if (key.isEmpty() || cancelled()) return TranslationProvider.Result(null)
        val model = c.settings().getString(KEY_MODEL, null)?.trim().orEmpty().ifEmpty { DEFAULT_MODEL }
        val r = TranslationHttp.post(URL_CHAT, mapOf("Authorization" to "Bearer $key", "X-Title" to "Daily Whisper"),
            body(text, source, target, model), TranslationHttp.timeoutMs(c), label) ?: return TranslationProvider.Result(null)
        if (r.first !in 200..299) return TranslationProvider.Result(null, describe(r.first))
        return TranslationProvider.Result(parse(r.second))
    }
}
