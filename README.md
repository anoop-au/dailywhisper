# Daily Whisper

A calm, on-device Android notes app. The composer at the bottom is focused on launch, so Vion (or any
dictation app / keyboard) lands text straight into it; tap the round button to save it as a note.

- Plain Kotlin + Android framework for the app's own code. One library: Google ML Kit on-device translation (it brings
  AndroidX/Play-services pieces in transitively). Native: llama.cpp (vendored source) for the Hy-MT translator.
- Permissions: RECORD_AUDIO + INTERNET (live dictation to Deepgram; one-time translation downloads),
  ACCESS_NETWORK_STATE + FOREGROUND_SERVICE(_DATA_SYNC) + POST_NOTIFICATIONS (background model download).
  Notes never leave the phone; translation runs on it.
- Notes in app-private SQLite (`notes.db`), excluded from cloud backup and device transfer.
- Build: `source ~/android-env.sh && ./gradlew assembleRelease` -> `out/DailyWhisper-v<version>.apk`
- Release signing reads `~/dailywhisper-keys/keystore.properties` (outside the repo; override with
  `DAILYWHISPER_KEYSTORE_PROPS`). Never Vion's key.

## Notes features
- **History:** every save is a version (`note_versions`); the feed shows the latest. Note → History lists versions;
  Restore adds a new version, history is never rewritten.
- **Deleted:** delete is a soft delete (Undo bar on the feed); ⋮ → Deleted lists them with Restore. "Delete forever"
  (with confirm) there is the only hard delete.
- **Archive:** Note → Archive hides it from the feed; ⋮ → Archive lists them, open one to move it back.
- **Highlight:** Note → Highlight tints the card (light/dark aware); default colour in Settings → Notes.
- **Search:** FTS4 full-text, prefix per word, across active notes (and archived if the setting is on).
- Schema and all SQL: `NoteSchema.kt`. DB v1 → v2 upgrade keeps every note and seeds one version each.

## Translation (offline)
- First run (and once after updating): Setup asks which language you write in, which to translate to, and
  Built-in (ML Kit, ~30 MB language pack per language on first use) vs the better Hy-MT model (one-time 440 MB).
  Settings → Translation changes any of it, shows download progress, and can re-run setup.
- Open a note: it opens to read. **Tap the text to translate**; the result shows in a panel below with Copy,
  Keep both (saves the translation as a new note) and Replace (new version, Undo right there, original in History).
  ✎ in the header, or a long-press on the text, edits the note (✓ to finish). List: tap opens, long-press = Share/Delete.
- `Translator` (Translator.kt) has two backends: `MlKitTranslator` and `HyMtTranslator` (llama.cpp via JNI,
  `app/src/main/cpp`). If Hy-MT isn't downloaded yet or can't do the pair, built-in is used and the panel says so.
- Hy-MT: `tencent/Hy-MT1.5-1.8B-1.25bit-GGUF` (pinned revision, SHA-256 checked), downloaded by a foreground service
  with HTTP Range resume to `filesDir/models/`; Wi-Fi only by default. Needs llama.cpp PR #22836 (STQ1_0 kernel),
  vendored in `app/src/main/cpp/llama.cpp` (see VENDORED.md); the GGUF's STQ type ids are rewritten once after download.
  Model licence: Tencent HY Community License (not valid in the EU, UK or South Korea).

## Tests (run on the box)
- `tools/translate-harness/run.sh [--hf] [model.gguf]` – languages vs the ML Kit AAR and the Hy-MT card, prompt format,
  chunking, GGUF patch, and the resumable downloader against a local Range server (and, with --hf, Hugging Face).
- `tools/hymt-native-test/run.sh host|arm64 <patched.gguf>` – the app's C++ engine + vendored llama.cpp translating a
  sentence with the real model; arm64 runs the NDK build under qemu-aarch64 (NEON STQ1_0 path).
- `tools/notes-db-test/run.sh` – runs `NoteSchema.kt`'s SQL on real SQLite (upgrade, FTS, versions, soft delete).
- `tools/ws-harness/run.sh [--wss]` – live-dictation WebSocket client + parser against a fake Deepgram server.
