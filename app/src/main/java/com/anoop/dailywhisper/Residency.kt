package com.anoop.dailywhisper

/**
 * Where live dictation audio goes. The user picks their region; Deepgram has regional endpoints for Australia, the
 * EU and India (processing and storage stay in that region, with mip_opt_out=true which the app always sends) plus
 * its global endpoint (US-based). If the user's region has one, we use it and just say so. Otherwise we name where
 * audio would go and ask first; without a yes the mic stays off. Pure Kotlin so tools/ws-harness tests it.
 * Source: developers.deepgram.com/trust-security/your-data (Oct 2026).
 */
object Residency {
    /** [home]: the Deepgram region inside this region, if any. [nearest]: where audio goes otherwise. */
    class Region(val code: String, val name: String, val home: String?, val nearest: String)

    val REGIONS: List<Region> = listOf(
        Region("au", "Australia", "au", "au"),
        Region("nz", "New Zealand", null, "au"),
        Region("eu", "European Union", "eu", "eu"),
        Region("europe", "Rest of Europe (UK, Switzerland, Norway…)", null, "eu"),
        Region("us", "United States", "us", "us"),
        Region("ca", "Canada", null, "us"),
        Region("latam", "Latin America", null, "us"),
        Region("in", "India", "in", "in"),
        Region("asia", "Rest of Asia", null, "in"),
        Region("me", "Middle East", null, "in"),
        Region("africa", "Africa", null, "eu"),
        Region("other", "Somewhere else", null, "us"),
    )

    const val DEFAULT = "au"

    fun region(code: String): Region = REGIONS.firstOrNull { it.code == code } ?: REGIONS.first { it.code == DEFAULT }

    /** Plain place name for a Deepgram region code (LiveDictationConfig.REGIONS). */
    fun placeOf(dg: String): String = when (dg) {
        "au" -> "Australia"; "eu" -> "the EU"; "in" -> "India"; else -> "the US"
    }

    /** The Deepgram region to use for someone in [region]: theirs if Deepgram has it, else the nearest. */
    fun endpointFor(region: String): String = region(region).let { it.home ?: it.nearest }

    /** True when audio sent to Deepgram region [dg] stays in the user's [region]. */
    fun inRegion(region: String, dg: String): Boolean = region(region).home == dg

    /** Live dictation may run: audio stays in-region, or the user said yes to sending it to [dg] ([consentedTo]). */
    fun allowed(region: String, dg: String, consentedTo: String): Boolean = inRegion(region, dg) || consentedTo == dg

    /** The short line shown when audio stays in-region. */
    fun inRegionLine(dg: String): String =
        "Live dictation uses Deepgram in ${placeOf(dg)}, so your voice stays in your region. Nothing is kept after it's typed."

    /** The consent question when it can't. */
    fun consentLine(region: String, dg: String): String =
        "Deepgram doesn't process audio in ${region(region).name}. Live dictation would send your voice to " +
            "${placeOf(dg)} to type it, and nothing is kept after. Is that OK?"

    // ---- First-run setup copy: plain language, no provider or endpoint names (details live in the Privacy Policy) ----

    /** Setup's line when audio stays in-region. */
    const val SETUP_IN_REGION_LINE = "Live dictation keeps your voice in your region. Nothing is kept after it’s typed."

    /** Setup's consent question when it can't. */
    fun setupConsentLine(region: String, dg: String): String =
        "Live dictation can't keep your voice in ${region(region).name}. It would go to ${placeOf(dg)} to be typed, " +
            "and nothing is kept after. Is that OK?"

    /** Setup's pointer to the full details. */
    const val SETUP_PRIVACY_LINE = "Full details are in the Privacy Policy."

    // ---- Sarvam AI (speech slot 2): India only ----
    // Sarvam processes audio only in India (sarvam.ai/speech-to-text, docs.sarvam.ai, Oct 2026). No other region.

    /** True when audio sent to Sarvam AI stays in the user's [region] (only India). */
    fun sarvamInRegion(region: String): Boolean = region(region).code == "in"

    /** Sarvam AI may be used: the user is in India, or said yes to sending audio there. */
    fun sarvamAllowed(region: String, consented: Boolean): Boolean = sarvamInRegion(region) || consented

    fun sarvamInRegionLine(): String =
        "Speech slot 2 uses Sarvam AI in India, so your voice stays in your region."

    fun sarvamConsentLine(region: String): String =
        "Sarvam AI (speech slot 2) only processes audio in India. Using it would send your voice from " +
            "${region(region).name} to India to type it. Is that OK?"
}
