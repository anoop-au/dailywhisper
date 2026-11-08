package com.anoop.dailywhisper

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.math.BigDecimal

/**
 * Settings, laid out like Anoop's drawing (Oct 2026): rounded cards under small grey labels, each row a title with its
 * value and a chevron on the right (or a switch). The main screen has Dictation, the speech providers in the order
 * Settings → Providers ([PAGE_PROVIDERS]: every provider in the order it is tried, each with its own page, this activity
 * with [EXTRA_PAGE]), then the Mic, Notes and everything else (Composer, Translation, Look & layout, Advanced, Reset). Every setting in [Prefs] is on one of these screens, with its default.
 */
class SettingsActivity : ThemedActivity() {

    private val prefs by lazy { settings() }
    /** Rows whose text depends on live state (region, consent, keys, model download); re-run on resume and on progress. */
    private val refreshers = ArrayList<() -> Unit>()
    private val onProgress: () -> Unit = { refresh() }
    /** A speech provider's own page, or null for the main screen. */
    private val page: Prefs.ProviderPage? by lazy { Prefs.providerPages.firstOrNull { it.id == intent.getStringExtra(EXTRA_PAGE) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
    }

    override fun onResume() { super.onResume(); OpusPacks.listen(onProgress); SpeechPacks.listen(onPacks); refresh() }
    override fun onPause() { super.onPause(); OpusPacks.unlisten(onProgress); SpeechPacks.unlisten(onPacks) }
    /** A speech pack finished or failed: the pack page regroups its rows; elsewhere only the texts change. */
    private var packsShown: Set<String> = emptySet()
    private val onPacks: () -> Unit = {
        if (isPacksPage && SpeechPacks.installed(this) != packsShown) build() else refresh()
    }
    private val isPacksPage get() = intent.getStringExtra(EXTRA_PAGE) == PAGE_PACKS
    private val isProvidersPage get() = intent.getStringExtra(EXTRA_PAGE) == PAGE_PROVIDERS

    private fun refresh() = refreshers.forEach { it() }

    private fun build() {
        refreshers.clear()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.bg)) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        header.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setBackgroundResource(R.drawable.bg_icon_btn)
            contentDescription = getString(R.string.back); setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(TextView(this).apply {
            text = page?.title ?: when { isPacksPage -> Prefs.SPEECH_PACKS.title; isProvidersPage -> Prefs.PROVIDERS.title
                else -> getString(R.string.settings) }
            textSize = 24f; typeface = DwFonts.semibold(context)
            setTextColor(getColor(R.color.text)); setPadding(dp(8), 0, 0, 0)
        })
        root.addView(header)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(32)) }
        val pg = page
        when {
            pg != null -> providerPage(col, pg)
            isPacksPage -> packsPage(col)
            isProvidersPage -> providersPage(col)
            else -> mainPage(col)
        }
        root.addView(ScrollView(this).apply { addView(col) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        edgeToEdge(root) { prefs[Prefs.MAX_WIDTH] }
        refresh()
    }

    private fun mainPage(col: LinearLayout) {
        for ((title, items) in Prefs.sections) {
            col.addView(label(title))
            col.addView(card(items.map { row(it) }))
        }
        col.addView(Button(this).apply {
            text = "Reset to defaults"; isAllCaps = false; setTextColor(getColor(R.color.accent))
            setBackgroundResource(R.drawable.bg_icon_btn)
            setOnClickListener {
                val theme = prefs[Prefs.THEME]
                prefs.edit().clear().apply()
                this@SettingsActivity.setUserRegion(this@SettingsActivity.userRegion()) // the processing region follows the user's region again
                OpusPacks.sync(this@SettingsActivity)
                if (prefs[Prefs.THEME] != theme) recreate() else build()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply {
            topMargin = dp(16); gravity = Gravity.CENTER_HORIZONTAL
        })
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        col.addView(TextView(this).apply {
            text = "Daily Whisper $version\nYour notes stay on this phone."
            gravity = Gravity.CENTER; textSize = 13f; setTextColor(getColor(R.color.text2)); setPadding(0, dp(12), 0, 0)
        })
    }

    /**
     * Settings → Providers: the speech providers in the order the mic tries them (each opens its page), how to change
     * the order, and a plain note that On this phone is only the fallback.
     */
    private fun providersPage(col: LinearLayout) {
        col.addView(label("Speech · tried in this order"))
        col.addView(card(speechOrder().map { providerRow(Prefs.pageFor(it)) } + Prefs.providersPage.map { row(it) }))
        col.addView(note("The first one that's on, has its key and knows your language types for you. If it can't connect, " +
            "the next one takes over. ${SpeechProvider.ON_DEVICE.label} is only used without internet, or when none of them " +
            "knows your language."))
        val translation = Prefs.providerPages.filter { it.provider == null }
        if (translation.isNotEmpty()) {
            col.addView(label("Translation"))
            col.addView(card(translation.map { translationRow(it) }))
        }
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(getColor(R.color.text2)); setPadding(dp(14), dp(8), dp(14), dp(4))
    }

    /** A translation provider on the Providers page (filled in by [translationStatus]). Opens its page. */
    private fun translationRow(pg: Prefs.ProviderPage): View {
        val r = Row(pg.title)
        r.value()
        refreshers.add { r.sub(translationStatus(pg)) }
        r.view.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_PAGE, pg.id)) }
        return r.view
    }

    private fun translationStatus(pg: Prefs.ProviderPage): String = pg.tagline

    /** A provider's page: its settings in one card, and where the audio goes underneath (tap to change consent). */
    private fun providerPage(col: LinearLayout, pg: Prefs.ProviderPage) {
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        if (pg.provider == SpeechProvider.ON_DEVICE && OnDeviceSpeech.guard(this).blocked() == OpusGuard.Block.CRASHED) {
            col.addView(TextView(this).apply {
                text = "${SpeechProvider.ON_DEVICE.label} stopped unexpectedly last time, so it's off for now. Tap to try again."
                textSize = 14f; setTextColor(getColor(R.color.accent)); setPadding(dp(14), dp(6), dp(14), dp(10)); background = ripple()
                setOnClickListener { OnDeviceSpeech.guard(this@SettingsActivity).reset(); build() }
            })
        }
        col.addView(card(pg.prefs.map { row(it) }))
        val residency = pg.residency ?: return
        val footer = TextView(this).apply {
            textSize = 14f; setTextColor(getColor(R.color.text2)); gravity = Gravity.CENTER_VERTICAL
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_place, 0, 0, 0); compoundDrawablePadding = dp(6)
            setPadding(dp(14), dp(10), dp(14), dp(10)); background = ripple()
        }
        refreshers.add { footer.text = residencyLine(pg, residency) }
        footer.setOnClickListener { residencyTapped(pg, residency) }
        col.addView(footer)
    }

    // ---- building blocks ----

    private fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 13f
        setTextColor(getColor(R.color.text2)); setPadding(dp(14), dp(18), dp(12), dp(6))
    }

    /** Rows in a rounded card, a hairline between them. */
    private fun card(rows: List<View>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_panel); setPadding(0, dp(2), 0, dp(2))
        clipToOutline = true
        rows.forEachIndexed { i, r ->
            if (i > 0) addView(View(this@SettingsActivity).apply { setBackgroundColor(getColor(R.color.outline)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(16) })
            addView(r)
        }
    }

    private fun ripple() = TypedValue().let { tv ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); getDrawable(tv.resourceId)
    }

    /** One row: [Row.texts] (title + optional [Row.sub]) on the left, [Row.end] for a value/chevron or switch. */
    private inner class Row(title: String) {
        val view = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52); setPadding(dp(16), dp(10), dp(12), dp(10)); background = ripple()
        }
        val texts = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this@SettingsActivity).apply { text = title; textSize = 16f; setTextColor(getColor(R.color.text)) }
        val sub = TextView(this@SettingsActivity).apply {
            textSize = 13f; setTextColor(getColor(R.color.text2)); setPadding(0, dp(2), 0, 0); visibility = View.GONE
            maxLines = 6; ellipsize = TextUtils.TruncateAt.END
        }
        val end = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        init {
            texts.addView(this.title); texts.addView(sub)
            view.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            view.addView(end)
        }
        fun sub(text: String?) { sub.text = text.orEmpty(); sub.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE }
        /** Right-aligned value (may be empty) followed by a chevron. */
        fun value(): TextView {
            val v = TextView(this@SettingsActivity).apply {
                textSize = 15f; setTextColor(getColor(R.color.text2)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(12), 0, dp(2), 0); maxWidth = dp(180); compoundDrawablePadding = dp(6)
            }
            end.addView(v)
            end.addView(ImageView(this@SettingsActivity).apply { setImageResource(R.drawable.ic_chevron) })
            return v
        }
    }

    /** Switch rows that show only their title (the drawing's); the rest keep their one-line explanation. */
    private fun bare(p: Pref) = p === Prefs.SHOW_MIC || p === Prefs.KEEP_SCREEN_ON || (page?.prefs?.firstOrNull() === p && p.summary.isEmpty())

    private fun row(p: Pref): View {
        val r = Row(p.title)
        val row = r.view
        when (p) {
            is ActionPref -> actionRow(p, r)
            is BoolPref -> {
                if (!bare(p)) r.sub(p.summary)
                val sw = Switch(this).apply {
                    isChecked = boolPref(p)
                    contentDescription = p.title
                    setOnCheckedChangeListener { _, v ->
                        prefs.edit().putBoolean(p.key, v).apply()
                        if (p === Prefs.TR_WIFI_ONLY) { OpusPacks.sync(this@SettingsActivity); SpeechPacks.sync(this@SettingsActivity); refresh() }
                        if (p === Prefs.USE_ONDEVICE) refresh()
                        if (p === Prefs.LIVE_TR) OpusPacks.sync(this@SettingsActivity) // fetch a Malayalam model it needs
                        val sp = page?.provider
                        if (sp != null && p.key == sp.useKey && v) askConsentIfNeeded(sp)
                    }
                }
                r.end.addView(sw)
                row.setOnClickListener { sw.toggle() }
            }
            is IntPref -> {
                val value = r.value()
                value.text = show(p, prefs[p])
                row.setOnClickListener { intDialog(p) { value.text = show(p, prefs[p]) } }
            }
            is TextPref -> {
                val value = r.value()
                fun show() {
                    val v = prefs[p]
                    value.text = when {
                        v.isNotEmpty() -> v
                        p.def.isEmpty() -> "Not set"
                        else -> p.def
                    }
                }
                show()
                row.setOnClickListener {
                    val input = editor(p.multiline, p.hint).apply { setText(prefs[p]); setSelection(text.length) }
                    AlertDialog.Builder(this).setTitle(p.title).setMessage(p.summary.ifEmpty { null }).setView(wrap(input))
                        .setPositiveButton("Save") { _, _ -> prefs.edit().putString(p.key, input.text.toString().trim()).apply(); show() }
                        .setNeutralButton(if (p.def.isEmpty()) "Clear" else "Default") { _, _ -> prefs.edit().remove(p.key).apply(); show() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            is ChoicePref -> {
                val value = r.value()
                fun show() {
                    val dictation = p === Prefs.DG_LANGUAGE
                    val opts = if (dictation) SpeechSlots.languageChoices(speechSlots()) else p.options
                    val name = (opts.firstOrNull { it.first == prefs[p] } ?: p.options.first { it.first == prefs[p] }).second
                    // Sarvam AI's long labels ("Saaras v3 realtime (default)") keep the part before the brackets here.
                    value.text = if (p === Prefs.SARVAM_MODEL || p === Prefs.SARVAM_MODE) name.substringBefore(" (") else name
                    // e.g. what "Auto (Sarvam AI)" does, under the title; for the dictation language also which engine
                    // dictates it now ("Uses Sarvam AI, then On this phone").
                    r.sub(if (dictation) listOfNotNull(p.hints[prefs[p]],
                        SpeechSlots.engineLine(speechSlots(), prefs[p])).joinToString("\n") else p.hints[prefs[p]])
                    if (p === Prefs.HIGHLIGHT_DEFAULT) {
                        val dot = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL; setColor(Highlight.of(prefs[p])?.swatch ?: getColor(R.color.hint))
                            setSize(dp(10), dp(10))
                        }
                        value.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
                    }
                }
                show()
                fun choose(code: String) {
                    prefs.edit().putString(p.key, code).apply(); show()
                    if (p === Prefs.THEME) recreate()
                    if (p === Prefs.TR_SOURCE || p === Prefs.TR_TARGET || p === Prefs.LIVE_TR_TARGET) { OpusPacks.sync(this); refresh() } // re-derive the models
                    if (p === Prefs.DG_REGION) afterRegionChange()
                    else page?.provider?.let { askConsentIfNeeded(it) } // e.g. AssemblyAI's region: ask if audio now leaves
                }
                if (p === Prefs.DG_REGION) refreshers.add { show() }
                if (p === Prefs.DG_LANGUAGE) refreshers.add { show() } // providers, keys and packs change what it uses
                row.setOnClickListener {
                    if (p.searchable) { searchablePicker(p, ::choose); return@setOnClickListener }
                    if (p === Prefs.HIGHLIGHT_DEFAULT) {
                        pickHighlight(p.title, prefs[p], allowNone = false) { k -> k?.let(::choose) }
                        return@setOnClickListener
                    }
                    val idx = p.options.indexOfFirst { it.first == prefs[p] }
                    AlertDialog.Builder(this).setTitle(p.title).setMessage(null)
                        .setSingleChoiceItems(p.options.map { it.second }.toTypedArray(), idx) { d, which ->
                            d.dismiss(); choose(p.options[which].first)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            is SecretPref -> {
                // The saved key is never shown or pre-filled: the dialog only replaces or clears it.
                val value = r.value()
                fun show() {
                    val saved = SecretStore.has(this, p.key)
                    value.text = if (saved) "•••••" else "Add"
                    r.sub(if (saved) "Saved on this phone" else "Not set")
                }
                show()
                refreshers.add { show() }
                row.setOnClickListener {
                    val input = editor(false, "Paste key").apply {
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                    val name = page?.title?.let { "$it API key" } ?: p.title
                    AlertDialog.Builder(this).setTitle(name).setMessage(p.summary).setView(wrap(input))
                        .setPositiveButton("Save") { _, _ ->
                            val v = input.text.toString().trim()
                            if (v.isNotEmpty()) SecretStore.put(this, p.key, v)
                            input.setText(""); show()
                            if (v.isNotEmpty()) page?.provider?.let { askConsentIfNeeded(it) }
                        }
                        .setNeutralButton("Clear") { _, _ -> SecretStore.put(this, p.key, ""); show() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
        return row
    }

    /** "1300 ms" reads as "1.3 s"; everything else as stored, with its unit. */
    private fun show(p: IntPref, v: Int): String =
        if (p.unit == " ms") BigDecimal(v).movePointLeft(3).stripTrailingZeros().toPlainString() + " s" else "$v${p.unit}"

    /** Number settings: a slider in a dialog, with the default named and one tap back to it. */
    private fun intDialog(p: IntPref, saved: () -> Unit) {
        var v = prefs[p]
        val now = TextView(this).apply { textSize = 20f; setTextColor(getColor(R.color.accent)); text = show(p, v); gravity = Gravity.CENTER }
        val bar = SeekBar(this).apply {
            max = (p.max - p.min) / p.step
            progress = (v - p.min) / p.step
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) { v = p.min + progress * p.step; now.text = show(p, v) }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(now); addView(bar)
            addView(TextView(this@SettingsActivity).apply {
                text = "Default ${show(p, p.def)}"; textSize = 12f; setTextColor(getColor(R.color.hint)); gravity = Gravity.CENTER
            })
        }
        AlertDialog.Builder(this).setTitle(p.title).setMessage(p.summary.ifEmpty { null }).setView(box)
            .setPositiveButton("Save") { _, _ -> prefs.edit().putInt(p.key, v).apply(); saved() }
            .setNeutralButton("Default") { _, _ -> prefs.edit().remove(p.key).apply(); saved() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Providers page: one speech provider, numbered in the order it's tried, with a dot and a one-line status. Opens its page. */
    private fun providerRow(pg: Prefs.ProviderPage): View {
        val sp = pg.provider ?: return translationRow(pg)
        val r = Row(pg.title)
        val number = TextView(this).apply {
            textSize = 13f; gravity = Gravity.CENTER; setTextColor(getColor(R.color.accent))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(R.color.ripple)) }
        }
        r.view.addView(number, 0, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) })
        r.value()
        refreshers.add {
            val slot = speechSlots().first { it.provider == sp }
            number.text = slot.number.toString()
            val (ready, status) = providerStatus(pg, slot)
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(getColor(if (ready) R.color.ok else R.color.hint)); setSize(dp(7), dp(7))
            }
            r.sub(status)
            r.sub.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null); r.sub.compoundDrawablePadding = dp(6)
        }
        r.view.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_PAGE, pg.id)) }
        return r.view
    }

    /** Whether the provider can take the mic now (green dot), and its status line, e.g. "On · nova-3". */
    private fun providerStatus(pg: Prefs.ProviderPage, slot: SpeechSlots.Slot): Pair<Boolean, String> {
        if (!slot.provider.online) {
            val names = SpeechPackCatalog.PACKS.filter { it.id in slot.installed }.map { it.name }
            val state = when {
                !prefs.getBoolean(slot.provider.useKey, true) -> "Off"
                OnDeviceSpeech.guard(this).blocked() != null -> "Stopped unexpectedly"
                names.isEmpty() -> "Needs a speech pack"
                else -> "On"
            }
            return (state == "On") to listOf(state, if (state == "On") names.joinToString(", ") else pg.tagline).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        val what = pg.tagline.ifEmpty { if (slot.provider == SpeechProvider.DEEPGRAM) prefs[Prefs.DG_MODEL].ifEmpty { Prefs.DG_MODEL.def } else "" }
        val state = when {
            !slot.enabled -> "Off"
            !slot.hasKey -> "Needs an API key"
            !slot.residencyOk -> "Needs your OK to send audio"
            else -> "On"
        }
        return (state == "On") to listOf(state, what).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** Where the audio goes, for a provider page's footer. */
    private fun residencyLine(pg: Prefs.ProviderPage, p: ActionPref): String {
        val sp = pg.provider
        return when {
            p === Prefs.OD_RESIDENCY || (sp != null && !sp.online) -> "Audio never leaves this phone"
            p === Prefs.SARVAM_RESIDENCY -> when {
                Residency.sarvamInRegion(userRegion()) -> "Audio stays in India"
                sarvamConsent() -> "Audio goes to India. You allowed this"
                else -> "Off: needs your OK to send audio to India"
            }
            p === Prefs.RESIDENCY -> {
                val dg = prefs[Prefs.DG_REGION]
                when {
                    Residency.inRegion(userRegion(), dg) -> "Audio stays in ${Residency.placeOf(dg)}"
                    liveDictationAllowed() -> "Audio goes to ${Residency.placeOf(dg)}. You allowed this"
                    else -> "Mic off: needs your OK to send audio to ${Residency.placeOf(dg)}"
                }
            }
            sp != null -> {
                val o = SttOptions(prefs.all)
                val place = sp.place(o) ?: return "Audio never leaves this phone"
                when {
                    sp.inRegion(userRegion(), o) -> "Audio stays in $place"
                    providerAllowed(sp, o) -> "Audio goes to $place. You allowed this"
                    else -> "Off: needs your OK to send audio to $place"
                }
            }
            else -> translationResidency(pg)
        }
    }

    private fun translationResidency(pg: Prefs.ProviderPage): String = pg.tagline

    private fun residencyTapped(pg: Prefs.ProviderPage, p: ActionPref) {
        val sp = pg.provider
        if (p === Prefs.OD_RESIDENCY || (sp != null && !sp.online)) {
            AlertDialog.Builder(this).setTitle(p.title).setMessage("${SpeechProvider.ON_DEVICE.label} turns speech into text on " +
                "the phone itself, with the speech pack you downloaded. Your voice isn't sent anywhere, and it works without internet.")
                .setPositiveButton("OK", null).show()
        } else if (p === Prefs.SARVAM_RESIDENCY) {
            if (Residency.sarvamInRegion(userRegion()))
                AlertDialog.Builder(this).setTitle(p.title).setMessage(Residency.sarvamInRegionLine()).setPositiveButton("OK", null).show()
            else askSarvamConsent()
        } else if (p === Prefs.RESIDENCY) {
            val dg = prefs[Prefs.DG_REGION]
            if (Residency.inRegion(userRegion(), dg))
                AlertDialog.Builder(this).setTitle(p.title).setMessage(Residency.inRegionLine(dg)).setPositiveButton("OK", null).show()
            else askConsent()
        } else if (sp != null) {
            val o = SttOptions(prefs.all)
            val place = sp.place(o) ?: return
            if (sp.inRegion(userRegion(), o))
                AlertDialog.Builder(this).setTitle(p.title)
                    .setMessage("${sp.label} processes audio in $place, so your voice stays in your region. Nothing is kept after it's typed.")
                    .setPositiveButton("OK", null).show()
            else askProviderConsent(sp)
        } else translationResidencyTapped(pg, p)
    }

    private fun translationResidencyTapped(pg: Prefs.ProviderPage, p: ActionPref) {
        AlertDialog.Builder(this).setTitle(p.title).setMessage(translationResidency(pg)).setPositiveButton("OK", null).show()
    }

    /** The order dialog: tap a provider to try it first. */
    private fun orderDialog() {
        val online = speechOrder().filter { it.online }
        AlertDialog.Builder(this).setTitle("Tap one to try it first")
            .setItems(online.mapIndexed { i, sp -> "${i + 1}. ${sp.label}" }.toTypedArray()) { _, which ->
                prefs.edit().putString(Prefs.STT_ORDER.key, SpeechRegistry.moveFirst(prefs.getString(Prefs.STT_ORDER.key, null), online[which])).apply()
                build()
            }
            .setNeutralButton("Default") { _, _ -> prefs.edit().remove(Prefs.STT_ORDER.key).apply(); build() }
            .setNegativeButton("Cancel", null).show()
    }

    /** Rows that do something: pick region, manage the Opus-MT models, run setup. */
    private fun actionRow(p: ActionPref, r: Row) {
        val row = r.view
        when (p) {
            Prefs.REGION -> {
                val value = r.value()
                refreshers.add { value.text = Residency.region(userRegion()).name }
                row.setOnClickListener {
                    val regions = Residency.REGIONS
                    AlertDialog.Builder(this).setTitle("Which region are you in?")
                        .setSingleChoiceItems(regions.map { it.name }.toTypedArray(), regions.indexOfFirst { it.code == userRegion() }) { d, which ->
                            d.dismiss(); setUserRegion(regions[which].code); refresh(); afterRegionChange()
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            }
            Prefs.SPEECH_PACKS -> {
                val value = r.value()
                refreshers.add {
                    val n = SpeechPacks.installed(this).count { it != SpeechPackCatalog.PUNCT } // not listed: looked after for you
                    value.text = if (n == 0) "None" else "$n on this phone"
                    r.sub(SpeechPacks.progress.takeIf { it.running && it.total > 0 }?.let { "Downloading… ${it.done * 100 / it.total}%" })
                }
                row.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_PAGE, PAGE_PACKS)) }
            }
            Prefs.PROVIDERS -> {
                val value = r.value()
                r.sub(p.summary)
                refreshers.add {
                    val ready = speechSlots().filter { it.provider.online && it.enabled && it.hasKey && it.residencyOk }
                    value.text = ready.firstOrNull()?.provider?.label ?: "Not set up"
                }
                row.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_PAGE, PAGE_PROVIDERS)) }
            }
            Prefs.STT_ORDER -> {
                r.value()
                refreshers.add { r.sub(speechOrder().filter { it.online }.joinToString(", then ") { it.label }) }
                row.setOnClickListener { orderDialog() }
            }
            Prefs.OPUS_MODELS -> {
                r.value()
                refreshers.add { r.sub(OpusPacks.statusLine(this)) }
                row.setOnClickListener { modelsDialog() }
            }
            else -> {
                r.value()
                r.sub(p.summary)
                row.setOnClickListener {
                    startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_RERUN, true))
                }
            }
        }
    }

    /** After the user's region or the processing region changes: ask if audio would now leave their region. */
    private fun afterRegionChange() {
        if (!liveDictationAllowed()) askConsent { askSarvamConsentIfNeeded() } else { refresh(); askSarvamConsentIfNeeded() }
    }

    /** Sarvam AI only runs in India: when it is set up and on, ask once if the user is elsewhere and hasn't said yes. */
    private fun askSarvamConsentIfNeeded() = askConsentIfNeeded(SpeechProvider.SARVAM)

    /**
     * When [sp] is on and has its key but would send audio outside the user's region without their OK, ask now
     * (Deepgram: its own question; the rest: where the audio would go). Otherwise just refresh.
     */
    private fun askConsentIfNeeded(sp: SpeechProvider) {
        if (!sp.online) { refresh(); return }
        if (sp == SpeechProvider.DEEPGRAM) { if (!liveDictationAllowed()) askConsent() else refresh(); return }
        val usable = prefs.getBoolean(sp.useKey, true) && (sp.keyName?.let { SecretStore.has(this, it) } ?: true)
        if (!usable || providerAllowed(sp)) { refresh(); return }
        if (sp == SpeechProvider.SARVAM) askSarvamConsent() else askProviderConsent(sp)
    }

    /** The yes/no for any other online provider: names where the audio goes. Stored either way. */
    private fun askProviderConsent(sp: SpeechProvider) {
        val place = sp.place(SttOptions(prefs.all)) ?: return
        AlertDialog.Builder(this).setTitle("Send audio to $place?")
            .setMessage("${sp.label} doesn't process audio in ${Residency.region(userRegion()).name}. Using it would send your " +
                "voice to $place to type it, and nothing is kept after. Is that OK?\n\nWithout it, ${sp.label} stays off. Everything else works.")
            .setPositiveButton("Allow") { _, _ -> setProviderConsent(sp, place); refresh() }
            .setNegativeButton("Don't allow") { _, _ -> setProviderConsent(sp, ""); refresh() }
            .setOnCancelListener { refresh() }
            .show()
    }

    private fun askSarvamConsent() {
        AlertDialog.Builder(this).setTitle("Send audio to India?")
            .setMessage(Residency.sarvamConsentLine(userRegion()) + "\n\nWithout it, Sarvam AI stays off. Everything else works.")
            .setPositiveButton("Allow") { _, _ -> setSarvamConsent(true); refresh() }
            .setNegativeButton("Don't allow") { _, _ -> setSarvamConsent(false); refresh() }
            .setOnCancelListener { refresh() }
            .show()
    }

    /** Standard yes/no consent for sending audio outside the user's region. Stored either way. */
    private fun askConsent(then: () -> Unit = {}) {
        val dg = prefs[Prefs.DG_REGION]
        AlertDialog.Builder(this).setTitle("Send audio to ${Residency.placeOf(dg)}?")
            .setMessage(Residency.consentLine(userRegion(), dg) + "\n\nWithout it, the mic stays off. Everything else works.")
            .setPositiveButton("Allow") { _, _ -> setDgConsent(dg); refresh() }
            .setNegativeButton("Don't allow") { _, _ -> setDgConsent(""); refresh() }
            .setOnCancelListener { refresh() }
            .setOnDismissListener { then() }
            .show()
    }

    private fun modelsDialog() {
        val want = OpusPacks.wanted(this)
        val ready = want.isNotEmpty() && want.all { OpusPacks.isReady(this, it) }
        val onDisk = OpusPacks.bytesOnDisk(this)
        val from = if (want.isEmpty()) "" else "\n\nModels: Helsinki-NLP Opus-MT (" + want.joinToString(" + ") { "opus-mt-$it" } +
            ") from Hugging Face. Translation runs on this phone" +
            (if (TranslateConfig.opusOnly(prefs[Prefs.TR_SOURCE], prefs[Prefs.TR_TARGET]) != null) "." else "; ML Kit fills in when they aren't ready.")
        val b = AlertDialog.Builder(this).setTitle(Prefs.OPUS_MODELS.title)
            .setMessage(OpusPacks.statusLine(this) + (if (onDisk > 0) "\nUsing ${OpusPacks.mb(onDisk)} on this phone." else "") + from)
            .setNegativeButton("Close", null)
        val guard = OpusPacks.guard(this)
        when (guard.blocked()) {
            OpusGuard.Block.CPU -> {} // nothing to download or retry until the engine is updated
            OpusGuard.Block.CRASHED -> b.setPositiveButton("Try Opus-MT again") { _, _ -> guard.reset(); OpusPacks.sync(this); refresh() }
            null -> {
                if (want.isNotEmpty() && !ready) b.setPositiveButton("Download") { _, _ -> OpusPacks.downloadAgain(this); refresh() }
                if (ready) b.setPositiveButton("Download again") { _, _ -> OpusPacks.delete(this); OpusPacks.downloadAgain(this); refresh() }
            }
        }
        if (onDisk > 0 || (want.isNotEmpty() && !OpusPacks.isOff(this)))
            b.setNeutralButton("Delete") { _, _ -> OpusPacks.delete(this); refresh() }
        b.show()
    }

    /**
     * The speech pack page (Settings → On this phone → Speech packs): the pack for the phone's language first, then
     * the packs on the phone, the others you can download, and the 1,600-language pack last (it's big). Each row is one
     * short line (what it does · size) with Delete or Download, and "In use" on the pack the dictation language uses;
     * tapping another pack on the phone makes its language the dictation language. Sharper English is one choice on the
     * English row (Off / Small / Best), and the punctuation pack is looked after behind the scenes (SpeechPacks.tidy).
     * Downloads follow "Download on Wi-Fi only" (off by default: mobile data works).
     */
    private fun packsPage(col: LinearLayout) {
        SpeechPacks.tidy(this, publish = false); SpeechPacks.sync(this) // phones from before the Sharper choice catch up
        val installed = SpeechPacks.installed(this)
        packsShown = installed
        val base = SpeechPackCatalog.LISTED
        val suggested = SpeechPackCatalog.suggestedFor(java.util.Locale.getDefault().language)
        val global = SpeechPackCatalog.pack(SpeechPackCatalog.GLOBAL)!!
        val sug = suggested.takeIf { it.id !in installed }
        val mine = base.filter { it.id in installed }
        val others = base.filter { it.id !in installed && it !== sug && it !== global }
        val last = global.takeIf { it.id !in installed && it !== sug }
        fun rows(pk: SpeechPackCatalog.Pack) = if (pk.id == "en") listOf(packRow(pk), sharperQuestion() ?: sharperRow()) else listOf(packRow(pk))
        sug?.let { col.addView(label("Suggested for this phone")); col.addView(card(rows(it))) }
        if (mine.isNotEmpty()) { col.addView(label("On this phone")); col.addView(card(mine.flatMap { rows(it) })) }
        if (others.isNotEmpty()) { col.addView(label("Available")); col.addView(card(others.flatMap { rows(it) })) }
        last?.let { col.addView(label("Every language")); col.addView(card(rows(it))) }
        col.addView(TextView(this).apply {
            text = "Your voice never leaves this phone."
            textSize = 13f; setTextColor(getColor(R.color.text2)); setPadding(dp(14), dp(14), dp(14), 0)
        })
    }

    /**
     * Asked once, under the English pack once it's on the phone: "Get Sharper English?" with both sizes, or No thanks.
     * Any answer hides it for good; the Sharper English choice takes its place on the English row.
     */
    private fun sharperQuestion(): View? {
        if (!SpeechPacks.askSharper(this)) return null
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(8)) }
        box.addView(TextView(this).apply { text = "Get Sharper English?"; textSize = 16f; setTextColor(getColor(R.color.text)) })
        box.addView(TextView(this).apply {
            text = "Fewer mistakes when you speak English: each sentence is written again when you pause."
            textSize = 14f; setTextColor(getColor(R.color.text2)); setPadding(0, dp(4), 0, dp(4))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        box.addView(row)
        fun choice(label: String, pack: String?) = TextView(this).apply {
            text = label; textSize = 15f; setTextColor(getColor(R.color.accent)); setPadding(dp(10), dp(8), dp(4), dp(8))
            background = ripple(); contentDescription = label
            setOnClickListener { SpeechPacks.sharperAsked(this@SettingsActivity); pack?.let { SpeechPacks.chooseSharper(this@SettingsActivity, it) }; build() }
        }.also { row.addView(it) }
        choice("No thanks", null)
        for ((name, id) in SHARPER_SIZES) choice("$name · ${SpeechPackCatalog.mb(SpeechPackCatalog.pack(id)!!.bytes)}", id)
        return box
    }

    /** "Sharper English: Off / Small / Best" on the English row. Tapping opens the three choices. */
    private fun sharperRow(): View {
        val r = Row("Sharper English")
        r.view.setPadding(dp(32), dp(6), dp(12), dp(10))
        val value = r.value()
        refreshers.add {
            val chosen = SpeechPacks.sharper(this)
            value.text = SHARPER_SIZES.firstOrNull { it.second == chosen }?.first ?: "Off"
            val pk = chosen?.let { SpeechPackCatalog.pack(it) }
            r.sub(when {
                pk == null -> "Fewer mistakes: each sentence is written again when you pause"
                SpeechPacks.isReady(this, pk) -> pk.about + " · " + SpeechPackCatalog.mb(SpeechPacks.bytesOnDisk(this, pk.id))
                else -> SpeechPacks.statusLine(this, pk)
            })
            r.view.contentDescription = "Sharper English: ${value.text}"
        }
        r.view.setOnClickListener { sharperDialog() }
        return r.view
    }

    private fun sharperDialog() {
        val chosen = SpeechPacks.sharper(this)
        val ids = listOf<String?>(null) + SHARPER_SIZES.map { it.second }
        val names = listOf("Off") + SHARPER_SIZES.map { (n, id) ->
            val pk = SpeechPackCatalog.pack(id)!!
            "$n · ${SpeechPackCatalog.mb(pk.bytes)} · ${pk.about.replaceFirstChar { it.lowercase() }}"
        }
        AlertDialog.Builder(this).setTitle("Sharper English")
            .setSingleChoiceItems(names.toTypedArray(), ids.indexOf(chosen)) { d, which ->
                d.dismiss()
                val target = ids[which]
                if (target != chosen) confirmSharper(chosen, target)
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Switching asks before it deletes the other size (or turns Sharper English off), so no unused pack is left. */
    private fun confirmSharper(from: String?, to: String?) {
        fun name(id: String) = SHARPER_SIZES.first { it.second == id }.first
        val go = { SpeechPacks.sharperAsked(this); SpeechPacks.chooseSharper(this, to); refresh() }
        val old = from?.let { SpeechPackCatalog.pack(it) }
        if (old == null || !SpeechPacks.isReady(this, old)) {
            go(); return // nothing on the phone to delete (an unfinished download just stops)
        }
        val oldSize = SpeechPackCatalog.mb(SpeechPacks.bytesOnDisk(this, old.id))
        val newPk = to?.let { SpeechPackCatalog.pack(it) }
        val msg = when {
            newPk == null -> "Deletes ${name(old.id)} ($oldSize) from this phone. You can get it again later."
            SpeechPacks.isReady(this, newPk) -> "Deletes ${name(old.id)} ($oldSize) from this phone."
            else -> "Downloads ${name(newPk.id)} (${SpeechPackCatalog.mb(newPk.bytes)}), then deletes ${name(old.id)} ($oldSize)."
        }
        AlertDialog.Builder(this).setTitle(if (newPk == null) "Turn off Sharper English?" else "Switch to ${name(newPk.id)}?")
            .setMessage(msg)
            .setPositiveButton(if (newPk == null) "Turn off" else "Switch") { _, _ -> go() }
            .setNegativeButton("Cancel", null).show()
    }

    /**
     * One pack: one short line (what it does · size, or its download), Download / Cancel / Delete, and "In use" on the
     * pack the dictation language uses. Tapping a pack on the phone that isn't in use makes its language the dictation
     * language (the line ends "Tap to use").
     */
    private fun packRow(pk: SpeechPackCatalog.Pack): View {
        val r = Row(pk.name)
        val tag = TextView(this).apply {
            text = "In use"; textSize = 13f; setTextColor(getColor(R.color.text2)); setPadding(dp(8), dp(8), dp(4), dp(8))
        }
        val action = TextView(this).apply {
            textSize = 15f; setTextColor(getColor(R.color.accent)); setPadding(dp(12), dp(8), dp(4), dp(8)); background = ripple()
        }
        r.end.addView(tag); r.end.addView(action)
        var canUse = false
        refreshers.add {
            val ready = SpeechPacks.isReady(this, pk)
            val language = prefs[Prefs.DG_LANGUAGE]
            val inUse = ready && pk.covers(language) && SpeechPackCatalog.choose(language, SpeechPacks.installed(this))?.id == pk.id
            canUse = ready && pk.primary != null && !inUse
            r.sub(SpeechPacks.statusLine(this, pk) + if (canUse) " · Tap to use" else "")
            tag.visibility = if (inUse) View.VISIBLE else View.GONE
            action.text = when {
                ready -> "Delete"
                pk.id in SpeechPacks.wanted(this) -> "Cancel"
                else -> "Download"
            }
            action.contentDescription = "${action.text} ${pk.name}"
            r.view.contentDescription = if (canUse) "Use ${pk.name} for dictation" else null
            r.view.isClickable = canUse
        }
        r.view.setOnClickListener {
            val code = pk.primary ?: return@setOnClickListener
            if (!canUse) return@setOnClickListener
            prefs.edit().putString(Prefs.DG_LANGUAGE.key, code).apply()
            android.widget.Toast.makeText(this, "Dictation language: ${SpeechSlots.languageName(code)}. " +
                SpeechSlots.engineLine(speechSlots(), code), android.widget.Toast.LENGTH_LONG).show()
            refresh()
        }
        action.setOnClickListener {
            when {
                SpeechPacks.isReady(this, pk) -> AlertDialog.Builder(this).setTitle("Delete ${pk.name}?")
                    .setMessage("Frees ${SpeechPackCatalog.mb(SpeechPacks.bytesOnDisk(this, pk.id))}. You can download it again later.")
                    .setPositiveButton("Delete") { _, _ -> SpeechPacks.delete(this, pk.id); build() }
                    .setNegativeButton("Cancel", null).show()
                pk.id in SpeechPacks.wanted(this) -> { SpeechPacks.delete(this, pk.id); refresh() }
                else -> { SpeechPacks.download(this, pk.id); refresh() }
            }
        }
        return r.view
    }

    /** Long lists (languages): a search box above a scrollable single-choice list. Shows names, stores codes. */
    private fun searchablePicker(p: ChoicePref, choose: (String) -> Unit) {
        val current = prefs[p]
        val translation = p === Prefs.TR_SOURCE || p === Prefs.TR_TARGET
        // The dictation list moves languages with a speech pack on the phone up and says who dictates them.
        val dictation = p === Prefs.DG_LANGUAGE
        val options = if (dictation) SpeechSlots.languageChoices(speechSlots()) else p.options
        val hints = if (dictation) SpeechSlots.languageHints(speechSlots()) else p.hints
        var shown = options
        val list = android.widget.ListView(this).apply { choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE }
        val adapter = android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, ArrayList())
        list.adapter = adapter
        fun filter(q: String) {
            val t = q.trim()
            shown = if (t.isEmpty()) options else options.filter { it.second.contains(t, true) || it.first.contains(t, true) }
            adapter.clear(); adapter.addAll(shown.map { (c, n) -> hints[c]?.let { "$n\n$it" } ?: n })
            list.clearChoices()
            shown.indexOfFirst { it.first == current }.takeIf { it >= 0 }?.let { list.setItemChecked(it, true) }
        }
        val search = editor(false, "Search languages").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = filter(s?.toString().orEmpty())
        })
        val note = TextView(this).apply {
            text = if (!translation) "The app picks a provider that knows the language (Settings → Providers). \"On this phone\" ones have a speech pack downloaded and also work without internet."
                else "The 59 languages the on-device translator knows, plus Malayalam once its offline model is downloaded."
            textSize = 12f
            setTextColor(getColor(R.color.text2)); setPadding(dp(4), dp(4), 0, dp(4))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(search); addView(note)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360)))
        }
        filter("")
        list.setSelection(shown.indexOfFirst { it.first == current }.coerceAtLeast(0))
        val dialog = AlertDialog.Builder(this).setTitle(p.title).setView(box).setNegativeButton("Cancel", null).create()
        list.setOnItemClickListener { _, _, pos, _ -> dialog.dismiss(); choose(shown[pos].first) }
        dialog.show()
    }

    private fun editor(multiline: Boolean, hint: String): EditText = EditText(this).apply {
        this.hint = hint
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_VARIATION_URI)
        if (multiline) { minLines = 3; maxLines = 8; typeface = Typeface.MONOSPACE; textSize = 14f } else isSingleLine = true
    }

    private fun wrap(v: View): View = FrameLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(v)
    }

    companion object {
        /** Which provider page to show ([Prefs.ProviderPage.id]); none = the main Settings screen. */
        const val EXTRA_PAGE = "page"
        /** [EXTRA_PAGE] for the speech pack page. */
        const val PAGE_PACKS = "packs"
        /** [EXTRA_PAGE] for Settings → Providers. */
        const val PAGE_PROVIDERS = "providers"
        /** The Sharper English choice's two sizes, as the page and setup name them. */
        val SHARPER_SIZES = listOf("Small" to SpeechPackCatalog.RECHECK_SMALL, "Best" to SpeechPackCatalog.RECHECK)
    }
}
