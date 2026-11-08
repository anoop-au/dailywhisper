package com.anoop.dailywhisper

import android.content.Context
import android.content.SharedPreferences

/** Every tunable value: a key, a default, a range, and the words shown on the Settings screen. */
sealed class Pref(val key: String, val title: String, val summary: String)

class BoolPref(key: String, title: String, summary: String, val def: Boolean,
               /** When set and the switch was never touched, the default comes from the phone (e.g. a pack is installed). */
               val defWhen: ((Context) -> Boolean)? = null) : Pref(key, title, summary)

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

    // Live dictation (the composer mic): "bring your own key" speech providers (SpeechRegistry), tried in the user's order,
    // with On this phone as the fallback. Only the useful knobs are settings (Anoop's call); protocol details are fixed in
    // each provider (SpeechProviders.kt, LiveDictationConfig, SarvamConfig).
    val SHOW_MIC = BoolPref("show_mic", "Mic button",
        "A small mic in the composer that streams your voice to your speech provider and types the words live", true)
    // "I dictate in". Stored under the old Deepgram key so earlier choices carry over; the app picks the provider.
    val DG_LANGUAGE = ChoicePref("dg_language", "I dictate in", "",
        LiveDictationConfig.DEFAULT_LANGUAGE, SpeechSlots.LANGUAGES, searchable = true, hints = SpeechSlots.LANGUAGE_HINTS)
    // On the Sarvam AI page; only used when dictating in "Auto (Indian languages)" (others use Sarvam AI's Output setting).
    val SARVAM_CODEMIX = BoolPref(SarvamProvider.KEY_CODEMIX, "Keep English words in English",
        "Code-mixed: for Malayalam and English in one sentence, when you dictate in Auto (Indian languages). Off by default", false)
    val PAUSE_MS = IntPref("pause_ms", "Sentence pause",
        "How long you can stop to think before the sentence is closed with . or ?", SpeechSlots.DEFAULT_PAUSE_MS,
        SpeechSlots.MIN_PAUSE_MS, SpeechSlots.MAX_PAUSE_MS, 100, " ms")
    val KEEP_SCREEN_ON = BoolPref("keep_screen_on", "Keep screen on",
        "The screen won't dim or lock while the mic is live", true)
    // Region and consent are facts, not tunables: kept in "state" so Reset to defaults leaves them alone.
    val REGION = ActionPref("region", "Region", "")
    /** Opens Settings → Providers: keys, order and details of every provider. */
    val PROVIDERS = ActionPref("providers", "Providers", "Keys, order and details")
    /** On the Providers page: which online speech provider is tried first. Saved as ids under its key ("stt_order"). */
    val STT_ORDER = ActionPref("stt_order", "Change the order", "")

    // Deepgram.
    val USE_DEEPGRAM = BoolPref(DeepgramProvider.useKey, "Use Deepgram", "", true)
    val STT_API_KEY = SecretPref(SecretStore.STT_API_KEY, "API key",
        "Encrypted on this phone with Android Keystore. Reset to defaults keeps it; use Clear to remove it")
    val DG_MODEL = TextPref(DeepgramProvider.KEY_MODEL, "Model",
        "nova-3, nova-2… always uses the newest version of the model", LiveDictationConfig.DEFAULT_MODEL, "nova-3")
    val DG_SMART_FORMAT = BoolPref(DeepgramProvider.KEY_SMART_FORMAT, "Smart formatting",
        "Punctuation and numbers", LiveDictationConfig.DEFAULT_SMART_FORMAT)
    val RESIDENCY = ActionPref("residency", "Where your voice goes", "")
    val DG_REGION = ChoicePref(DeepgramProvider.KEY_REGION, "Processing region", "Where Deepgram processes your audio. Set from your region",
        LiveDictationConfig.DEFAULT_REGION, LiveDictationConfig.REGIONS.map { it.first to it.second })

    // Sarvam AI (India): Malayalam and the other Indian languages.
    val USE_SARVAM = BoolPref(SarvamProvider.useKey, "Use Sarvam AI", "", true)
    val SARVAM_KEY = SecretPref(SecretStore.SLOT2_API_KEY, "API key",
        "Encrypted on this phone with Android Keystore. Reset to defaults keeps it; use Clear to remove it")
    val SARVAM_MODEL = ChoicePref(SarvamProvider.KEY_MODEL, "Model", "", SarvamConfig.DEFAULT_MODEL, SarvamConfig.MODELS)
    val SARVAM_MODE = ChoicePref(SarvamProvider.KEY_MODE, "Output", "How the words are written", SarvamConfig.DEFAULT_MODE, SarvamConfig.MODES)
    val SARVAM_RESIDENCY = ActionPref("sarvam_residency", "Where your voice goes", "")

    // AssemblyAI (Universal Streaming), bring your own key. US or EU processing.
    val USE_ASSEMBLYAI = BoolPref(AssemblyAiProvider.useKey, "Use AssemblyAI", "", true)
    val AAI_KEY = SecretPref(AssemblyAiProvider.keyName, "API key",
        "Encrypted on this phone with Android Keystore. Reset to defaults keeps it; use Clear to remove it")
    val AAI_MODEL = ChoicePref(AssemblyAiProvider.KEY_MODEL, "Model", "", AssemblyAiProvider.DEFAULT_MODEL,
        AssemblyAiProvider.MODELS.map { it.id to it.name })
    val AAI_REGION = ChoicePref(AssemblyAiProvider.KEY_REGION, "Processing region", "Where AssemblyAI processes your audio",
        AssemblyAiProvider.DEFAULT_REGION, AssemblyAiProvider.REGIONS)
    val AAI_RESIDENCY = ActionPref("aai_residency", "Where your voice goes", "")

    // On this phone (Sherpa-ONNX). No key; audio never leaves the phone. Needs a speech pack. Only ever the fallback.
    val USE_ONDEVICE = BoolPref(OnDeviceProvider.useKey, "Use On this phone",
        "Used when there's no internet, or when no online provider can do your language", true)
    val SPEECH_PACKS = ActionPref("speech_packs", "Speech packs", "")
    val OFFLINE_SWITCH = BoolPref("offline_switch", "Switch when the internet drops",
        "If the connection goes while you dictate, carry on with On this phone", true)
    val OD_THREADS = IntPref("od_threads", "Processor cores",
        "How many cores On this phone uses. More is faster but uses more battery", OnDeviceSpeech.DEFAULT_THREADS,
        1, OnDeviceSpeech.MAX_THREADS, 1, "")
    val OD_MAX_SENTENCE = IntPref("od_max_sentence", "Longest sentence",
        "Packs that write a sentence at a time cut it here if you don't pause", OnDeviceSpeech.DEFAULT_MAX_SENTENCE_S,
        5, 30, 1, " s")
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
    val LONG_AUTOSAVE_SECONDS = IntPref("long_autosave_seconds", "Long session autosave",
        "How often a Long session saves while you talk, so nothing is lost if the app closes", 4, 2, 30, 1, " s")

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
    // Live translation while dictating (English, Malayalam, Chinese; on the phone). On by default when the chosen
    // languages can be translated here (Chinese: always; Malayalam: once its offline model is on the phone). Only
    // stored once the switch is touched.
    val LIVE_TR = BoolPref("live_tr", "Translate while dictating",
        "Shows the translation under what you say (side by side on a wide screen). English, Chinese and Malayalam; works without internet once the language is on the phone",
        false, defWhen = { c -> LiveTranslation.usable(c) })
    val LIVE_TR_TARGET = ChoicePref("live_tr_target", "Translate dictation into", "", LiveTranslation.NOTES, listOf(
        LiveTranslation.NOTES to "Same as notes", LiveTranslation.ZH to "Chinese", LiveTranslation.AUTO to "The other one",
        LiveTranslation.EN to "English", LiveTranslation.ML to "Malayalam"),
        hints = mapOf(
            LiveTranslation.NOTES to "The \"Translate notes to\" language. Speaking it translates back to \"I mostly write in\"",
            LiveTranslation.ZH to "English becomes Chinese",
            LiveTranslation.AUTO to "English becomes Malayalam, Malayalam and Chinese become English"))
    val LIVE_TR_PARTIALS = BoolPref("live_tr_partials", "Translate words before the sentence ends",
        "Off: each sentence is translated once you pause (steadier). On: a rough translation follows as you speak", false)
    val LIVE_TR_PARTIAL_MS = IntPref("live_tr_partial_ms", "Wait before translating unfinished words",
        "With the switch above on: how long the words must stay the same before they're translated", 800, 300, 3000, 100, " ms")
    val LIVE_TR_SIDE_DP = IntPref("live_tr_side_dp", "Side by side from",
        "Screens at least this wide (the unfolded Fold) show the translation beside the words instead of under them",
        600, 400, 1400, 20, " dp")
    val LIVE_TR_BEAMS = IntPref("live_tr_beams", "Live translation search width",
        "Like Opus-MT search width, for live translation. Lower is quicker", 2, 1, OpusMt.MAX_BEAMS, 1, "")
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
     * The main Settings screen, top to bottom. The top card asks the two things that matter ("I dictate in", and live
     * translation); the app works out the providers. Keys, order and provider details sit behind [PROVIDERS]
     * (Settings → Providers, one page per provider). Everything else lives in the sections after it.
     */
    const val DICTATION = "Dictation"
    val sections: List<Pair<String, List<Pref>>> = listOf(
        DICTATION to listOf(DG_LANGUAGE, PROVIDERS),
        "Mic" to listOf(SHOW_MIC, REGION, KEEP_SCREEN_ON, PAUSE_MS),
        "Notes" to listOf(NOTE_TEXT_SIZE, PREVIEW_LINES, HIGHLIGHT_DEFAULT, SEARCH_ARCHIVED, LONG_AUTOSAVE_SECONDS, CONFIRM_DELETE, UNDO_SECONDS),
        "Composer" to listOf(AUTO_FOCUS, SHOW_KEYBOARD, APPEND_DICTATION, ENTER_SAVES, AUTO_SAVE,
            AUTO_SAVE_SECONDS, COMPOSER_TEXT_SIZE, COMPOSER_MAX_LINES),
        "Translation" to listOf(TR_SOURCE, TR_TARGET, OPUS_MODELS, OPUS_BEAMS, TR_WIFI_ONLY,
            LIVE_TR, LIVE_TR_TARGET, LIVE_TR_PARTIALS, LIVE_TR_PARTIAL_MS, LIVE_TR_SIDE_DP, LIVE_TR_BEAMS),
        "Look & layout" to listOf(THEME, WRITING_MODE, MAX_WIDTH),
        "Advanced" to listOf(WELCOME_MS, RERUN_SETUP),
    )

    /**
     * One provider's own page: its rows, and the "where your voice goes" line under them. [provider]: the speech
     * provider it sets up (null for a translation provider).
     */
    class ProviderPage(val id: String, val title: String, val provider: SpeechProvider?, val tagline: String,
                       val prefs: List<Pref>, val residency: ActionPref?)

    private val speechPages: Map<SpeechProvider, ProviderPage> by lazy { mapOf(
        DeepgramProvider to ProviderPage("deepgram", DeepgramProvider.label, DeepgramProvider, "",
            listOf(USE_DEEPGRAM, STT_API_KEY, DG_MODEL, DG_SMART_FORMAT, DG_REGION), RESIDENCY),
        SarvamProvider to ProviderPage("sarvam", SarvamProvider.label, SarvamProvider, SarvamProvider.tagline,
            listOf(USE_SARVAM, SARVAM_KEY, SARVAM_MODEL, SARVAM_MODE, SARVAM_CODEMIX), SARVAM_RESIDENCY),
        AssemblyAiProvider to ProviderPage("assemblyai", AssemblyAiProvider.label, AssemblyAiProvider, AssemblyAiProvider.tagline,
            listOf(USE_ASSEMBLYAI, AAI_KEY, AAI_MODEL, AAI_REGION), AAI_RESIDENCY),
        OnDeviceProvider to ProviderPage("ondevice", OnDeviceProvider.label, OnDeviceProvider, OnDeviceProvider.tagline,
            listOf(USE_ONDEVICE, SPEECH_PACKS, OFFLINE_SWITCH, OD_RECHECK, OD_RECHECK_PAUSE_MS, OD_PUNCT, OD_BOOST, OD_BEAM, OD_THREADS, OD_MAX_SENTENCE),
            OD_RESIDENCY),
    ) }

    /**
     * A page for a speech provider that has no hand-made one yet: its "Use" switch, its key and where the audio goes.
     * So a new provider needs only its class and its line in SpeechRegistry to show up here.
     */
    private fun genericPage(p: SpeechProvider) = ProviderPage(p.id, p.label, p, p.tagline,
        listOfNotNull(BoolPref(p.useKey, "Use ${p.label}", "", true),
            p.keyName?.let { SecretPref(it, "API key", "Encrypted on this phone with Android Keystore. Reset to defaults keeps it; use Clear to remove it") }),
        if (p.online) ActionPref("residency_${p.id}", "Where your voice goes", "") else null)

    /** Every provider page (Settings → Providers), speech ones in registry order. */
    val providerPages: List<ProviderPage> by lazy { SpeechRegistry.ALL.map { speechPages[it] ?: genericPage(it) } }

    fun pageFor(p: SpeechProvider): ProviderPage = providerPages.first { it.provider == p }

    /** Rows on the Providers page itself (besides the provider list). */
    val providersPage: List<Pref> = listOf(STT_ORDER)
}

