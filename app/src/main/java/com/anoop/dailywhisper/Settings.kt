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
                 val searchable: Boolean = false,
                 /** Optional one-line hint per option code, shown under its name in the picker and as the value. */
                 val hints: Map<String, String> = emptyMap()) :
    Pref(key, title, summary)

/** A secret kept encrypted in [SecretStore] (never in plain settings, never shown back). Default: not set. */
class SecretPref(key: String, title: String, summary: String) : Pref(key, title, summary)

/** A row that runs an action on the Settings screen (run setup again). Stores nothing itself. */
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

    // Live dictation (the composer mic): "bring your own key" with 4 ordered speech slots (SpeechSlots). Slot 1 Deepgram,
    // slot 2 Sarvam AI, 3 and 4 empty for now. Only the useful knobs are settings (Anoop's call); protocol details are fixed
    // in LiveDictationConfig / SarvamConfig.
    val SHOW_MIC = BoolPref("show_mic", "Mic button",
        "A small mic in the composer that streams your voice to your speech provider and types the words live", true)
    // Stored under the old Deepgram key so earlier choices carry over; the list now also has Sarvam AI's languages.
    val DG_LANGUAGE = ChoicePref("dg_language", "Language", "",
        LiveDictationConfig.DEFAULT_LANGUAGE, SpeechSlots.LANGUAGES, searchable = true, hints = SpeechSlots.LANGUAGE_HINTS)
    // On the Sarvam AI page; only used when Language is "Auto (Sarvam AI)" (other languages use slot 2's Output setting).
    val SARVAM_CODEMIX = BoolPref("sarvam_codemix", "Keep English words in English",
        "Code-mixed: for Malayalam and English in one sentence, with Language on Auto (Sarvam AI). Off by default", false)
    val PAUSE_MS = IntPref("pause_ms", "Sentence pause",
        "How long you can stop to think before the sentence is closed with . or ?", SpeechSlots.DEFAULT_PAUSE_MS,
        SpeechSlots.MIN_PAUSE_MS, SpeechSlots.MAX_PAUSE_MS, 100, " ms")
    val KEEP_SCREEN_ON = BoolPref("keep_screen_on", "Keep screen on",
        "The screen won't dim or lock while the mic is live", true)
    // Region and consent are facts, not tunables: kept in "state" so Reset to defaults leaves them alone.
    val REGION = ActionPref("region", "Region", "")

    // Speech slot 1: Deepgram.
    val SLOT1_ON = BoolPref("slot1_on", "Use Deepgram", "Tried first", true)
    val STT_API_KEY = SecretPref(SecretStore.STT_API_KEY, "API key",
        "Encrypted on this phone with Android Keystore. Reset to defaults keeps it; use Clear to remove it")
    val DG_MODEL = TextPref("dg_model", "Model",
        "nova-3, nova-2… always uses the newest version of the model", LiveDictationConfig.DEFAULT_MODEL, "nova-3")
    val DG_SMART_FORMAT = BoolPref("dg_smart_format", "Smart formatting",
        "Punctuation and numbers", LiveDictationConfig.DEFAULT_SMART_FORMAT)
    val RESIDENCY = ActionPref("residency", "Where your voice goes", "")
    val DG_REGION = ChoicePref("dg_region", "Processing region", "Where Deepgram processes your audio. Set from your region",
        LiveDictationConfig.DEFAULT_REGION, LiveDictationConfig.REGIONS.map { it.first to it.second })

    // Speech slot 2: Sarvam AI (India). Used when slot 1 can't: no key, can't connect, or a language Deepgram lacks.
    val SLOT2_ON = BoolPref("slot2_on", "Use Sarvam AI", "Used when slot 1 can't, and for Malayalam and other Indian languages", true)
    val SLOT2_KEY = SecretPref(SecretStore.SLOT2_API_KEY, "API key",
        "Encrypted on this phone with Android Keystore. Reset to defaults keeps it; use Clear to remove it")
    val SARVAM_MODEL = ChoicePref("sarvam_model", "Model", "", SarvamConfig.DEFAULT_MODEL, SarvamConfig.MODELS)
    val SARVAM_MODE = ChoicePref("sarvam_mode", "Output", "How the words are written", SarvamConfig.DEFAULT_MODE, SarvamConfig.MODES)
    val SARVAM_RESIDENCY = ActionPref("sarvam_residency", "Where your voice goes", "")

    // Speech slot 3: on this phone (Sherpa-ONNX). No key; audio never leaves the phone. Needs a speech pack.
    val SLOT3_ON = BoolPref("slot3_on", "Use On this phone", "Tried last, and used when there's no internet", true)
    val SPEECH_PACKS = ActionPref("speech_packs", "Speech packs", "")
    val OFFLINE_SWITCH = BoolPref("offline_switch", "Switch when the internet drops",
        "If the connection goes while you dictate, carry on with On this phone", true)
    val OD_THREADS = IntPref("od_threads", "Processor cores",
        "How many cores On this phone uses. More is faster but uses more battery", OnDeviceSpeech.DEFAULT_THREADS,
        1, OnDeviceSpeech.MAX_THREADS, 1, "")
    val OD_MAX_SENTENCE = IntPref("od_max_sentence", "Longest sentence",
        "Packs that write a sentence at a time cut it here if you don't pause", OnDeviceSpeech.DEFAULT_MAX_SENTENCE_S,
        5, 30, 1, " s")
    val OD_FIRST = BoolPref("od_first", "Use first",
        "On: used even when Deepgram or Sarvam AI could dictate. Off: used when they can't, or there's no internet", false)
    val OD_BOOST = IntPref("od_boost", "Quiet speech boost",
        "Raises quiet speech up to this many times before it's turned into text, so you needn't speak up. 1× is off",
        OnDeviceSpeech.DEFAULT_BOOST, 1, 20, 1, "×")
    val OD_BEAM = IntPref("od_beam", "English search width",
        "How many guesses the English pack weighs at once. 1 is quickest", OnDeviceSpeech.DEFAULT_BEAM, 1, 8, 1, "")
    val OD_RECHECK = BoolPref("od_recheck", "Recheck each sentence",
        "With Sharper English downloaded: when you pause, the sentence is written again more accurately", true)
    val OD_RECHECK_PAUSE_MS = IntPref("od_recheck_pause_ms", "Recheck pause",
        "With Sharper English: how long you can stop before the sentence is written again. Longer keeps slow sentences whole",
        OnDeviceSpeech.DEFAULT_RECHECK_PAUSE_MS, 500, 5000, 100, " ms")
    val OD_PUNCT = BoolPref("od_punct", "English punctuation",
        "With the English punctuation pack: adds full stops, commas and capitals", true)
    val OD_RESIDENCY = ActionPref("od_residency", "Where your voice goes", "")

    // Speech slot 4: empty for now.
    val SLOT4 = ActionPref("slot4", "Speech slot 4", "")

    // Notes
    val NOTE_TEXT_SIZE = IntPref("note_text_size", "Text size", "Size of the text in your notes", 16, 12, 28, 1, " sp")
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

    // Translation (on this phone: Opus-MT when its models are downloaded, else Google ML Kit; Malayalam is Opus-MT only;
    // tap a note's text to translate it). Languages store codes, show names.
    val TR_SOURCE = ChoicePref("tr_source", "I mostly write in", "", TranslateConfig.DEFAULT_SOURCE,
        TranslateConfig.options(), searchable = true)
    val TR_TARGET = ChoicePref("tr_target", "Translate notes to", "", TranslateConfig.DEFAULT_TARGET,
        TranslateConfig.options(), searchable = true)
    val OPUS_MODELS = ActionPref("opus_models", "Offline translation models", "")
    val OPUS_BEAMS = IntPref("opus_beams", "Opus-MT search width",
        "How many candidate translations Opus-MT weighs per sentence. 4 (what the models were tuned for) is the most " +
            "accurate; 1 is quickest but can repeat itself or cut a sentence short", OpusMt.DEFAULT_BEAMS, 1, OpusMt.MAX_BEAMS, 1, "")
    // Off by default (Anoop is mostly on mobile data, Oct 2026). Only stored once the switch is touched, so installs that
    // never changed it follow this default; someone who turned it on keeps it on.
    val TR_WIFI_ONLY = BoolPref("tr_wifi_only", "Download on Wi-Fi only",
        "Off: models download on mobile data too. Opus-MT models (about 110 MB per language pair), ML Kit packs " +
            "(~30 MB per language) and offline speech packs (8-662 MB each) are fetched once. Your notes never leave the phone", false)
    val RERUN_SETUP = ActionPref("rerun_setup", "Run first-time setup again", "Languages and region")
    val WELCOME_MS = IntPref("welcome_ms", "Welcome screen time",
        "How long the first-run welcome stays before moving on (a tap skips it)", 2500, 500, 10000, 250, " ms")

    // Look & layout
    val THEME = ChoicePref("theme", "Theme", "The sun/moon button at the top flips light and dark", ThemeMode.SYSTEM, listOf(
        ThemeMode.SYSTEM to "Follow system", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"))
    val WRITING_MODE = ChoicePref("writing_mode", "Default mode",
        "Short notes or Long sessions. The toggle at the top of the screen also remembers your last choice",
        WritingMode.SHORT, listOf(WritingMode.SHORT to "Short", WritingMode.LONG to "Long"),
        hints = mapOf(
            WritingMode.SHORT to "Quick thoughts. The mic stops when you pause",
            WritingMode.LONG to "Sessions that keep going until you tap stop",
        ))
    val MAX_WIDTH = IntPref("max_width", "Max content width",
        "Keeps lines readable on wide screens such as the unfolded Fold", 720, 400, 1400, 20, " dp")

    /**
     * The main Settings screen, top to bottom. The speech providers (one page each, [providerPages]) are listed right
     * after [DICTATION]; the empty slots 3 and 4 sit behind "Add provider". Everything not on the simple layout Anoop
     * drew (Oct 2026) lives in the sections after Notes.
     */
    const val DICTATION = "Dictation"
    val sections: List<Pair<String, List<Pref>>> = listOf(
        DICTATION to listOf(SHOW_MIC, DG_LANGUAGE, REGION, KEEP_SCREEN_ON, PAUSE_MS),
        "Notes" to listOf(NOTE_TEXT_SIZE, PREVIEW_LINES, HIGHLIGHT_DEFAULT, SEARCH_ARCHIVED, CONFIRM_DELETE, UNDO_SECONDS),
        "Composer" to listOf(AUTO_FOCUS, SHOW_KEYBOARD, APPEND_DICTATION, ENTER_SAVES, AUTO_SAVE,
            AUTO_SAVE_SECONDS, COMPOSER_TEXT_SIZE, COMPOSER_MAX_LINES),
        "Translation" to listOf(TR_SOURCE, TR_TARGET, OPUS_MODELS, OPUS_BEAMS, TR_WIFI_ONLY),
        "Look & layout" to listOf(THEME, WRITING_MODE, MAX_WIDTH),
        "Advanced" to listOf(WELCOME_MS, RERUN_SETUP),
    )

    /** One speech provider's own page: its rows, and the "where your voice goes" line under them. */
    class ProviderPage(val id: String, val provider: SpeechProvider, val slot: Int, val tagline: String,
                       val prefs: List<Pref>, val residency: ActionPref)
    val providerPages: List<ProviderPage> = listOf(
        ProviderPage("deepgram", SpeechProvider.DEEPGRAM, 1, "", listOf(SLOT1_ON, STT_API_KEY, DG_MODEL, DG_SMART_FORMAT, DG_REGION), RESIDENCY),
        ProviderPage("sarvam", SpeechProvider.SARVAM, 2, "Indian languages",
            listOf(SLOT2_ON, SLOT2_KEY, SARVAM_MODEL, SARVAM_MODE, SARVAM_CODEMIX), SARVAM_RESIDENCY),
        ProviderPage("ondevice", SpeechProvider.ON_DEVICE, 3, "No internet needed",
            listOf(SLOT3_ON, SPEECH_PACKS, OD_FIRST, OFFLINE_SWITCH, OD_RECHECK, OD_RECHECK_PAUSE_MS, OD_PUNCT, OD_BOOST, OD_BEAM, OD_THREADS, OD_MAX_SENTENCE),
            OD_RESIDENCY),
    )
    /** Behind "Add provider" on the main screen. */
    val emptySlots: List<ActionPref> = listOf(SLOT4)
}

fun Context.settings(): SharedPreferences = getSharedPreferences("settings", Context.MODE_PRIVATE).also { migrateSettings(it) }
fun Context.uiState(): SharedPreferences = getSharedPreferences("state", Context.MODE_PRIVATE)

private const val KEY_SETTINGS_VERSION = "settings_version"
private const val SETTINGS_VERSION = 5 // 3 = v0.1.5: generic live-dictation settings removed; 4 = language picker;
                                        // 5 = translation is ML Kit only (the second translator's leftovers removed)

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
    if (state.getInt(KEY_SETTINGS_VERSION, 1) < 5) cleanUpOldTranslator(p, edit)
    edit.apply()
    state.edit().putInt(KEY_SETTINGS_VERSION, SETTINGS_VERSION).apply()
}

