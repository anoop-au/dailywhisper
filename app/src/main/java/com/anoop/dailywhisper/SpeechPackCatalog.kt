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
 */
object SpeechPackCatalog {
    enum class Mode { LIVE, SENTENCES }
    /** The sherpa-onnx model family, which decides how the files are wired up (OnDeviceSpeech). */
    enum class Kind { STREAMING_TRANSDUCER, NEMO_CTC, OMNILINGUAL_CTC }

    class PackFile(val repo: String, val revision: String, val remote: String, val local: String, val size: Long, val sha256: String) {
        val url get() = "https://huggingface.co/$repo/resolve/$revision/$remote"
    }

    class Pack(val id: String, val name: String, val mode: Mode, val kind: Kind, val files: List<PackFile>,
               /** Dictation language codes (SpeechSlots.LANGUAGES) this pack can write; null = any language. */
               val languages: Set<String>?, val credit: String) {
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
    private const val GL_REPO = "csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12"
    private const val GL_REV = "6abf1ece20cd2308bdb7d13cd78ec1c44fa4c094"

    val ENGLISH_CODES = setOf("en", "en-AU", "en-CA", "en-IN", "en-NZ", "en-GB", "en-US")

    val PACKS: List<Pack> = listOf(
        Pack("en", "English", Mode.LIVE, Kind.STREAMING_TRANSDUCER, listOf(
            PackFile(EN_REPO, EN_REV, "encoder.int8.onnx", "encoder.onnx", 71_083_163, "563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1"),
            PackFile(EN_REPO, EN_REV, "decoder.int8.onnx", "decoder.onnx", 1_307_236, "98da299f471e38bb4e1a8df579b8cc9122d6039576a77e357b3c60f17dd83b02"),
            PackFile(EN_REPO, EN_REV, "joiner.int8.onnx", "joiner.onnx", 259_335, "d944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297"),
            PackFile(EN_REPO, EN_REV, "tokens.txt", "tokens.txt", 5_048, "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb"),
        ), ENGLISH_CODES, "Streaming Zipformer (k2-fsa / icefall), Apache-2.0"),
        Pack("ml", "Malayalam", Mode.SENTENCES, Kind.NEMO_CTC, listOf(
            PackFile(ML_REPO, ML_REV, "model.int8.onnx", "model.onnx", 137_677_591, "390b8f9e7092324d9da586829114db62f792e917d59e86e57771f058ff0d6733"),
            PackFile(ML_REPO, ML_REV, "tokens.txt", "tokens.txt", 2_823, "6b025c3055708763a952d21b73556ed02c9abd88cc706ff42e3cb94a984bdb88"),
            VAD,
        ), setOf("ml"), "IndicConformer (AI4Bharat), MIT; Silero VAD, MIT"),
        Pack("global", "All languages (1,600+)", Mode.SENTENCES, Kind.OMNILINGUAL_CTC, listOf(
            PackFile(GL_REPO, GL_REV, "model.int8.onnx", "model.onnx", 365_352_120, "e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c"),
            PackFile(GL_REPO, GL_REV, "tokens.txt", "tokens.txt", 86_423, "a7a044c52cb29cbe8b0dc1953e92cefd4ca16b0ed968177b6beab21f9a7d0b31"),
            VAD,
        ), null, "Omnilingual ASR (Meta), Apache-2.0; Silero VAD, MIT"),
    )

    const val GLOBAL = "global"

    fun pack(id: String): Pack? = PACKS.firstOrNull { it.id == id }

    /**
     * Packs that can write [code], best first. A language's own pack comes before the 1,600-language one. The two
     * automatic modes have no single language: Multilingual tries the big pack, then English; Auto (Sarvam AI), made for
     * Indian languages, tries the big pack, then Malayalam, then English.
     */
    fun packsFor(code: String): List<Pack> = when (code) {
        "multi" -> listOfNotNull(pack(GLOBAL), pack("en"))
        SpeechSlots.AUTO_SARVAM -> listOfNotNull(pack(GLOBAL), pack("ml"), pack("en"))
        else -> PACKS.filter { it.languages != null && code in it.languages } + listOfNotNull(pack(GLOBAL))
    }

    /** The pack to use for [code] among the [installed] pack ids, or null. */
    fun choose(code: String, installed: Set<String>): Pack? = packsFor(code).firstOrNull { it.id in installed }

    /**
     * The pack to suggest for a phone or setup language ([language] is a plain ISO code such as "ml", "en" or "fr"):
     * the language's own pack if there is one, else the 1,600-language pack.
     */
    fun suggestedFor(language: String): Pack {
        val base = language.substringBefore('-').substringBefore('_').lowercase()
        return PACKS.firstOrNull { p -> p.languages != null && p.languages.any { it.substringBefore('-') == base } } ?: pack(GLOBAL)!!
    }

    fun mb(bytes: Long) = "${(bytes + 500_000) / 1_000_000} MB"
}