fun Context.settings(): SharedPreferences = getSharedPreferences("settings", Context.MODE_PRIVATE).also { migrateSettings(it) }
fun Context.uiState(): SharedPreferences = getSharedPreferences("state", Context.MODE_PRIVATE)

private const val KEY_SETTINGS_VERSION = "settings_version"
private const val SETTINGS_VERSION = 6 // 3 = v0.1.5: generic live-dictation settings removed; 4 = language picker;
                                        // 5 = translation is ML Kit only (the second translator's leftovers removed);
                                        // 6 = provider layer: slot switches become per-provider switches, "Use first" gone

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
        // Against the whole list (Sarvam AI's Malayalam, Auto…), not just Deepgram's, so a version bump never resets them.
        val code = SpeechSlots.LANGUAGES.firstOrNull { it.first.equals(typed.trim(), ignoreCase = true) }?.first
        if (code != null) edit.putString(Prefs.DG_LANGUAGE.key, code) else edit.remove(Prefs.DG_LANGUAGE.key)
    }
    if (state.getInt(KEY_SETTINGS_VERSION, 1) < 5) cleanUpOldTranslator(p, edit)
    if (state.getInt(KEY_SETTINGS_VERSION, 1) < 6) SettingsMigration.toProviders(p.all).apply(edit)
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