/**
 * Commit 9d3e96b (never released) had a second, downloadable translator; it was removed. If that build ever ran,
 * drop its leftovers: the translator choice (ML Kit is the only one), its settings and download state, any model file
 * in app storage, its notification channel, and languages only it offered (back to the defaults). Notes are untouched.
 * The key names below are that build's, kept only so they can be deleted.
 */
private fun Context.cleanUpOldTranslator(p: SharedPreferences, edit: SharedPreferences.Editor) {
    edit.remove("tr_backend").remove("hymt_threads")
    for (key in listOf(Prefs.TR_SOURCE.key, Prefs.TR_TARGET.key)) {
        val code = runCatching { p.getString(key, null) }.getOrNull() ?: continue
        if (TranslateConfig.lang(code) == null) edit.remove(key)
    }
    uiState().edit().remove("hymt_download_wanted").remove("hymt_download_error").remove("asked_notifications").apply()
    runCatching { java.io.File(filesDir, "models").deleteRecursively() }
    runCatching { getSystemService(android.app.NotificationManager::class.java).deleteNotificationChannel("model_download") }
}

// ---- region and cloud-dictation consent (see Residency) ----
private const val KEY_REGION = "region"
private const val KEY_DG_CONSENT = "dg_consent" // the Deepgram region the user said yes to sending audio to ("" = none)

