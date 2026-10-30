package com.anoop.dailywhisper

/**
 * The offline speech packs for speech slot 3 ("On this phone", Sherpa-ONNX). Every file is pinned to a Hugging Face
 * commit with its exact size and SHA-256 (checked before it is used, like the Opus-MT models). Pure Kotlin (no Android
 * imports) so the JVM tests and tools/translate-harness can check it.
 *
 * - en: live, word by word (streaming Zipformer transducer). Mirror on akm9191, Apache-2.0 (k2-fsa / icefall).
 * - ml: sentence by sentence (AI4Bharat IndicConformer CTC, MIT), cut by the voice-activity detector. Converted by us:
 *   vocabulary sliced to Malayalam, subsampling fixed, int8. FLEURS ml test (60 clips): WER 26.7%, CER 5.8%.
 * - global: sentence by sentence, Meta Omnilingual ASR 300M CTC int8 (Apache-2.0), 1,600+ languages. From k2-fsa's
 *   own repo. Last on the pack page because it is big.
 * The voice-activity detector (Silero VAD, MIT, 0.6 MB) comes with every sentence-by-sentence pack.
 *
 * Add-ons ([Pack.addonFor]) aren't recognisers on their own; they make a base pack better and need it on the phone:
 * - en-recheck ("Sharper English"): NVIDIA Parakeet TDT 0.6B v2 int8 (CC-BY-4.0) + Silero VAD. Two-pass: the English
 *   pack still writes words live, and when you pause the whole sentence is written again by Parakeet. Box test on 400
 *   clips (EdACC conversational accents incl. Indian English, LibriSpeech test-other): word errors 38% → 12% (Indian
 *   English), 45% → 15% (other accents), 10% → 5% (LibriSpeech). It writes its own punctuation and capitals.
 * - punct-en ("English punctuation"): Edge-Punct-Casing CNN-BiLSTM int8 (Apache-2.0), adds . , ? and capitals to the
 *   English pack's sentences when Sharper English isn't used.
 */
object SpeechPackCatalog {
    enum class Mode { LIVE, SENTENCES }
    /** The sherpa-onnx model family, which decides how the files are wired up (OnDeviceSpeech). */
    enum class Kind { STREAMING_TRANSDUCER, NEMO_CTC, OMNILINGUAL_CTC, NEMO_TRANSDUCER, PUNCTUATION }

    class PackFile(val repo: String, val revision: String, val remote: String, val local: String, val size: Long, val sha256: String) {
        val url get() = "https://huggingface.co/$repo/resolve/$revision/$remote"
    }

    class Pack(val id: String, val name: String, val mode: Mode, val kind: Kind, val files: List<PackFile>,
               /** Dictation language codes (SpeechSlots.LANGUAGES) this pack can write; null = any language. */
               val languages: Set<String>?, val credit: String,
               /** What the pack is for, in plain words (the pack page shows it). */
               val about: String = "",
               /** An add-on improves this base pack and needs it; it never dictates on its own. */
               val addonFor: String? = null,
               /** The dictation language "Use for dictation" picks (null: the pack has no single language). */
               val primary: String? = null) {
        val bytes: Long get() = files.sumOf { it.size }
        fun covers(code: String) = languages == null || code in languages
    }

    private const val VAD_REPO = "akm9191/silero-vad-onnx"
    private const val VAD_REV = "ce75b491cbab8e530f7b3323c8fd095cbf9f52f4"
    val VAD = PackFile(VAD_REPO, VAD_REV, "silero_vad.onnx", "silero_vad.onnx", 643_854,
        "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6")

    private const val EN_REPO = "akm9191/sherpa-onnx-streaming-zipformer-en-int8"
    private const val EN_REV = "aadbecdd16ea73b4f28099833ae98a6bd6016161"
    private const val ML_REPO = "akm9191/sherpa-onnx-indicconformer-ml-int8"
    private const val ML_REV = "0ea74b1557da742520c68f0c9777bb4d0147c766"
    private const val RC_REPO = "akm9191/sherpa-onnx-parakeet-tdt-0.6b-v2-int8"
    private const val RC_REV = "7ebc1d93f5d105d99ea1fd23ab93aa96b6d5f416"
    private const val PU_REPO = "akm9191/sherpa-onnx-online-punct-en-int8"
    private const val PU_REV = "fa106735928b4eb94b9dbc39cc8b394f386e5ef1"
    private const val GL_REPO = "csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12"
    private const val GL_REV = "6abf1ece20cd2308bdb7d13cd78ec1c44fa4c094"

    val ENGLISH_CODES = setOf("en", "en-AU", "en-CA", "en-IN", "en-NZ", "en-GB", "en-US")

