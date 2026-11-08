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
 * order, and takes the first answer, all on its single translation thread (so sentences still come back in order).
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
    val ALL: List<TranslationProvider> by lazy { listOf(OpenRouterTranslation, OpusMtTranslation, MlKitTranslation) }

    fun byId(id: String): TranslationProvider? = ALL.firstOrNull { it.id == id }

    /**
     * Engines to try for [source] → [target], in order: only ones that support the pair and are [usable]; online ones
     * only when [online]; the online ones first when [preferOnline], else after the phone's own. Pure (tests).
     */
    fun route(source: String, target: String, online: Boolean, preferOnline: Boolean,
              usable: (TranslationProvider) -> Boolean, all: List<TranslationProvider> = ALL): List<TranslationProvider> {
        if (source == target) return emptyList()
        val can = all.filter { it.supports(source, target) && (!it.online || online) && usable(it) }
        val (cloud, phone) = can.partition { it.online }
        return if (preferOnline) cloud + phone else phone + cloud
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
    const val KEY_FIRST = "or_first"
    const val KEY_TIMEOUT = "or_timeout_s"
    const val DEFAULT_TIMEOUT_S = 10
    const val CONSENT = "consent_tr_openrouter" // in "state": the user said yes to sending sentences to OpenRouter
    override val id = "openrouter"
    override val label = "OpenRouter"
    override val online = true
    override val keyName = "tr_api_key_openrouter"

    override fun supports(source: String, target: String) = source.isNotBlank() && target.isNotBlank()

    override fun usable(c: Context, source: String, target: String): Boolean =
        c.settings().getBoolean(useKey, true) && SecretStore.has(c, keyName) && c.uiState().getBoolean(CONSENT, false)

    private fun name(code: String) = TranslateConfig.lang(code)?.name ?: code

    /** The instruction: translate only, nothing else. */
    fun prompt(source: String, target: String): String =
        "You translate dictated speech from ${name(source)} to ${name(target)}. Reply with the ${name(target)} translation " +
            "only: no quotes, notes, explanations or the original. Keep names and numbers. If it is already ${name(target)}, " +
            "repeat it unchanged."

    /** The request body (no key in it). */
    fun body(text: String, source: String, target: String, model: String): String = JSONObject()
        .put("model", model.ifBlank { DEFAULT_MODEL })
        .put("temperature", 0)
        .put("max_tokens", (text.length * 4 + 64).coerceAtMost(2048))
        .put("provider", JSONObject().put("data_collection", "deny"))
        .put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", prompt(source, target)))
            .put(JSONObject().put("role", "user").put("content", text)))
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

    /** Plain words for a refused request (never the key). */
    fun describe(status: Int): String? = when (status) {
        401, 403 -> "Live translation: OpenRouter refused the key. Check it in Settings → Providers → OpenRouter"
        402 -> "Live translation: the OpenRouter account is out of credits"
        429 -> "Live translation: OpenRouter is busy (rate limit). The phone translates for now"
        else -> null
    }

    override fun translate(t: NoteTranslator, text: String, source: String, target: String, cancelled: () -> Boolean): TranslationProvider.Result {
        val c = t.context
        val key = SecretStore.get(c, keyName)
        if (key.isEmpty() || cancelled()) return TranslationProvider.Result(null)
        val p = c.settings()
        val model = p.getString(KEY_MODEL, null)?.trim().orEmpty().ifEmpty { DEFAULT_MODEL }
        val timeout = (p.getInt(KEY_TIMEOUT, DEFAULT_TIMEOUT_S).coerceIn(3, 30)) * 1000
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(URL_CHAT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = timeout; readTimeout = timeout; doOutput = true
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Title", "Daily Whisper")
            }
            conn.outputStream.use { it.write(body(text, source, target, model).toByteArray()) }
            val status = conn.responseCode
            if (status !in 200..299) {
                Log.w("DailyWhisper", "OpenRouter answered HTTP $status")
                return TranslationProvider.Result(null, describe(status))
            }
            TranslationProvider.Result(parse(conn.inputStream.bufferedReader().use { it.readText() }))
        } catch (e: Exception) {
            Log.w("DailyWhisper", "OpenRouter unreachable: ${e.javaClass.simpleName}")
            TranslationProvider.Result(null)
        } finally {
            conn?.disconnect()
        }
    }
}
