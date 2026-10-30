# Daily Whisper — Architecture Overview + Data Model

## High-level architecture
- Native Android app (Kotlin, Android framework). minSdk 30, targetSdk 36, arm64-v8a only.
- Local-only storage: app-private SQLite (`notes.db`), excluded from cloud backup and device transfer.
- On-device translation:
  - Google ML Kit Translation `17.0.3` (language packs ~30 MB each, fetched through Android's Download Manager into `no_backup/com.google.mlkit.translate.models`).
  - Opus-MT (Helsinki-NLP MarianMT int8 ONNX, `Xenova/opus-mt-*` exports pinned by revision + SHA-256; Malayalam `en-ml`/`ml-en` from our own exports in the public `akm9191/opus-mt-en-ml-int8-onnx` / `akm9191/opus-mt-ml-en-int8-onnx` repos, same file layout, per-model `repo` field in `OpusMtCatalog`) via ONNX Runtime `onnxruntime-android:1.28.0`. Models downloaded on demand into `files/opus/<pair>/`. Beam search (default 4 beams) with KV cache in `OpusMtModel`.
  - Crash guard (`OpusGuard`): a marker file in `no_backup/opus-guard/` around each Opus-MT run; if a run dies in native code, Opus-MT stays off (ML Kit translates) until an app update or "Try again". The SME CPU block is off now that the engine is 1.28.0 (`ORT_SME_SAFE = true`).
- Live dictation: 4 ordered BYOK speech slots over one hand-written WebSocket client. Slot 1 Deepgram (regional endpoints, `mip_opt_out=true`), slot 2 Sarvam AI (India only, realtime WebSocket), slot 3 "On this phone" (on-device, see below), slot 4 empty. First usable slot wins; on connect/auth failure or an unsupported language the next is used. The language setting (`dg_language`) decides which slots can serve: `multi` = "Multilingual (Deepgram)" (slot 1, Sarvam `auto` as fallback); `auto-sarvam` = "Auto (Sarvam AI)" (slot 2, `language_code=auto`; when slot 2 is off, has no key or India isn't allowed, `SpeechSlots.plan` hands the session to slot 1 Deepgram with `language=multi` and the mic shows "Sarvam AI is off, using Deepgram" once per app run; only when neither is usable does the mic say how to set one up; the code-mixed switch never reaches Deepgram, Sarvam's adaptive detection; finals carry `language`, e.g. `ml-IN`, which `TranscriptParser` reads into `Update.language` and the mic announces once per change; with Live dictation's "Keep English words in English" on, `SarvamConfig.mode` sends `mode=codemix`, which Sarvam applies to finals only, partials stay plain transcription); "(Sarvam AI)" languages slot 2 only; the rest Deepgram first. Each cloud destination needs residency consent when outside the user's region.
- On-device speech (slot 3, `SpeechProvider.ON_DEVICE`): Sherpa-ONNX (Apache-2.0, k2-fsa) JNI `jniLibs/arm64-v8a/libsherpa-onnx-jni.so` (3.3 MB) built from source by `tools/sherpa-android/build.sh` at a pinned commit, linked against the app's own `libonnxruntime.so` 1.28.0 (one runtime; the script checks the `VERS_1.28.0` symbols), recognition + VAD only. Kotlin API vendored in `com.k2fsa.sherpa.onnx` (kept by ProGuard). `SpeechPackCatalog` (pure Kotlin) lists the packs with pinned Hugging Face revisions, sizes and SHA-256: `en` (streaming zipformer transducer, live word by word with endpoint rules), `ml` (IndicConformer CTC, Malayalam-only head) and `global` (Omnilingual ASR CTC) — the sentence packs run Silero VAD + an offline recognizer and show "…" while you speak. `SpeechPacks` downloads into `no_backup/speech/<id>/` (JobScheduler job 4108, `SpeechPackDownloadJob`, same network rule as Opus-MT), `OnDeviceSpeech` holds one cached engine (released on low memory) behind its own crash guard (`OpusGuard` class, `no_backup/speech-guard/`): if a run dies in native code, On this phone stays off until an app update or "Tap to try again". `SpeechSlots.plan(…, online)`: online → cloud slots then slot 3; offline (`Context.isOnline()`: a validated network) → slot 3 only. `LiveDictation.Session` moves to the device recognizer on a connect failure or a dropped socket when `offline_switch` is on, keeping the words already heard.
  - Add-on packs (`Pack.addonFor = "en"`, never chosen alone, `SpeechPackCatalog.addons`): `en-recheck` (Parakeet TDT 0.6B v2 int8, `Kind.NEMO_TRANSDUCER`) makes the English engine two-pass: the streaming zipformer shows live words (endpoint rules off), Silero VAD cuts sentences, and an `OfflineRecognizer` rewrites each one (blank result keeps the live text); `en-recheck-small` (Parakeet TDT 110M, same wiring; `SpeechPackCatalog.RECHECKS` is best first, so the 0.6B wins when both are installed; the VAD uses `od_recheck_pause_ms`, default 2000, not the live sentence pause); `punct-en` (`Kind.PUNCTUATION`, `OnlinePunctuation`, vendored; the JNI is now built with online punctuation) punctuates the live text when the recheck isn't used. `AutoGain` (`OnDeviceText.kt`, pure Kotlin) evens out quiet input before every on-device engine (`od_boost`, max 10×). English uses `modified_beam_search` when `od_beam` > 1. `SpeechSlots.plan(…, deviceFirst)` (`od_first`) puts slot 3 first when it has the language; `languageChoices` / `languageHints` / `engineLine` / `engineNote` drive the Language list, its hint lines and the "Using …" notice.
- Background work: JobScheduler for Opus-MT downloads (job 4107, `OpusDownloadJob`): resumable, storage-not-low, `NETWORK_TYPE_ANY` by default (mobile data included), `NETWORK_TYPE_UNMETERED` only when "Download on Wi-Fi only" is on.
- UI: Main list + composer, NoteActivity (reading view + edit), SetupActivity (first-run), SettingsActivity, CollectionActivity (deleted/archived).

## Key components
- `NoteSchema.kt` / `NoteStore.kt` — schema, migrations, queries, FTS.
- `Translator.kt` / `TranslateConfig.kt` / `OpusMt.kt` / `OpusMtCatalog.kt` / `OpusPacks.kt` — translation routing and model management.
- `SetupActivity.kt` / `Residency.kt` — first-run and data-residency logic.
- `LiveDictation.kt` (mic + session) / `SpeechSlots.kt` (slot order, language list) / `SttConnect.kt` (per-provider protocol, connect + fallback) / `LiveDictationConfig.kt` (Deepgram) / `SarvamConfig.kt` (Sarvam AI) / `WebSocketClient.kt` — speech.
- `Settings.kt` / `SettingsActivity.kt` — preferences. Main screen: Dictation, Speech providers · tried in order (one row per provider, opening its own page via `EXTRA_PAGE`), Notes, Composer, Translation, Look & layout, Advanced (`Prefs.sections`); provider pages from `Prefs.providerPages` with the residency line as a tappable footer; empty slots 3–4 behind "Add provider" (`Prefs.emptySlots`). UI only: keys, defaults and logic unchanged.

## Data model (SQLite, schema v2)

### notes
| Column       | Type    | Notes                                      |
|--------------|---------|--------------------------------------------|
| id           | INTEGER | PK AUTOINCREMENT                           |
| text         | TEXT    | Current note content (NOT NULL)            |
| created      | INTEGER | Epoch ms (NOT NULL)                        |
| updated      | INTEGER | Epoch ms of the last edit (NOT NULL)       |
| status       | TEXT    | 'active' \| 'archived' \| 'deleted' (NOT NULL, default 'active') |
| deleted_at   | INTEGER | Epoch ms, set on soft-delete, NULL otherwise |
| highlight    | TEXT    | Palette key (amber, rose, sage, sky, lavender) or NULL |

Indexes: `notes_created (created DESC)`, `notes_status (status, created DESC)`.

### note_versions
| Column   | Type    | Notes                          |
|----------|---------|--------------------------------|
| id       | INTEGER | PK AUTOINCREMENT               |
| note_id  | INTEGER | The note's id (no declared foreign key; removed with the note by "Delete forever") |
| text     | TEXT    | Version content (NOT NULL)     |
| created  | INTEGER | Epoch ms of this version (NOT NULL) |

Index: `note_versions_note (note_id, created DESC)`.

Every create/edit appends a version. History is never rewritten (Restore adds a new version). Upgrade from v1 seeds one version per existing note, stamped with its `updated` time. Bulk delete/restore (multi-select) is `NoteStore.deleteAll` / `restoreAll`, one transaction.

### notes_fts
FTS4 virtual table (external content on notes.text, `unicode61` tokenizer, `simple` as fallback) with triggers for insert/update/delete sync. Prefix search via `ftsQuery` (every word, each a prefix); a `LIKE` substring search is the fallback if FTS fails. All SQL lives in `NoteSchema.kt`.

### Preferences / config (not in notes.db)
Two SharedPreferences files. A setting is only written when the user changes it, so a new default reaches existing installs that never touched it.
- `settings` (tunables, every one with a default; "Reset to defaults" clears this file):
  - Translation: `tr_source` (default en), `tr_target` (default es), `opus_beams` (Opus-MT search width, default 4, 1–6), `tr_wifi_only` ("Download on Wi-Fi only", default **off**).
  - Live dictation: `show_mic`, `dg_language`, `sarvam_codemix` ("Keep English words in English" on the Sarvam AI page, default off; used only when the language is Auto (Sarvam AI): on sends Sarvam `mode=codemix`, off sends slot 2's `sarvam_mode` as before), `pause_ms` (default 1300, 300–5000), `keep_screen_on`; slot 1 `slot1_on`, `dg_model` (nova-3), `dg_smart_format`, `dg_region`; slot 2 `slot2_on`, `sarvam_model`, `sarvam_mode` ("Output", default `transcribe`; used for every Sarvam session except Auto (Sarvam AI) with `sarvam_codemix` on); slot 3 `slot3_on` (default on), `offline_switch` (default on), `od_threads` (processor cores, default 2, 1–4), `od_max_sentence` (longest sentence for sentence packs, default 20 s, 5–30).
  - Composer, notes and look: `auto_focus`, `show_keyboard`, `append_dictation`, `enter_saves`, `auto_save`, `auto_save_seconds`, `composer_text_size`, `composer_max_lines`, `note_text_size`, `preview_lines`, `confirm_delete` (default off), `undo_seconds`, `highlight_default` (amber), `search_archived` (on), `theme`, `max_width`, `welcome_ms` (default 2500, 500–10000).
- `state` (facts, kept by "Reset to defaults"): `first_run_done`, `region`, `dg_consent` (the Deepgram region said yes to), `sarvam_consent`, `opus_off` (user deleted the models), `draft` (composer text), `translations_done` (hides the tap-to-translate hint), `settings_version` (one-time clean-ups), `speech_packs_wanted` (speech packs the user asked for; removed on Delete).
- Speech API keys in the encrypted `secrets` file (AES key in Android Keystore): `stt_api_key` (Deepgram), `stt_api_key_2` (Sarvam AI).

## Translation routing
1. Derive Opus-MT pair(s) from source + target (direct, or via English) (`OpusMt.route`).
2. If the models are ready and the crash guard allows → Opus-MT, a line at a time and a sentence at a time, beam search (`opus_beams`).
3. Malayalam (`TranslateConfig.Lang.mlKit = false`; ML Kit has none): no fallback. `OpusPacks.unavailable` gives the plain reason shown in the panel and Settings: "Malayalam works once it's downloaded (… MB)." (with progress / waiting for Wi-Fi), "Malayalam translation isn't available on this phone" (crash guard), or "Malayalam can't be translated to or from <language> yet." (no Opus-MT route, e.g. Korean). If Opus-MT throws on a Malayalam note: "Couldn't translate this note".
4. Else, or if Opus-MT throws → ML Kit: `downloadModelIfNeeded` (Wi-Fi required only when `tr_wifi_only` is on; 3-minute limit), then translate line by line.
5. Joining every piece's text + separator gives back the note's layout exactly; for Chinese/Japanese, spaces between sentences are dropped and a missing 。！？ is added.

## Permissions
RECORD_AUDIO, INTERNET, ACCESS_NETWORK_STATE. Notes stay local. The ONNX Runtime 1.28.0 AAR adds no permissions or components (1.29+ would add a telemetry provider and network permissions). Sherpa-ONNX adds no permissions; its packs download from Hugging Face over HTTPS like the Opus-MT models.
