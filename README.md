# Daily Whisper

A calm, on-device Android notes app. The composer at the bottom is focused on launch, so Vion (or any
dictation app / keyboard) lands text straight into it; tap the round button to save it as a note.

Current release: **v0.1.16** (versionCode 17, 30 Oct 2026) – a smaller Sharper English choice (137 MB, quicker) next
to the 662 MB one, a separate Recheck pause (2 s) so slow sentences stay whole, and setup and the pack page offer
Sharper English to English speakers.

- Plain Kotlin + Android framework for the app's own code. Two libraries, both for on-device translation: Google ML Kit
  `translate:17.0.3` (brings AndroidX/Play-services pieces in transitively, 16 MB native library) and ONNX Runtime
  `onnxruntime-android:1.28.0` for Opus-MT (29 MB native library; kept below 1.29, whose Android build adds a telemetry
  provider and network permissions). Plus Sherpa-ONNX (Apache-2.0) for on-device dictation, built from source by
  `tools/sherpa-android/build.sh` against that same ONNX Runtime (3.3 MB `libsherpa-onnx-jni.so`, no second runtime).
  arm64 only. Release APK about 51 MB.
- Permissions: RECORD_AUDIO + INTERNET (live dictation to your speech provider; one-time model downloads) + ACCESS_NETWORK_STATE
  (downloads wait for Wi-Fi only if "Download on Wi-Fi only" is on; it's off by default). Notes never leave the phone;
  translation runs on it.
- Notes in app-private SQLite (`notes.db`), excluded from cloud backup and device transfer.
- Build: `source ~/android-env.sh && ./gradlew assembleRelease` -> `out/DailyWhisper-v<version>.apk` (add
  `-x copyReleaseApk` to leave `out/` alone and keep the APK in `app/build/outputs/apk/release/`)
- Release signing reads `~/dailywhisper-keys/keystore.properties` (outside the repo; override with
  `DAILYWHISPER_KEYSTORE_PROPS`). Never Vion's key.

## Notes features
- **History:** every save is a version (`note_versions`); the feed shows the latest. Note → History lists versions;
  Restore adds a new version, history is never rewritten.
- **Deleted:** delete is a soft delete (Undo bar on the feed); ⋮ → Deleted lists them, most recently deleted first, with
  Restore. "Delete forever" (with confirm) there is the only hard delete; it removes the note's history too. Deleted
  notes have no expiry.
- **Multi-select:** long-press a note → Share / Delete / Select. Select ticks that note and switches the top bar to
  ✕ (cancel), "N selected", Select all (the notes on screen, so it follows search) and Delete (greyed until one is
  picked). Tapping a note toggles it. Delete soft-deletes them all at once (one Undo bar; confirm only if "Ask before
  deleting" is on); ✕ or Back leaves without deleting. The composer hides while selecting and keeps its draft.
- **Archive:** Note → Archive hides it from the feed; ⋮ → Archive lists them, open one to move it back.
- **Highlight:** Note → Highlight tints the card (light/dark aware); default colour in Settings → Notes.
- **Search:** FTS4 full-text (`unicode61` tokenizer, `simple` if the phone's SQLite lacks it, `LIKE` if FTS fails),
  prefix per word, across active notes (and archived if the setting is on). Deleted notes are never searched.
- Schema and all SQL: `NoteSchema.kt`. DB v1 → v2 upgrade keeps every note and seeds one version each.

## First run (once after install, and once after updating to 0.1.7)
Welcome (icon + one line, 2.5 s by default (Settings → Advanced → Welcome screen time) or a tap) → source language → target language → region (explicit pick) →
where live-dictation audio goes → "You can change these later in Settings." → notes list. `SetupActivity.kt`.
- Region (`Residency.kt`): Deepgram has regional endpoints for Australia, the EU and India (+ its US-based global one).
  In one of those, dictation uses it and setup just says the audio stays in-region. Elsewhere it names where audio
  would go (nearest endpoint) and asks yes/no; without a yes the mic stays hidden until allowed in Settings → Deepgram
  (tap the "Audio …" line at the bottom). Every stream sends `mip_opt_out=true` (Deepgram keeps nothing).
- Region and consent live in the "state" prefs (Reset to defaults keeps them); languages in Settings → Translation.

## Settings screen
`SettingsActivity.kt`, laid out after Anoop's drawing (Oct 2026): rounded cards under small grey labels; each row is a
title with its value and a chevron (tap for a picker, slider or text dialog with its default) or a switch.
- Main screen: **Dictation** (Mic button, Language, Region, Keep screen on, Sentence pause), **Speech providers ·
  tried in order** (1 Deepgram, 2 Sarvam AI, each with a green/grey dot and a status such as "On · nova-3" or
  "Off · Indian languages", 3 On this phone, e.g. "On · English" or "Needs a speech pack"; its page has Speech packs),
  "+ Add provider" explains slot 4 is empty), **Notes**, then **Composer**,
  **Translation**, **Look & layout**, **Advanced** (welcome time, run setup again) and Reset to defaults.
- Each provider has its own page (same activity, `EXTRA_PAGE`): Deepgram = Use Deepgram, API key ("•••••", "Saved
  on this phone"), Model, Smart formatting, Processing region; Sarvam AI = Use Sarvam AI, API key, Model, Output,
  Keep English words in English. The line at the bottom ("Audio stays in Australia") shows where the audio goes;
  tapping it asks for or explains consent.
- `Prefs.sections` / `Prefs.providerPages` / `Prefs.emptySlots` list every setting; `SettingsLayoutTest` checks by
  reflection that each one in `Prefs` is on exactly one screen.

## Live dictation: 4 speech slots (bring your own key)
- Slot 1 **Deepgram** (`LiveDictationConfig.kt`), slot 2 **Sarvam AI** (`SarvamConfig.kt`), slot 3 **On this phone**
  (`OnDeviceSpeech.kt`, no key), slot 4 empty ("Not set"). Each slot has its own on/off switch, its own key (Android Keystore, `SecretStore.kt`; never shown or
  logged) and its own settings. `SpeechSlots.kt` picks the order; `SttConnect.kt` connects.
- The mic uses the first slot that is on, has a key, knows the dictation language and may receive the audio. If it
  can't connect or refuses the key, the next slot is tried before the mic starts. Malayalam and other Indian
  languages Deepgram lacks are marked "(Sarvam AI)" in the language list and go straight to slot 2.
- Two automatic choices sit at the top of the language list: **Multilingual (Deepgram)** (code `multi`, nova-3
  multilingual; Sarvam's `auto` is its fallback) and **Auto (Sarvam AI)** (code `auto-sarvam`), which goes only to
  slot 2 with `language_code=auto`, Sarvam's adaptive language detection over its 22 Indian languages and English.
  Deepgram never takes it; without a usable slot 2 the mic says what to fix (key, switch or India consent). Sarvam
  returns the detected `language` (e.g. `ml-IN`) on each transcript; the app shows "Sarvam AI hears Malayalam" once
  and again when it changes. Saved language codes are unchanged, so older choices keep working.
- Sarvam AI: realtime WebSocket `wss://api.sarvam.ai/speech-to-text-realtime/ws` (`saaras:v3-realtime` or
  `saaras:v4`), `api-subscription-key` header, base64 PCM in JSON `audio_input` messages. India only: outside India
  it needs a yes (Settings → Sarvam AI, the "Audio …" line at the bottom), like Deepgram's consent.
- **On this phone** (slot 3): Sherpa-ONNX speech-to-text on the phone, audio never leaves it. Needs a speech pack
  (`SpeechPackCatalog.kt`, pinned Hugging Face revisions + SHA-256; `SpeechPacks.kt` downloads like Opus-MT, job 4108):
  English 73 MB ([akm9191/sherpa-onnx-streaming-zipformer-en-int8](https://huggingface.co/akm9191/sherpa-onnx-streaming-zipformer-en-int8),
  word by word), Malayalam 139 MB ([akm9191/sherpa-onnx-indicconformer-ml-int8](https://huggingface.co/akm9191/sherpa-onnx-indicconformer-ml-int8),
  AI4Bharat IndicConformer, MIT; sentence at a time after each pause using
  [akm9191/silero-vad-onnx](https://huggingface.co/akm9191/silero-vad-onnx)), and All languages (1,600+) 366 MB (Meta
  Omnilingual ASR, Apache-2.0). Settings → On this phone → Speech packs lists the phone's language first, then installed (size,
  Delete), then available, then "All languages (1,600+)" under "Every language". Tried last when online; with no internet the mic uses it straight away
  ("No internet, using offline mode"), and if the connection drops mid-session it carries on there ("Connection lost,
  using offline mode"; switch "Switch when the internet drops", default on). Settings → On this phone also has
  Processor cores (default 2) and Longest sentence (default 20 s). Own crash guard: one native crash turns it off until
  "Tap to try again" or an app update.
  - Picking a pack (v0.1.15): every installed pack's language moves up the Settings → Language list and says
    "On this phone" (e.g. "Malayalam (Sarvam AI, On this phone)"); the Language row and the picker say which engine is used
    ("Uses Sarvam AI, then On this phone"); each pack on the Speech packs page says what it's for and has **Use** (sets the
    dictation language); **Use first** (default off) puts the phone ahead of Deepgram/Sarvam AI; the mic shows
    "Using On this phone · Malayalam" when the engine changes. Sarvam AI still comes first for Malayalam when it's on and online.
  - Better English (v0.1.15, optional packs, need English): **Sharper English** 662 MB
    ([akm9191/sherpa-onnx-parakeet-tdt-0.6b-v2-int8](https://huggingface.co/akm9191/sherpa-onnx-parakeet-tdt-0.6b-v2-int8),
    NVIDIA Parakeet TDT 0.6B v2, CC-BY-4.0): live words still come from the English pack, and each sentence is rewritten by
    Parakeet when you pause (Silero VAD), with punctuation and capitals. **English punctuation** 8 MB
    ([akm9191/sherpa-onnx-online-punct-en-int8](https://huggingface.co/akm9191/sherpa-onnx-online-punct-en-int8),
    Edge-Punct-Casing, Apache-2.0), used when Sharper English isn't. Settings → On this phone: Recheck each sentence (on),
    English punctuation (on), Quiet speech boost (10×, 1 = off; automatic gain for quiet voices), English search width
    (4, beam search; 1 = greedy). Trailing pad before the last word is 1.0 s (was 0.6 s).
  - v0.1.16: **Sharper English (small)** 137 MB
    ([akm9191/sherpa-onnx-parakeet-tdt-110m-int8](https://huggingface.co/akm9191/sherpa-onnx-parakeet-tdt-110m-int8),
    NVIDIA Parakeet TDT 110M, CC-BY-4.0), the smaller, faster choice (the big one is used when both are on the phone).
    **Recheck pause** (Settings → On this phone, default 2 s) is Sharper English's own sentence pause, so slow speakers'
    sentences aren't cut into pieces; the live Sentence pause stays 1.3 s. Setup offers Sharper English to English
    speakers (Small 137 MB / Best 662 MB, optional), and the pack page asks "Get Sharper English?" once under English.
  - Simpler Speech packs page (unreleased, on main): each pack is one row with one short line ("Live, word by word ·
    73 MB", "A sentence at a time · 138 MB", "Any other language, less accurate · 366 MB") and Delete/Download; the pack
    the dictation language uses is tagged **In use**, and tapping another pack on the phone makes its language the
    dictation language (its line ends "Tap to use"; this replaces the Use button). Sharper English is one choice on the
    English row: **Off / Small (137 MB) / Best (662 MB)**. Choosing one downloads it (with English if needed); switching
    asks first and deletes the other size once the new one is ready (Off deletes it), so no unused pack is left. Phones
    that already have both keep both and show Best (the one used) until you choose. The 8 MB English punctuation pack
    isn't listed any more: it downloads with English while Sharper English is Off (and English punctuation is on), and
    is removed once a Sharper English pack is on the phone. One footer line: "Your voice never leaves this phone."
- **Sentence pause** (Settings → Dictation, default 1.3 s, stored as 1300 ms): Deepgram `endpointing`, Sarvam
  `silence_duration_ms`. Deepgram's own default (~10 ms) ended a sentence with "?" or "." at every short breath.

## Translation (offline)
- **Opus-MT** (Helsinki-NLP MarianMT, int8 ONNX exports from the `Xenova/opus-mt-*` repos, pinned revisions + SHA-256
  in `OpusMtCatalog.kt`, regenerate with `tools/opus-catalog/gen.py`). Xenova has no Malayalam, so `en-ml` and `ml-en`
  are our own exports (same steps and layout: Optimum ONNX export, onnxruntime `quantize_dynamic` int8, merged decoder)
  in the public repos [akm9191/opus-mt-en-ml-int8-onnx](https://huggingface.co/akm9191/opus-mt-en-ml-int8-onnx) and
  [akm9191/opus-mt-ml-en-int8-onnx](https://huggingface.co/akm9191/opus-mt-ml-en-int8-onnx) (Apache-2.0, credited to
  Helsinki-NLP), set per model by `gen.py`'s `REPOS`. The pair comes from the two languages: the
  direct model, else source→English→target, else none (`OpusMt.route`). About 85–150 MB per model, fetched in the
  background by a JobScheduler job (`OpusPacks.kt`; Range resume, SHA-256 check, no notification) into app storage
  (`files/opus/<pair>/`). Any network by default, mobile data included (`NETWORK_TYPE_ANY`); with "Download on Wi-Fi
  only" on, unmetered only. Settings → Translation → Offline translation models shows status, Delete and Download
  again; changing a language re-derives the models.
- **Malayalam** is offered as a 60th language but only Opus-MT translates it (ML Kit has none;
  `TranslateConfig.Lang.mlKit`). Until its models are downloaded, or when the crash guard keeps Opus-MT off, the panel
  and Settings say so plainly (`OpusPacks.unavailable`) rather than falling back. Known weakness: code-mixed
  Malayalam + English (e.g. Sarvam's "Keep English words in English" dictation) translates badly.
- **ML Kit** is the fallback: used until the Opus-MT models are ready, when no model covers the pair (e.g.
  English→Japanese/Korean), or if Opus-MT fails. Its packs (~30 MB per language) are fetched once, through Android's
  Download Manager (a brief system download notification shows), into `no_backup/com.google.mlkit.translate.models`.
  Mobile data is allowed unless "Download on Wi-Fi only" is on (then `requireWifi()`, and the panel says it's waiting).
- **"Download on Wi-Fi only"** defaults to off. The value is only stored once the switch is touched, so installs
  that never changed it get the new default; someone who turned it on keeps it on.
- **Crash guard** (`OpusGuard.kt`): ONNX Runtime's native code can kill the process where no catch reaches. ONNX Runtime
  before 1.24.3 died with SIGILL on CPUs that report Arm SME (Snapdragon 8 Elite Gen 5, e.g. Galaxy Z Fold 8). The app
  now bundles 1.28.0, so `OpusGuard.ORT_SME_SAFE` is true and those phones run Opus-MT (the harness checks the flag
  against the build file). A marker around each Opus-MT run still catches any native death: Opus-MT then stays off
  (ML Kit translates) until an app update or Settings → Translation → Offline translation models → Try again.
- Open a note: it opens to read. **Tap the text to translate**; the result shows in a panel below with Copy,
  Keep both (saves the translation as a new note) and Replace (new version, Undo right there, original in History).
  ✎ in the header, or a long-press on the text, edits the note (✓ to finish). List: tap opens, long-press = Share/Delete.
- `NoteTranslator` (Translator.kt) translates a line at a time so the note's layout comes back exactly, and Opus-MT a
  sentence at a time (the models drop everything after the first sentence); the panel title says which engine did it.
  For Chinese and Japanese the spaces between sentences are dropped and a missing 。！？ is added
  (`TranslateConfig.sentenceSep` / `cjkEnd`).
- `OpusMt.kt`: JSON + SentencePiece-unigram tokenizer + **beam search** with KV cache, all beams in one batch. Width is
  Settings → Translation → Opus-MT search width (default 4, the models' own `num_beams`; 1 = greedy). Scores are
  summed log probability / length^0.5 (`OpusMt.LENGTH_PENALTY`). Greedy made en-zh repeat itself on short sentences
  ("I came home." → 我回到家,我回到家,…) and drop the end of long ones; a decode that still hits the step limit keeps one
  copy of a repeating span (`loopCut`). Fold 5: about 0.3–0.7 s per sentence with 4 beams, plus ~1 s to load a model.

## Tests (run on the box)
- `tools/translate-harness/run.sh [--opus <models>] [--hf]` – language list vs ML Kit's AAR, chunking, Opus-MT routes,
  JSON, tokenizer, sentence joins, loop cut, crash guard, downloads; `--opus <root>` runs real Opus-MT translations
  (greedy and 4-beam) through ONNX Runtime and must match the Python reference in `opus-ref.json` exactly
  (`make_ref.py`, onnxruntime 1.28.0, pairs en-de de-en en-hi hi-en ja-en en-es en-zh en-ml ml-en;
  `make_ref.py <root> en-ml ml-en` regenerates just those); `--hf` checks every catalog file at its pinned revision.
- `./gradlew testDebugUnitTest` – Robolectric: the first-run flow, Settings region/consent, model bookkeeping and the
  Wi-Fi-only default, the Settings layout (every setting reachable, provider pages), the Opus-MT crash guard, Malayalam (pickers, plain "not downloaded" / "not available" states, no
  ML Kit fallback), speech slots, and multi-select delete.
- `tools/notes-db-test/run.sh` – runs `NoteSchema.kt`'s SQL on real SQLite (upgrade, FTS, versions, soft delete).
- `tools/ws-harness/run.sh [--wss]` – live-dictation WebSocket client, parser, slot order and fallback against fake
  Deepgram and Sarvam AI servers; `--wss` also checks TLS to the real Deepgram and Sarvam hosts (no key: must refuse).
