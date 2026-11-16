package com.anoop.dailywhisper

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/** The simpler Settings layout (Anoop's drawing, Oct 2026): main screen, provider pages, and nothing left out. */
@RunWith(RobolectricTestRunner::class)
class SettingsLayoutTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clean() {
        for (f in listOf("settings", "state", "secrets")) ctx.getSharedPreferences(f, 0).edit().clear().commit()
        ctx.uiState().edit().putBoolean(SetupActivity.KEY_DONE, true).commit()
        ctx.setUserRegion("au")
    }

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }
    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private fun main() = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
    private fun page(id: String) = Robolectric.buildActivity(SettingsActivity::class.java,
        Intent(ctx, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, id)).setup().get()

    /** Every setting declared in [Prefs] (read by reflection, so a new one can't be forgotten) is on some screen, once. */
    @Test fun everySettingIsReachableOnce() {
        val declared = Prefs::class.java.declaredFields.filter { Pref::class.java.isAssignableFrom(it.type) }
            .map { it.isAccessible = true; it.get(Prefs) as Pref }
        val shown = Prefs.sections.flatMap { it.second } + Prefs.providerPages.flatMap { it.prefs + listOfNotNull(it.residency) } +
            Prefs.providersPage + Prefs.translationRows + Prefs.hidden
        assertEquals("shown once each", shown.size, shown.toSet().size)
        assertEquals("missing: " + (declared - shown.toSet()).map { it.key }, declared.toSet(), shown.toSet())
        assertTrue(declared.size >= 40)
    }

    /** Only what's on screen (Advanced folded away hides its rows). */
    private fun shown(v: View): List<String> = when {
        v.visibility != View.VISIBLE -> emptyList()
        v is TextView -> listOf(v.text.toString())
        v is ViewGroup -> (0 until v.childCount).flatMap { shown(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun advanced(openAll: Boolean = false): SettingsActivity {
        if (openAll) Prefs.advanced.forEach { (g, _) -> ctx.uiState().edit().putBoolean(SettingsActivity.KEY_ADVANCED_GROUP + g, true).commit() }
        return page(SettingsActivity.PAGE_ADVANCED)
    }

    /** v0.1.27: Capture, Language & translation, Privacy lead; then Plus, Look, and a row to the Advanced page. */
    @Test fun mainScreenShowsOnlyTheEssentials() {
        val t = shown(main().window.decorView)
        val labels = listOf("Capture", "Language & translation", "Privacy", "Daily Whisper Plus", "Look", "Advanced")
        assertEquals(labels, labels.sortedBy { t.indexOf(it) })
        assertTrue(t.toString(), labels.all { it in t })
        for ((title, value) in listOf("I dictate in" to "English", "Online services" to "Not set up", "Translate into" to "Spanish",
            "Theme" to "Follow system")) {
            assertTrue("$title on $t", title in t)
            assertEquals("$title value", value, t[t.indexOf(title) + 1 + t.drop(t.indexOf(title) + 1).indexOf(value)])
        }
        assertTrue(t.containsAll(listOf("Mic button", "Summary & sentiment", "Translation", "Weekly review online",
            "Test summary & translation", "Weekly review", "Plus preview")))
        assertTrue("Translation: the switch, then the language", t.indexOf("Translate into") > t.indexOf("Translation"))
        assertTrue("the rest is on the Advanced page", listOf("Region", "Sentence pause", "Text size", "Words before a summary",
            "Wait for a summary", "Download on Wi-Fi only", "Run first-time setup again", "Default mode", "Speech order",
            "Translation order", "Tap", "Swipe left", "Reset to defaults").none { it in t })
        assertTrue("no provider names on the main screen", listOf("Deepgram", "Sarvam AI", "AssemblyAI", "OpenRouter").none { it in t })
        assertTrue(listOf("Live translation into", "I mostly write in", "Translate notes to").none { it in t })
    }

    @Test fun advancedRowOpensTheAdvancedPage() {
        val a = main()
        all(a.window.decorView).first { it.hasOnClickListeners() && it is ViewGroup && texts(it).firstOrNull() == "Advanced" }.performClick()
        assertEquals(SettingsActivity.PAGE_ADVANCED, shadowOf(a).nextStartedActivity.getStringExtra(SettingsActivity.EXTRA_PAGE))
    }

    @Test fun advancedPageHasEverythingElseAndRemembers() {
        val a = advanced()
        assertTrue("Advanced" in texts(a.window.decorView))
        // Only the group names (each folded), so it's a short list to scan.
        val groups = Prefs.advanced.map { it.first }
        assertEquals(listOf("Providers & order", "Gestures", "Mic", "Notes", "Composer", "Translation", "Insights", "Weekly review",
            "Look & layout", "Setup"), groups)
        val folded = shown(a.window.decorView)
        assertTrue(folded.toString(), groups.all { it in folded })
        assertTrue("groups start folded", listOf("Region", "Text size", "Speech order").none { it in folded })
        for (g in groups) all(a.window.decorView).first { it.hasOnClickListeners() && it.isShown && it.contentDescription == "$g settings" }.performClick()
        val t = shown(a.window.decorView)
        for ((title, value) in listOf("Speech order" to null, "Translation order" to null, "Give up after" to "10 s",
            "Tap" to "Open", "Long press" to "Delete", "Swipe left" to "Select", "Swipe right" to "Share",
            "Region" to "Australia", "Keep screen on" to null, "Sentence pause" to "1.3 s",
            "Text size" to "16 sp", "Preview length" to "5 lines", "Highlight colour" to null, "Words before a summary" to "50 words",
            "Wait for a summary" to "30 s", "Review day" to "Sunday", "Notes before a review" to "3 notes", "Gentle prompts" to "3",
            "Key themes" to "5", "Unfinished thoughts" to "4")) {
            assertTrue("$title on $t", title in t)
            if (value != null) assertEquals("$title value", value, t[t.indexOf(title) + 1 + t.drop(t.indexOf(title) + 1).indexOf(value)])
        }
        assertTrue("the order is automatic until changed", t.any { it.startsWith("Automatic: ") })
        val tr = listOf("Translate words before the sentence ends", "Wait before translating unfinished words", "Side by side from", "Download on Wi-Fi only")
        assertEquals(tr, tr.sortedBy { t.indexOf(it) })
        assertTrue(t.contains("Run first-time setup again") && t.contains("Reset to defaults"))
        assertFalse("no Short/Long mode setting", t.contains("Default mode"))
        assertFalse("translation engines are on their provider page", t.contains("Opus-MT search width"))
        assertTrue(ctx.uiState().getBoolean(SettingsActivity.KEY_ADVANCED_GROUP + "Mic", false))
        assertTrue("stays open next time", "Region" in shown(advanced().window.decorView))
    }

    @Test fun providersRowOpensTheProvidersPage() {
        val a = main()
        all(a.window.decorView).first { it.hasOnClickListeners() && it is ViewGroup && texts(it).firstOrNull() == "Online services" }.performClick()
        assertEquals(SettingsActivity.PAGE_PROVIDERS, shadowOf(a).nextStartedActivity.getStringExtra(SettingsActivity.EXTRA_PAGE))
    }

    /** A provider page keeps "Use" and the key in view; models and tuning fold under its own Advanced. */
    @Test fun providerPageFoldsModelsUnderAdvanced() {
        val a = page("deepgram")
        val t = shown(a.window.decorView)
        assertTrue(t.toString(), t.containsAll(listOf("Use Deepgram", "API key", "Advanced")))
        assertTrue("model folded: $t", "Model" !in t && "Smart formatting" !in t)
        all(a.window.decorView).first { it.hasOnClickListeners() && it.isShown && it.contentDescription == "Advanced settings" }.performClick()
        assertTrue(shown(a.window.decorView).containsAll(listOf("Model", "nova-3", "Smart formatting")))
        val od = shown(page("ondevice").window.decorView)
        assertTrue("speech packs folded on On this phone: $od", "Speech packs" !in od && "Use On this phone" in od)
        val phone = shown(page(Prefs.PHONE_TRANSLATION).window.decorView)
        assertTrue("no switch or key there: all in view", "Offline translation models" in phone)
    }

    @Test fun providerRowOpensItsPage() {
        val a = page(SettingsActivity.PAGE_PROVIDERS)
        val row = all(a.window.decorView).first { it.hasOnClickListeners() && it is ViewGroup && "Deepgram" in texts(it) && "Sarvam AI" !in texts(it) }
        row.performClick()
        val next = shadowOf(a).nextStartedActivity
        assertEquals("deepgram", next.getStringExtra(SettingsActivity.EXTRA_PAGE))
    }

    @Test fun deepgramPageMatchesTheDrawing() {
        val t = texts(page("deepgram").window.decorView)
        for (s in listOf("Deepgram", "Use Deepgram", "API key", "Not set", "Model", "nova-3", "Smart formatting", "Punctuation and numbers",
            "Processing region", "Audio stays in Australia")) assertTrue("$s on $t", s in t)
        ctx.getSharedPreferences("secrets", 0).edit().putString(SecretStore.STT_API_KEY, "placeholder").commit()
        val k = texts(page("deepgram").window.decorView)
        assertTrue(k.toString(), "Saved on this phone" in k && "•••••" in k && "placeholder" !in k)
    }

    @Test fun sarvamPageHasItsSettingsAndConsentFooter() {
        val a = page("sarvam")
        val t = texts(a.window.decorView)
        for (s in listOf("Sarvam AI", "Use Sarvam AI", "API key", "Model", "Output", "Keep English words in English",
            "Off: needs your OK to send audio to India")) assertTrue("$s on $t", s in t)
        all(a.window.decorView).first { it is TextView && it.text.startsWith("Off: needs your OK") }.performClick()
        ShadowLooper.idleMainLooper()
        val d = ShadowDialog.getLatestDialog() as android.app.AlertDialog
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); ShadowLooper.idleMainLooper()
        assertTrue(ctx.sarvamConsent())
        assertTrue(texts(a.window.decorView).contains("Audio goes to India. You allowed this"))
    }

    @Test fun numberDialogSavesAndDefaultRestores() {
        val a = advanced(openAll = true)
        all(a.window.decorView).first { it.hasOnClickListeners() && it is ViewGroup && texts(it).firstOrNull() == "Sentence pause" }.performClick()
        ShadowLooper.idleMainLooper()
        val d = ShadowDialog.getLatestDialog() as android.app.AlertDialog
        val bar = all(d.window!!.decorView).first { it is android.widget.SeekBar } as android.widget.SeekBar
        bar.progress = bar.progress + 2 // +200 ms
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); ShadowLooper.idleMainLooper()
        assertEquals(1500, ctx.settings()[Prefs.PAUSE_MS])
        assertTrue(texts(a.window.decorView).contains("1.5 s"))
        all(a.window.decorView).first { it.hasOnClickListeners() && it is ViewGroup && texts(it).firstOrNull() == "Sentence pause" }.performClick()
        ShadowLooper.idleMainLooper()
        (ShadowDialog.getLatestDialog() as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick(); ShadowLooper.idleMainLooper()
        assertEquals(SpeechSlots.DEFAULT_PAUSE_MS, ctx.settings()[Prefs.PAUSE_MS])
    }

    @Test fun orderDialogMovesAProviderFirst() {
        val a = advanced(openAll = true)
        all(a.window.decorView).first { it.hasOnClickListeners() && it is ViewGroup && texts(it).firstOrNull() == "Speech order" }.performClick()
        ShadowLooper.idleMainLooper()
        val d = ShadowDialog.getLatestDialog() as android.app.AlertDialog
        assertEquals(3, d.listView.adapter.count)
        shadowOf(d).clickOnItem(2) // "3. AssemblyAI"
        ShadowLooper.idleMainLooper()
        assertEquals(listOf(SpeechProvider.ASSEMBLYAI, SpeechProvider.DEEPGRAM, SpeechProvider.SARVAM, SpeechProvider.ON_DEVICE), ctx.speechOrder())
        val t = texts(page(SettingsActivity.PAGE_PROVIDERS).window.decorView)
        assertTrue(t.toString(), t.indexOf("AssemblyAI") < t.indexOf("Deepgram"))
        assertTrue("no longer automatic", texts(advanced(openAll = true).window.decorView).contains("AssemblyAI, then Deepgram, then Sarvam AI"))
    }

    @Test fun assemblyAiPageAsksBeforeAudioLeavesAustralia() {
        val a = page("assemblyai")
        val t = texts(a.window.decorView)
        for (s in listOf("AssemblyAI", "Use AssemblyAI", "API key", "Not set", "Model", "Universal-3.6 Pro (default, 32 languages)",
            "Processing region", "US", "Off: needs your OK to send audio to the US")) assertTrue("$s on $t", s in t)
        all(a.window.decorView).first { it is TextView && it.text.startsWith("Off: needs your OK") }.performClick()
        ShadowLooper.idleMainLooper()
        (ShadowDialog.getLatestDialog() as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(ctx.providerAllowed(SpeechProvider.ASSEMBLYAI))
        assertTrue(texts(a.window.decorView).contains("Audio goes to the US. You allowed this"))
        // Switching to the EU is a different place: asked again.
        ctx.settings().edit().putString(AssemblyAiProvider.KEY_REGION, "eu").commit()
        assertFalse(ctx.providerAllowed(SpeechProvider.ASSEMBLYAI))
    }

    @Test fun providersPageListsTranslationAndOpenRouterAsksFirst() {
        val t = texts(page(SettingsActivity.PAGE_PROVIDERS).window.decorView)
        for (s in listOf("Translation & summaries", "OpenRouter", "Off: add your key", "Translation on this phone",
            "Off: add your key in Speech → AssemblyAI", "Off: add your key in Speech → Sarvam AI")) assertTrue("$s on $t", s in t)
        assertTrue("the order is in Advanced now", listOf("Change the translation order", "Give up after").none { it in t })
        assertTrue(texts(advanced(openAll = true).window.decorView)
            .contains("Automatic: AssemblyAI, then Sarvam AI, then OpenRouter, then this phone"))
        // In order: AssemblyAI, Sarvam AI, OpenRouter, the phone (after the speech list, which has AssemblyAI too).
        val tr = t.drop(t.indexOf("Translation & summaries"))
        assertEquals(listOf(1, 2, 3, 4), listOf("AssemblyAI", "Sarvam AI", "OpenRouter", "Translation on this phone").map { tr.indexOf(it) }
            .let { idx -> idx.map { i -> idx.sorted().indexOf(i) + 1 } })
        val phone = texts(page(Prefs.PHONE_TRANSLATION).window.decorView)
        assertTrue(phone.toString(), phone.containsAll(listOf("Offline translation models", "Opus-MT search width", "Live translation search width")))
        val a = page(OpenRouterTranslation.id)
        val o = texts(a.window.decorView)
        for (s in listOf("Use OpenRouter", "API key", "Model", "google/gemini-2.5-flash-lite",
            "Off: needs your OK to send sentences to OpenRouter")) assertTrue("$s on $o", s in o)
        assertFalse("the old switch is gone", "Use before the phone's translators" in o)
        all(a.window.decorView).first { it is TextView && it.text.startsWith("Off: needs your OK") }.performClick()
        ShadowLooper.idleMainLooper()
        (ShadowDialog.getLatestDialog() as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(ctx.uiState().getBoolean(OpenRouterTranslation.CONSENT, false))
        assertTrue(texts(a.window.decorView).contains("Sentences go to OpenRouter to translate. You allowed this"))
    }

    @Test fun translationOrderDialogAndAssemblyAiReusesSpeechOk() {
        val a = advanced(openAll = true)
        all(a.window.decorView).first { it.hasOnClickListeners() && it is ViewGroup && texts(it).firstOrNull() == "Translation order" }.performClick()
        ShadowLooper.idleMainLooper()
        val d = ShadowDialog.getLatestDialog() as android.app.AlertDialog
        assertEquals("1. AssemblyAI", d.listView.adapter.getItem(0).toString())
        assertEquals(3, d.listView.adapter.count)
        shadowOf(d).clickOnItem(2) // "3. OpenRouter" first
        ShadowLooper.idleMainLooper()
        assertEquals(listOf(OpenRouterTranslation, AssemblyAiTranslation, SarvamTranslation, OpusMtTranslation, MlKitTranslation),
            ctx.translationOrder())
        // AssemblyAI translation: the speech key and the speech OK (Australia → the US needs a yes).
        ctx.getSharedPreferences("secrets", 0).edit().putString(AssemblyAiProvider.keyName, "placeholder").commit()
        val p = page("tr_assemblyai")
        val o = texts(p.window.decorView)
        for (s in listOf("Use AssemblyAI to translate", "Model", "gemini-2.5-flash-lite",
            "Off: needs your OK to send sentences to the US (the same OK as AssemblyAI speech)")) assertTrue("$s on $o", s in o)
        all(p.window.decorView).first { it is TextView && it.text.startsWith("Off: needs your OK") }.performClick()
        ShadowLooper.idleMainLooper()
        val ask = ShadowDialog.getLatestDialog() as android.app.AlertDialog
        assertTrue(ask.findViewById<TextView>(android.R.id.message).text.contains("Live translation also sends the sentences"))
        ask.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(ctx.providerAllowed(SpeechProvider.ASSEMBLYAI))
        assertTrue(AssemblyAiTranslation.usable(ctx, "en", "zh"))
    }
}