// Consent for any other online speech provider: the place the user said yes to sending audio to ("" = none).
private fun consentKey(p: SpeechProvider) = "consent_${p.id}"
fun Context.providerConsent(p: SpeechProvider): String = uiState().getString(consentKey(p), "") ?: ""
fun Context.setProviderConsent(p: SpeechProvider, place: String) = uiState().edit().putString(consentKey(p), place).apply()

/** [p] may receive audio: it stays in the user's region, or they said yes to where it goes. On this phone: always. */
fun Context.providerAllowed(p: SpeechProvider, o: SttOptions = SttOptions(settings().all)): Boolean = when {
    !p.online -> true
    p == SpeechProvider.DEEPGRAM -> liveDictationAllowed()
    p == SpeechProvider.SARVAM -> sarvamAllowed()
    else -> p.inRegion(userRegion(), o) || providerConsent(p) == p.place(o)
}

/** The online speech providers, in the order the mic tries them (Settings → Providers → Change the order). */
fun Context.speechOrder(): List<SpeechProvider> = SpeechRegistry.ordered(settings().getString(Prefs.STT_ORDER.key, null))

/**
 * Every speech provider as it stands now, numbered in the order the mic tries them (SpeechSlots picks which to use).
 * "On this phone" needs no key and no consent (audio stays on the phone); it is off while its crash guard holds it back
 * (OnDeviceSpeech.guard).
 */
