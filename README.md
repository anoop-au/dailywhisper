# Daily Whisper

A calm, on-device Android notes app. The composer at the bottom is focused on launch, so Vion (or any
dictation app / keyboard) lands text straight into it; tap the round button to save it as a note.

Current release: **v0.1.10** (versionCode 11, 27 Oct 2026) – adds the "Auto (Sarvam AI)" dictation language
(Sarvam's own language detection) and renames Deepgram's automatic choice to "Multilingual (Deepgram)".

- Plain Kotlin + Android framework for the app's own code. Two libraries, both for on-device translation: Google ML Kit
  `translate:17.0.3` (brings AndroidX/Play-services pieces in transitively, 16 MB native library) and ONNX Runtime
  `onnxruntime-android:1.28.0` for Opus-MT (29 MB native library; kept below 1.29, whose Android build adds a telemetry
  provider and network permissions). arm64 only. Release APK about 47 MB.
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
Welcome (icon + one line, 2.5 s by default (Settings → Look & layout → Welcome screen time) or a tap) → source language → target language → region (explicit pick) →
where live-dictation audio goes → "You can change these later in Settings." → notes list. `SetupActivity.kt`.
- Region (`Residency.kt`): Deepgram has regional endpoints for Australia, the EU and India (+ its US-based global one).
  In one of those, dictation uses it and setup just says the audio stays in-region. Elsewhere it names where audio
  would go (nearest endpoint) and asks yes/no; without a yes the mic stays hidden until allowed in Settings → Live
  dictation → Where your voice goes. Every stream sends `mip_opt_out=true` (Deepgram keeps nothing).
- Region and consent live in the "state" prefs (Reset to defaults keeps them); languages in Settings → Translation.

## Live dictation: 4 speech slots (bring your own key)
- Slot 1 **Deepgram** (`LiveDictationConfig.kt`), slot 2 **Sarvam AI** (`SarvamConfig.kt`), slots 3 and 4 empty
  ("Not set"). Each slot has its own on/off switch, its own key (Android Keystore, `SecretStore.kt`; never shown or
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
  it needs a yes (Settings → Speech slot 2 → Where your voice goes), like Deepgram's consent.
- **Pause before a sentence ends** (Settings → Live dictation, default 1300 ms): Deepgram `endpointing`, Sarvam
  `silence_duration_ms`. Deepgram's own default (~10 ms) ended a sentence with "?" or "." at every short breath.

## Translation (offline)
- **Opus-MT** (Helsinki-NLP MarianMT, int8 ONNX exports from the `Xenova/opus-mt-*` repos, pinned revisions + SHA-256
  in `OpusMtCatalog.kt`, regenerate with `tools/opus-catalog/gen.py`). The pair comes from the two languages: the
  direct model, else source→English→target, else none (`OpusMt.route`). About 110–140 MB per model, fetched in the
  background by a JobScheduler job (`OpusPacks.kt`; Range resume, SHA-256 check, no notification) into app storage
  (`files/opus/<pair>/`). Any network by default, mobile data included (`NETWORK_TYPE_ANY`); with "Download on Wi-Fi
  only" on, unmetered only. Settings → Translation → Offline translation models shows status, Delete and Download
  again; changing a language re-derives the models.
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
  (ML Kit translates) until an app update or Settings → Offline translation models → Try again.
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
  (`make_ref.py`, onnxruntime 1.28.0, pairs en-de de-en en-hi hi-en ja-en en-es en-zh); `--hf` checks every catalog
  file at its pinned revision.
- `./gradlew testDebugUnitTest` – Robolectric: the first-run flow, Settings region/consent, model bookkeeping and the
  Wi-Fi-only default, the Opus-MT crash guard, speech slots, and multi-select delete.
- `tools/notes-db-test/run.sh` – runs `NoteSchema.kt`'s SQL on real SQLite (upgrade, FTS, versions, soft delete).
- `tools/ws-harness/run.sh [--wss]` – live-dictation WebSocket client, parser, slot order and fallback against fake
  Deepgram and Sarvam AI servers; `--wss` also checks TLS to the real Deepgram and Sarvam hosts (no key: must refuse).
