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
 * they are tried (each opens its own page: this activity with [EXTRA_PAGE]), Notes, then everything else (Composer,
 * Translation, Look & layout, Advanced, Reset). Every setting in [Prefs] is on one of these screens, with its default.
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

    override fun onResume() { super.onResume(); OpusPacks.listen(onProgress); refresh() }
    override fun onPause() { super.onPause(); OpusPacks.unlisten(onProgress) }

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
            text = page?.provider?.label ?: getString(R.string.settings); textSize = 24f; typeface = Typeface.SERIF
            setTextColor(getColor(R.color.text)); setPadding(dp(8), 0, 0, 0)
        })
        root.addView(header)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(32)) }
        val pg = page
        if (pg != null) providerPage(col, pg) else mainPage(col)
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
            if (title == Prefs.DICTATION) {
                col.addView(label("Speech providers · tried in order"))
                col.addView(card(Prefs.providerPages.map { providerRow(it) } + addProviderRow()))
            }
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

    /** A provider's page: its settings in one card, and where the audio goes underneath (tap to change consent). */
    private fun providerPage(col: LinearLayout, pg: Prefs.ProviderPage) {
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        col.addView(card(pg.prefs.map { row(it) }))
        val footer = TextView(this).apply {
            textSize = 14f; setTextColor(getColor(R.color.text2)); gravity = Gravity.CENTER_VERTICAL
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_place, 0, 0, 0); compoundDrawablePadding = dp(6)
            setPadding(dp(14), dp(10), dp(14), dp(10)); background = ripple()
        }
        refreshers.add { footer.text = residencyLine(pg.residency) }
        footer.setOnClickListener { residencyTapped(pg.residency) }
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
    private val bareSwitches: Set<Pref> = setOf(Prefs.SHOW_MIC, Prefs.KEEP_SCREEN_ON, Prefs.SLOT1_ON, Prefs.SLOT2_ON)

    private fun row(p: Pref): View {
        val r = Row(p.title)
        val row = r.view
        when (p) {
            is ActionPref -> actionRow(p, r)
            is BoolPref -> {
                if (p !in bareSwitches) r.sub(p.summary)
                val sw = Switch(this).apply {
                    isChecked = prefs[p]
                    contentDescription = p.title
                    setOnCheckedChangeListener { _, v ->
                        prefs.edit().putBoolean(p.key, v).apply()
                        if (p === Prefs.TR_WIFI_ONLY) { OpusPacks.sync(this@SettingsActivity); refresh() }
                        if (p === Prefs.SLOT2_ON && v) askSarvamConsentIfNeeded()
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
                    val name = p.options.first { it.first == prefs[p] }.second
                    // Sarvam AI's long labels ("Saaras v3 realtime (default)") keep the part before the brackets here.
                    value.text = if (p === Prefs.SARVAM_MODEL || p === Prefs.SARVAM_MODE) name.substringBefore(" (") else name
                    r.sub(p.hints[prefs[p]]) // e.g. what "Auto (Sarvam AI)" does, under the title
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
                    if (p === Prefs.TR_SOURCE || p === Prefs.TR_TARGET) { OpusPacks.sync(this); refresh() } // re-derive the models
                    if (p === Prefs.DG_REGION) afterRegionChange()
                }
                if (p === Prefs.DG_REGION) refreshers.add { show() }
                if (p === Prefs.DG_LANGUAGE) refreshers.add { show() }
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
                    val name = page?.provider?.label?.let { "$it API key" } ?: p.title
                    AlertDialog.Builder(this).setTitle(name).setMessage(p.summary).setView(wrap(input))
                        .setPositiveButton("Save") { _, _ ->
                            val v = input.text.toString().trim()
                            if (v.isNotEmpty()) SecretStore.put(this, p.key, v)
                            input.setText(""); show()
                            if (v.isNotEmpty() && p === Prefs.SLOT2_KEY) askSarvamConsentIfNeeded()
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

    /** Main screen: one provider, numbered in the order it's tried, with a dot and a one-line status. Opens its page. */
    private fun providerRow(pg: Prefs.ProviderPage): View {
        val r = Row(pg.provider.label)
        r.view.addView(TextView(this).apply {
            text = pg.slot.toString(); textSize = 13f; gravity = Gravity.CENTER; setTextColor(getColor(R.color.accent))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(R.color.ripple)) }
        }, 0, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) })
        r.value()
        refreshers.add {
            val (ready, status) = providerStatus(pg)
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
    private fun providerStatus(pg: Prefs.ProviderPage): Pair<Boolean, String> {
        val slot = speechSlots().first { it.number == pg.slot }
        val what = pg.tagline.ifEmpty { if (pg.provider == SpeechProvider.DEEPGRAM) prefs[Prefs.DG_MODEL].ifEmpty { Prefs.DG_MODEL.def } else "" }
        val state = when {
            !slot.enabled -> "Off"
            !slot.hasKey -> "Needs an API key"
            !slot.residencyOk -> "Needs your OK to send audio"
            else -> "On"
        }
        return (state == "On") to listOf(state, what).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** "+ Add provider": slots 3 and 4 are reserved and empty for now. */
    private fun addProviderRow(): View {
        val v = TextView(this).apply {
            text = "Add provider"; textSize = 16f; setTextColor(getColor(R.color.accent)); gravity = Gravity.CENTER_VERTICAL
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_add, 0, 0, 0); compoundDrawablePadding = dp(10)
            minHeight = dp(52); setPadding(dp(16), dp(10), dp(12), dp(10)); background = ripple()
        }
        v.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Add provider")
                .setMessage(Prefs.emptySlots.joinToString("\n") { "${it.title}: not set" } +
                    "\n\nEmpty for now. A future version can put another speech provider here, with its own key.")
                .setPositiveButton("OK", null).show()
        }
        return v
    }

    /** Where the audio goes, for a provider page's footer. */
    private fun residencyLine(p: ActionPref): String = if (p === Prefs.SARVAM_RESIDENCY) when {
        Residency.sarvamInRegion(userRegion()) -> "Audio stays in India"
        sarvamConsent() -> "Audio goes to India. You allowed this"
        else -> "Off: needs your OK to send audio to India"
    } else {
        val dg = prefs[Prefs.DG_REGION]
        when {
            Residency.inRegion(userRegion(), dg) -> "Audio stays in ${Residency.placeOf(dg)}"
            liveDictationAllowed() -> "Audio goes to ${Residency.placeOf(dg)}. You allowed this"
            else -> "Mic off: needs your OK to send audio to ${Residency.placeOf(dg)}"
        }
    }

    private fun residencyTapped(p: ActionPref) {
        if (p === Prefs.SARVAM_RESIDENCY) {
            if (Residency.sarvamInRegion(userRegion()))
                AlertDialog.Builder(this).setTitle(p.title).setMessage(Residency.sarvamInRegionLine()).setPositiveButton("OK", null).show()
            else askSarvamConsent()
        } else {
            val dg = prefs[Prefs.DG_REGION]
            if (Residency.inRegion(userRegion(), dg))
                AlertDialog.Builder(this).setTitle(p.title).setMessage(Residency.inRegionLine(dg)).setPositiveButton("OK", null).show()
            else askConsent()
        }
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

    /** Sarvam AI only runs in India: when slot 2 is set up and on, ask once if the user is elsewhere and hasn't said yes. */
    private fun askSarvamConsentIfNeeded() {
        val usable = prefs[Prefs.SLOT2_ON] && SecretStore.has(this, Prefs.SLOT2_KEY.key)
        if (usable && !sarvamAllowed()) askSarvamConsent() else refresh()
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

    /** Long lists (languages): a search box above a scrollable single-choice list. Shows names, stores codes. */
    private fun searchablePicker(p: ChoicePref, choose: (String) -> Unit) {
        val current = prefs[p]
        val translation = p === Prefs.TR_SOURCE || p === Prefs.TR_TARGET
        val options = p.options
        var shown = options
        val list = android.widget.ListView(this).apply { choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE }
        val adapter = android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, ArrayList())
        list.adapter = adapter
        fun filter(q: String) {
            val t = q.trim()
            shown = if (t.isEmpty()) options else options.filter { it.second.contains(t, true) || it.first.contains(t, true) }
            adapter.clear(); adapter.addAll(shown.map { (c, n) -> p.hints[c]?.let { "$n\n$it" } ?: n })
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
            text = if (!translation) "Deepgram (provider 1) is used when it knows the language; \"(Sarvam AI)\" ones, including Auto, need Sarvam AI (provider 2)."
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
    }
}