fun Context.speechSlots(): List<SpeechSlots.Slot> {
    val p = settings()
    val o = SttOptions(p.all)
    return speechOrder().mapIndexed { i, provider ->
        val on = p.getBoolean(provider.useKey, true)
        if (provider.online) SpeechSlots.Slot(i + 1, provider, on, provider.keyName?.let { SecretStore.has(this, it) } ?: true,
            providerAllowed(provider, o))
        else SpeechSlots.Slot(i + 1, provider, on && OnDeviceSpeech.guard(this).blocked() == null, true, true, SpeechPacks.installed(this))
    }
}

/**
 * The mic button shows when some provider could stream: an online one that is on and allowed (even before its key is
 * added, so the mic points people to Settings), or On this phone with a pack for the dictation language.
 */
fun Context.micAvailable(): Boolean {
    val language = settings()[Prefs.DG_LANGUAGE]
    return speechSlots().any { s ->
        if (s.provider.online) s.enabled && s.residencyOk && (s.hasKey || s.provider == SpeechProvider.DEEPGRAM)
        else s.enabled && SpeechSlots.ready(s, language)
    }
}

/** The phone has a working internet connection right now (Android has checked it reaches the internet). */
fun Context.isOnline(): Boolean = runCatching {
    val cm = getSystemService(android.net.ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}.getOrDefault(true) // can't tell: try the online providers as before

/** Writes a [SettingsMigration.Change] into [e]. */
fun SettingsMigration.Change.apply(e: SharedPreferences.Editor) {
    remove.forEach { e.remove(it) }
    for ((k, v) in put) when (v) {
        is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v); is String -> e.putString(k, v)
    }
}

operator fun SharedPreferences.get(p: BoolPref): Boolean = getBoolean(p.key, p.def)

/** A switch's value, using its phone-dependent default ([BoolPref.defWhen]) until it is touched. */
fun Context.boolPref(p: BoolPref): Boolean {
    val s = settings()
    val dyn = p.defWhen
    return if (dyn != null && !s.contains(p.key)) runCatching { dyn(this) }.getOrDefault(p.def) else s[p]
}
operator fun SharedPreferences.get(p: IntPref): Int = getInt(p.key, p.def).coerceIn(p.min, p.max)
operator fun SharedPreferences.get(p: TextPref): String = getString(p.key, null) ?: p.def
operator fun SharedPreferences.get(p: ChoicePref): String =
    getString(p.key, null)?.takeIf { v -> p.options.any { it.first == v } } ?: p.def