fun Context.hasUserRegion(): Boolean = uiState().contains(KEY_REGION)
fun Context.userRegion(): String = Residency.region(uiState().getString(KEY_REGION, null) ?: Residency.DEFAULT).code

/** Saves the user's region and points live dictation at the matching (or nearest) Deepgram endpoint. */
fun Context.setUserRegion(code: String) {
    uiState().edit().putString(KEY_REGION, code).apply()
    settings().edit().putString(Prefs.DG_REGION.key, Residency.endpointFor(code)).apply()
}

fun Context.dgConsent(): String = uiState().getString(KEY_DG_CONSENT, "") ?: ""
fun Context.setDgConsent(dg: String) = uiState().edit().putString(KEY_DG_CONSENT, dg).apply()

/** Deepgram (slot 1) may stream: audio stays in the user's region, or they said yes to where it goes. */
fun Context.liveDictationAllowed(): Boolean = Residency.allowed(userRegion(), settings()[Prefs.DG_REGION], dgConsent())

private const val KEY_SARVAM_CONSENT = "sarvam_consent" // the user said yes to sending audio to Sarvam AI in India
fun Context.sarvamConsent(): Boolean = uiState().getBoolean(KEY_SARVAM_CONSENT, false)
fun Context.setSarvamConsent(yes: Boolean) = uiState().edit().putBoolean(KEY_SARVAM_CONSENT, yes).apply()
/** Sarvam AI (slot 2) may stream: the user is in India, or said yes. */
fun Context.sarvamAllowed(): Boolean = Residency.sarvamAllowed(userRegion(), sarvamConsent())