    val PACKS: List<Pack> = listOf(
        Pack("en", "English", Mode.LIVE, Kind.STREAMING_TRANSDUCER, listOf(
            PackFile(EN_REPO, EN_REV, "encoder.int8.onnx", "encoder.onnx", 71_083_163, "563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1"),
            PackFile(EN_REPO, EN_REV, "decoder.int8.onnx", "decoder.onnx", 1_307_236, "98da299f471e38bb4e1a8df579b8cc9122d6039576a77e357b3c60f17dd83b02"),
            PackFile(EN_REPO, EN_REV, "joiner.int8.onnx", "joiner.onnx", 259_335, "d944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297"),
            PackFile(EN_REPO, EN_REV, "tokens.txt", "tokens.txt", 5_048, "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb"),
        ), ENGLISH_CODES, "Streaming Zipformer (k2-fsa / icefall), Apache-2.0",
            about = "English dictation without internet. Words appear as you speak.", primary = "en"),
        Pack("ml", "Malayalam", Mode.SENTENCES, Kind.NEMO_CTC, listOf(
            PackFile(ML_REPO, ML_REV, "model.int8.onnx", "model.onnx", 137_677_591, "390b8f9e7092324d9da586829114db62f792e917d59e86e57771f058ff0d6733"),
            PackFile(ML_REPO, ML_REV, "tokens.txt", "tokens.txt", 2_823, "6b025c3055708763a952d21b73556ed02c9abd88cc706ff42e3cb94a984bdb88"),
            VAD,
        ), setOf("ml"), "IndicConformer (AI4Bharat), MIT; Silero VAD, MIT",
            about = "Malayalam dictation on this phone. Writes each sentence after you pause.", primary = "ml"),
        Pack("global", "All languages (1,600+)", Mode.SENTENCES, Kind.OMNILINGUAL_CTC, listOf(
            PackFile(GL_REPO, GL_REV, "model.int8.onnx", "model.onnx", 365_352_120, "e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c"),
            PackFile(GL_REPO, GL_REV, "tokens.txt", "tokens.txt", 86_423, "a7a044c52cb29cbe8b0dc1953e92cefd4ca16b0ed968177b6beab21f9a7d0b31"),
            VAD,
        ), null, "Omnilingual ASR (Meta), Apache-2.0; Silero VAD, MIT",
            about = "Any other language, for when there's no pack of its own. Writes each sentence after you pause; less accurate."),
        Pack(RECHECK, "Sharper English", Mode.SENTENCES, Kind.NEMO_TRANSDUCER, listOf(
            PackFile(RC_REPO, RC_REV, "encoder.int8.onnx", "encoder.onnx", 652_184_296, "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"),
            PackFile(RC_REPO, RC_REV, "decoder.int8.onnx", "decoder.onnx", 7_257_753, "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"),
            PackFile(RC_REPO, RC_REV, "joiner.int8.onnx", "joiner.onnx", 1_739_080, "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"),
            PackFile(RC_REPO, RC_REV, "tokens.txt", "tokens.txt", 9_384, "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"),
            VAD,
        ), ENGLISH_CODES, "Parakeet TDT 0.6B v2 (NVIDIA), CC-BY-4.0; Silero VAD, MIT",
            about = "Fewer English mistakes, with punctuation. When you pause, the sentence is written again more " +
                "carefully. Needs the English pack.", addonFor = "en"),
        Pack(PUNCT, "English punctuation", Mode.SENTENCES, Kind.PUNCTUATION, listOf(
            PackFile(PU_REPO, PU_REV, "model.int8.onnx", "model.onnx", 7_490_500, "9d611f445fe4a46186080fe161be6059d87d72eb88d3a8cb00c1a06e83a6067e"),
            PackFile(PU_REPO, PU_REV, "bpe.vocab", "bpe.vocab", 149_430, "e118b7ad88c54db562517df49e1cffd4836d166c34fb190fd311d7f34eb238f5"),
        ), ENGLISH_CODES, "Edge-Punct-Casing CNN-BiLSTM (frankyoujian), Apache-2.0",
            about = "Adds full stops, commas and capitals to English typed on this phone. Not needed with Sharper English.",
            addonFor = "en"),
    )

    const val GLOBAL = "global"
    const val RECHECK = "en-recheck"
    const val PUNCT = "punct-en"

    fun pack(id: String): Pack? = PACKS.firstOrNull { it.id == id }

    /**
     * Packs that can write [code], best first. A language's own pack comes before the 1,600-language one. The two
     * automatic modes have no single language: Multilingual tries the big pack, then English; Auto (Sarvam AI), made for
     * Indian languages, tries the big pack, then Malayalam, then English.
     */
    fun packsFor(code: String): List<Pack> = when (code) {
        "multi" -> listOfNotNull(pack(GLOBAL), pack("en"))
        SpeechSlots.AUTO_SARVAM -> listOfNotNull(pack(GLOBAL), pack("ml"), pack("en"))
        else -> PACKS.filter { it.addonFor == null && it.languages != null && code in it.languages } + listOfNotNull(pack(GLOBAL))
    }

    /** The add-ons on the phone ([installed]) that improve [base]. */
    fun addons(base: Pack, installed: Set<String>): List<Pack> = PACKS.filter { it.addonFor == base.id && it.id in installed }

    /**
     * The dictation languages an installed pack of their own covers (not the 1,600-language pack, not add-ons), so the
     * Language list can show them as working on this phone.
     */
    fun ownPackLanguages(installed: Set<String>): Set<String> =
        PACKS.filter { it.id in installed && it.addonFor == null && it.languages != null }.flatMap { it.languages!! }.toSet()

    /** The pack to use for [code] among the [installed] pack ids, or null. */
    fun choose(code: String, installed: Set<String>): Pack? = packsFor(code).firstOrNull { it.id in installed }

    /**
     * The pack to suggest for a phone or setup language ([language] is a plain ISO code such as "ml", "en" or "fr"):
     * the language's own pack if there is one, else the 1,600-language pack.
     */
    fun suggestedFor(language: String): Pack {
        val base = language.substringBefore('-').substringBefore('_').lowercase()
        return PACKS.firstOrNull { p -> p.addonFor == null && p.languages != null && p.languages.any { it.substringBefore('-') == base } } ?: pack(GLOBAL)!!
    }

    fun mb(bytes: Long) = "${(bytes + 500_000) / 1_000_000} MB"
}
