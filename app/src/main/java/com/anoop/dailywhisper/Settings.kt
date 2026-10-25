package com.anoop.dailywhisper

import android.content.Context
import android.content.SharedPreferences

/** Every tunable value: a key, a default, a range, and the words shown on the Settings screen. */
sealed class Pref(val key: String, val title: String, val summary: String)

class BoolPref(key: String, title: String, summary: String, val def: Boolean) : Pref(key, title, summary)

/** Free text. [multiline] for JSON messages. */
class TextPref(key: String, title: String, summary: String, val def: String, val hint: String = "", val multiline: Boolean = false) :
    Pref(key, title, summary)

/** One of a fixed set of [options] (stored value to label). */
class ChoicePref(key: String, title: String, summary: String, val def: String, val options: List<Pair<String, String>>) :
    Pref(key, title, summary)

/** A secret kept encrypted in [SecretStore] (never in plain settings, never shown back). Default: not set. */
class SecretPref(key: String, title: String, summary: String) : Pref(key, title, summary)

class IntPref(
    key: String, title: String, summary: String,
    val def: Int, val min: Int, val max: Int, val step: Int = 1, val unit: String = "",
) : Pref(key, title, summary)

object Prefs {
    // Composer
    val AUTO_FOCUS = BoolPref("auto_focus", "Focus the composer on open",
        "The cursor waits in the bottom box, so Vion dictates straight into it", true)
    val SHOW_KEYBOARD = BoolPref("show_keyboard", "Show keyboard on open",
        "Turn off to keep the cursor ready without the keyboard covering your notes", true)
    val APPEND_DICTATION = BoolPref("append_dictation", "Append dictation instead of replacing",
        "If another app replaces the whole composer text, keep what was there and add the new words after it", true)
    val ENTER_SAVES = BoolPref("enter_saves", "Keyboard Enter saves the note",
        "Off: Enter starts a new line", false)
    val AUTO_SAVE = BoolPref("auto_save", "Auto-save the composer",
        "Save what's in the composer as a note after a quiet pause", false)
    val AUTO_SAVE_SECONDS = IntPref("auto_save_seconds", "Auto-save pause",
        "Seconds with no change before auto-save", 8, 3, 60, 1, " s")
    val COMPOSER_TEXT_SIZE = IntPref("composer_text_size", "Composer text size", "", 17, 12, 28, 1, " sp")
    val COMPOSER_MAX_LINES = IntPref("composer_max_lines", "Composer height",
        "Lines it grows to before scrolling", 8, 2, 20, 1, " lines")

    // Live dictation (the composer mic): streams mic audio to a WebSocket speech-to-text service.
    val SHOW_MIC = BoolPref("show_mic", "Show mic button",
        "A small mic in the composer that streams your voice to the service below and types the words live", true)
    val STT_URL = TextPref("stt_url", "Streaming URL",
        "WebSocket address of your live speech-to-text service. {sample_rate} is filled in for you", "", "wss://…")
    val STT_API_KEY = SecretPref(SecretStore.STT_API_KEY, "API key",
        "Encrypted on this phone with Android Keystore. Reset to defaults keeps it; use Clear to remove it")
    val STT_AUTH = ChoicePref("stt_auth", "How to send the key", "", "bearer", listOf(
        "bearer" to "Authorization: Bearer <key>",
        "token" to "Authorization: Token <key>",
        "header" to "Custom header (name below)",
        "query" to "URL parameter (name below)",
        "none" to "Don't send a key",
    ))
    val STT_AUTH_NAME = TextPref("stt_auth_name", "Header or parameter name",
        "Used by \"Custom header\" and \"URL parameter\"", "x-api-key")
    val STT_SAMPLE_RATE = IntPref("stt_sample_rate", "Audio sample rate",
        "Mono 16-bit PCM, sent as binary frames", 16000, 8000, 48000, 1000, " Hz")
    val STT_CHUNK_MS = IntPref("stt_chunk_ms", "Audio chunk size",
        "How much audio goes in each frame", 100, 20, 500, 10, " ms")
    val STT_START_MSG = TextPref("stt_start_msg", "Message sent on connect",
        "Optional JSON sent once connected. {sample_rate} is filled in", "", "{\"type\":\"start\"}", multiline = true)
    val STT_END_MSG = TextPref("stt_end_msg", "Message sent when you stop",
        "Optional, e.g. {\"type\":\"end\"}. Empty: just close", "", "{\"type\":\"end\"}", multiline = true)
    val STT_TEXT_FIELD = TextPref("stt_text_field", "Transcript field",
        "JSON field with the words. Dots go deeper, numbers pick list items, e.g. channel.alternatives.0.transcript", "text")
    val STT_FINAL_FIELD = TextPref("stt_final_field", "Final-result field",
        "JSON field that marks a result as final. Empty, or missing in a message: every message counts as final", "is_final")
    val STT_FINAL_VALUE = TextPref("stt_final_value", "Final-result value",
        "Value of that field meaning final", "true")

    // Notes
    val NOTE_TEXT_SIZE = IntPref("note_text_size", "Note text size", "", 16, 12, 28, 1, " sp")
    val PREVIEW_LINES = IntPref("preview_lines", "Preview length",
        "Lines of each note shown in the list", 5, 1, 15, 1, " lines")
    val CONFIRM_DELETE = BoolPref("confirm_delete", "Ask before deleting",
        "Off: delete right away, with an Undo bar", false)
    val UNDO_SECONDS = IntPref("undo_seconds", "Undo bar time", "", 6, 2, 20, 1, " s")

    // Layout
    val MAX_WIDTH = IntPref("max_width", "Max content width",
        "Keeps lines readable on wide screens such as the unfolded Fold", 720, 400, 1400, 20, " dp")

    val sections: List<Pair<String, List<Pref>>> = listOf(
        "Composer" to listOf(AUTO_FOCUS, SHOW_KEYBOARD, APPEND_DICTATION, ENTER_SAVES, AUTO_SAVE,
            AUTO_SAVE_SECONDS, COMPOSER_TEXT_SIZE, COMPOSER_MAX_LINES),
        "Live dictation" to listOf(SHOW_MIC, STT_URL, STT_API_KEY, STT_AUTH, STT_AUTH_NAME, STT_SAMPLE_RATE, STT_CHUNK_MS,
            STT_START_MSG, STT_END_MSG, STT_TEXT_FIELD, STT_FINAL_FIELD, STT_FINAL_VALUE),
        "Notes" to listOf(NOTE_TEXT_SIZE, PREVIEW_LINES, CONFIRM_DELETE, UNDO_SECONDS),
        "Layout" to listOf(MAX_WIDTH),
    )
}

fun Context.settings(): SharedPreferences = getSharedPreferences("settings", Context.MODE_PRIVATE)
fun Context.uiState(): SharedPreferences = getSharedPreferences("state", Context.MODE_PRIVATE)

operator fun SharedPreferences.get(p: BoolPref): Boolean = getBoolean(p.key, p.def)
operator fun SharedPreferences.get(p: IntPref): Int = getInt(p.key, p.def).coerceIn(p.min, p.max)
operator fun SharedPreferences.get(p: TextPref): String = getString(p.key, null) ?: p.def
operator fun SharedPreferences.get(p: ChoicePref): String =
    getString(p.key, null)?.takeIf { v -> p.options.any { it.first == v } } ?: p.def