/**
 * The 4 speech slots as they stand now (SpeechSlots picks which to use). "On this phone" needs no key and no consent
 * (audio stays on the phone); it is off while its crash guard holds it back (OnDeviceSpeech.guard).
 */
fun Context.speechSlots(): List<SpeechSlots.Slot> {
    val p = settings()
    return SpeechSlots.PROVIDERS.mapIndexed { i, provider ->
        val n = i + 1
        when (provider) {
            SpeechProvider.DEEPGRAM -> SpeechSlots.Slot(n, provider, p[Prefs.SLOT1_ON], SecretStore.has(this, Prefs.STT_API_KEY.key), liveDictationAllowed())
            SpeechProvider.SARVAM -> SpeechSlots.Slot(n, provider, p[Prefs.SLOT2_ON], SecretStore.has(this, Prefs.SLOT2_KEY.key), sarvamAllowed())
            SpeechProvider.ON_DEVICE -> SpeechSlots.Slot(n, provider, p[Prefs.SLOT3_ON] && OnDeviceSpeech.guard(this).blocked() == null,
                true, true, SpeechPacks.installed(this))
            SpeechProvider.NONE -> SpeechSlots.Slot(n, provider, false, false, false)
        }
    }
}

/**
 * The mic button shows when some slot could stream: Deepgram is allowed (as before, even before a key is added, so the
 * mic points people to Settings), or Sarvam AI has a key, is on and is allowed, or On this phone has a pack for the
 * dictation language.
 */
fun Context.micAvailable(): Boolean = (settings()[Prefs.SLOT1_ON] && liveDictationAllowed()) ||
    speechSlots().any { it.provider == SpeechProvider.SARVAM && it.enabled && it.hasKey && it.residencyOk } ||
    speechSlots().any { it.provider == SpeechProvider.ON_DEVICE && it.enabled && SpeechSlots.ready(it, settings()[Prefs.DG_LANGUAGE]) }

/** The phone has a working internet connection right now (Android has checked it reaches the internet). */
fun Context.isOnline(): Boolean = runCatching {
    val cm = getSystemService(android.net.ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}.getOrDefault(true) // can't tell: try the online providers as before

operator fun SharedPreferences.get(p: BoolPref): Boolean = getBoolean(p.key, p.def)
operator fun SharedPreferences.get(p: IntPref): Int = getInt(p.key, p.def).coerceIn(p.min, p.max)
operator fun SharedPreferences.get(p: TextPref): String = getString(p.key, null) ?: p.def
operator fun SharedPreferences.get(p: ChoicePref): String =
    getString(p.key, null)?.takeIf { v -> p.options.any { it.first == v } } ?: p.def
