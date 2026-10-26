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
class ChoicePref(key: String, title: String, summary: String, val def: String, val options: List<Pair<String, String>>,
                 val searchable: Boolean = false) :
    Pref(key, title, summary)

/** A secret kept encrypted in [SecretStore] (never in plain settings, never shown back). Default: not set. */
class SecretPref(key: String, title: String, summary: String) : Pref(key, title, summary)

/** A row with its own controls on the Settings screen (model download, run setup again). Stores nothing itself. */
class ActionPref(key: String, title: String, summary: String) : Pref(key, title, summary)

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

    // Live dictation (the composer mic): Deepgram streaming. Only the useful knobs are settings (Anoop's call);
    // the protocol details are fixed in LiveDictationConfig.
    val SHOW_MIC = BoolPref("show_mic", "Show mic button",
        "A small mic in the composer that streams your voice to Deepgram and types the words live", true)
    val STT_API_KEY = SecretPref(SecretStore.STT_API_KEY, "Deepgram API key",
        "Encrypted on this phone with Android Keystore. Reset to defaults keeps it; use Clear to remove it")
    val DG_MODEL = TextPref("dg_model", "Model",
        "nova-3, nova-2… always uses the newest version of the model", LiveDictationConfig.DEFAULT_MODEL, "nova-3")
    val DG_LANGUAGE = ChoicePref("dg_language", "Language", "",
        LiveDictationConfig.DEFAULT_LANGUAGE, LiveDictationConfig.LANGUAGES, searchable = true)
    val KEEP_SCREEN_ON = BoolPref("keep_screen_on", "Keep screen on while dictating",
        "The screen won't dim or lock while the mic is live", true)
    val DG_SMART_FORMAT = BoolPref("dg_smart_format", "Smart formatting",
        "Punctuation, capitals and numbers", LiveDictationConfig.DEFAULT_SMART_FORMAT)
    val DG_REGION = ChoicePref("dg_region", "Region", "Where Deepgram processes your audio",
        LiveDictationConfig.DEFAULT_REGION, LiveDictationConfig.REGIONS.map { it.first to it.second })

    // Notes
    val NOTE_TEXT_SIZE = IntPref("note_text_size", "Note text size", "", 16, 12, 28, 1, " sp")
    val PREVIEW_LINES = IntPref("preview_lines", "Preview length",
        "Lines of each note shown in the list", 5, 1, 15, 1, " lines")
    val CONFIRM_DELETE = BoolPref("confirm_delete", "Ask before deleting",
        "Off: delete right away, with an Undo bar", false)
    val UNDO_SECONDS = IntPref("undo_seconds", "Undo bar time", "", 6, 2, 20, 1, " s")
    val HIGHLIGHT_DEFAULT = ChoicePref("highlight_default", "Highlight colour",
        "Used when you tap Highlight on a note (you can still pick another)", Highlight.DEFAULT,
        Highlight.PALETTE.map { it.key to it.name })
    val SEARCH_ARCHIVED = BoolPref("search_archived", "Search includes archived notes",
        "Deleted notes are never searched", true)

    // Translation (fully offline; tap a note's text to translate it). Languages store codes, show names.
    val TR_SOURCE = ChoicePref("tr_source", "I mostly write in", "", TranslateConfig.DEFAULT_SOURCE,
        TranslateConfig.options(null), searchable = true)
    val TR_TARGET = ChoicePref("tr_target", "Translate notes to", "", TranslateConfig.DEFAULT_TARGET,
        TranslateConfig.options(null), searchable = true)
    val TR_BACKEND = ChoicePref("tr_backend", "Translator", "Both run on this phone. Your notes never leave it",
        TranslateConfig.DEFAULT_BACKEND, listOf(
            TranslateConfig.BACKEND_MLKIT to "Built-in (no extra download)",
            TranslateConfig.BACKEND_HYMT to "Hy-MT, higher quality (one-time 440 MB download)"))
    val HYMT_MODEL = ActionPref("hymt_model", "Hy-MT model", "Tencent Hy-MT1.5 1.8B, 1.25-bit, kept in the app's own storage. Tencent HY is licensed under the Tencent HY Community License; Tencent isn't affiliated with this app")
    val TR_WIFI_ONLY = BoolPref("tr_wifi_only", "Download on Wi-Fi only",
        "For the Hy-MT model and the built-in translator's language packs", true)
    val HYMT_THREADS = IntPref("hymt_threads", "Hy-MT speed",
        "CPU threads it uses. More is quicker but warmer", 4, 1, 8, 1, " threads")
    val RERUN_SETUP = ActionPref("rerun_setup", "Run first-time setup again", "Languages and translator, in three quick steps")

    // Look & layout
    val THEME = ChoicePref("theme", "Theme", "The sun/moon button at the top flips light and dark", ThemeMode.SYSTEM, listOf(
        ThemeMode.SYSTEM to "Follow system", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"))
    val MAX_WIDTH = IntPref("max_width", "Max content width",
        "Keeps lines readable on wide screens such as the unfolded Fold", 720, 400, 1400, 20, " dp")

    val sections: List<Pair<String, List<Pref>>> = listOf(
        "Composer" to listOf(AUTO_FOCUS, SHOW_KEYBOARD, APPEND_DICTATION, ENTER_SAVES, AUTO_SAVE,
            AUTO_SAVE_SECONDS, COMPOSER_TEXT_SIZE, COMPOSER_MAX_LINES),
        "Live dictation" to listOf(SHOW_MIC, STT_API_KEY, DG_MODEL, DG_LANGUAGE, DG_SMART_FORMAT, DG_REGION, KEEP_SCREEN_ON),
        "Notes" to listOf(NOTE_TEXT_SIZE, PREVIEW_LINES, HIGHLIGHT_DEFAULT, SEARCH_ARCHIVED, CONFIRM_DELETE, UNDO_SECONDS),
        "Translation" to listOf(TR_SOURCE, TR_TARGET, TR_BACKEND, HYMT_MODEL, TR_WIFI_ONLY, HYMT_THREADS, RERUN_SETUP),
        "Look & layout" to listOf(THEME, MAX_WIDTH),
    )
}

fun Context.settings(): SharedPreferences = getSharedPreferences("settings", Context.MODE_PRIVATE).also { migrateSettings(it) }
fun Context.uiState(): SharedPreferences = getSharedPreferences("state", Context.MODE_PRIVATE)

private const val KEY_SETTINGS_VERSION = "settings_version"
private const val SETTINGS_VERSION = 4 // 3 = v0.1.5: generic live-dictation settings removed; 4 = language picker

/**
 * One-time clean-up of saved settings. The version marker lives in the "state" file so "Reset to defaults"
 * (which clears "settings") doesn't re-run it. The API key is in SecretStore and is never touched.
 */
private fun Context.migrateSettings(p: SharedPreferences) {
    val state = uiState()
    if (state.getInt(KEY_SETTINGS_VERSION, 1) >= SETTINGS_VERSION) return
    val edit = p.edit()
    if (state.getInt(KEY_SETTINGS_VERSION, 1) < 3) LiveDictationConfig.OLD_KEYS.forEach { edit.remove(it) }
    // v0.1.5 stored the language as free text: keep it if it matches a listed code (any case), else use the default.
    runCatching { p.getString(Prefs.DG_LANGUAGE.key, null) }.getOrNull()?.let { typed ->
        val code = LiveDictationConfig.canonicalLanguage(typed)
        if (code != null) edit.putString(Prefs.DG_LANGUAGE.key, code) else edit.remove(Prefs.DG_LANGUAGE.key)
    }
    edit.apply()
    state.edit().putInt(KEY_SETTINGS_VERSION, SETTINGS_VERSION).apply()
}

operator fun SharedPreferences.get(p: BoolPref): Boolean = getBoolean(p.key, p.def)
operator fun SharedPreferences.get(p: IntPref): Int = getInt(p.key, p.def).coerceIn(p.min, p.max)
operator fun SharedPreferences.get(p: TextPref): String = getString(p.key, null) ?: p.def
operator fun SharedPreferences.get(p: ChoicePref): String =
    getString(p.key, null)?.takeIf { v -> p.options.any { it.first == v } } ?: p.def
