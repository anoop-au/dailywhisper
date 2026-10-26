package com.anoop.dailywhisper

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns one incoming JSON message from a streaming STT server into a transcript update, using the
 * field paths from Settings (so it fits many providers without provider-specific code).
 *
 * - [textPath]: dotted path to the text, numbers index arrays, e.g. "channel.alternatives.0.transcript".
 * - [finalPath] / [finalValue]: the message is final when the value at [finalPath] equals [finalValue]
 *   (compared as text, case-insensitive, so true / "true" / "AddTranscript" all work).
 *   If [finalPath] is blank or the message doesn't have that field, the message counts as final.
 */
class TranscriptParser(
    private val textPath: String, private val finalPath: String, private val finalValue: String,
    /** Optional path to a detected language code (Sarvam's "language" with language_code=auto). Blank = none. */
    private val languagePath: String = "",
) {

    data class Update(val text: String, val isFinal: Boolean, val language: String? = null)

    /** Returns null for messages that carry no transcript (metadata, acks, non-JSON, ...). */
    fun parse(message: String): Update? {
        val root: Any = runCatching { JSONObject(message) }.getOrNull() ?: return null
        val textValue = resolve(root, textPath) ?: return null
        if (textValue == JSONObject.NULL || textValue is JSONObject || textValue is JSONArray) return null
        val text = textValue.toString()
        val fin = finalPath.isBlank() || resolve(root, finalPath).let { v ->
            v == null || v == JSONObject.NULL || v.toString().equals(finalValue.trim(), ignoreCase = true)
        }
        val lang = if (languagePath.isBlank()) null else resolve(root, languagePath)
            ?.takeIf { it is String && it.isNotBlank() }?.toString()
        return Update(text, fin, lang)
    }

    companion object {
        fun resolve(root: Any, path: String): Any? {
            var cur: Any? = root
            for (part in path.trim().split('.').filter { it.isNotEmpty() }) {
                cur = when (cur) {
                    is JSONObject -> if (cur.has(part)) cur.get(part) else return null
                    is JSONArray -> part.toIntOrNull()?.takeIf { it in 0 until cur.length() }?.let { cur.get(it) } ?: return null
                    else -> return null
                }
            }
            return cur
        }
    }
}
