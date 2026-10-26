# Daily Whisper

A calm, on-device Android notes app. The composer at the bottom is focused on launch, so Vion (or any
dictation app / keyboard) lands text straight into it; tap the round button to save it as a note.

- Plain Kotlin + Android framework for the app's own code. Two libraries, both for on-device translation: Google ML Kit
  (brings AndroidX/Play-services pieces in transitively, 16 MB native library) and ONNX Runtime 1.23.2 for Opus-MT
  (19 MB native library). arm64 only.
- Permissions: RECORD_AUDIO + INTERNET (live dictation to Deepgram; one-time model downloads) + ACCESS_NETWORK_STATE
  (the model download waits for Wi-Fi). Notes never leave the phone; translation runs on it.
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

## First run (once after install, and once after updating to 0.1.7)
Welcome (icon + one line, ~1.75 s or a tap) → source language → target language → region (explicit pick) →
where live-dictation audio goes → "You can change these later in Settings." → notes list. `SetupActivity.kt`.
- Region (`Residency.kt`): Deepgram has regional endpoints for Australia, the EU and India (+ its US-based global one).
  In one of those, dictation uses it and setup just says the audio stays in-region. Elsewhere it names where audio
  would go (nearest endpoint) and asks yes/no; without a yes the mic stays hidden until allowed in Settings → Live
  dictation → Where your voice goes. Every stream sends `mip_opt_out=true` (Deepgram keeps nothing).
- Region and consent live in the "state" prefs (Reset to defaults keeps them); languages in Settings → Translation.

## Translation (offline)
- **Opus-MT** (Helsinki-NLP MarianMT, int8 ONNX exports from the `Xenova/opus-mt-*` repos, pinned revisions + SHA-256
  in `OpusMtCatalog.kt`, regenerate with `tools/opus-catalog/gen.py`). The pair comes from the two languages: the
  direct model, else source→English→target, else none (`OpusMt.route`). About 110–140 MB per model, fetched in the
  background by a JobScheduler job (`OpusPacks.kt`; Range resume, SHA-256 check, Wi-Fi only by default, no
  notification) into app storage. Settings → Translation → Offline translation models shows status, Delete and
  Download again; changing a language re-derives the models.
- **ML Kit** is the fallback: used until the Opus-MT models are ready, when no model covers the pair, or if Opus-MT
  fails. Its packs (~30 MB per language) are fetched once.
- Open a note: it opens to read. **Tap the text to translate**; the result shows in a panel below with Copy,
  Keep both (saves the translation as a new note) and Replace (new version, Undo right there, original in History).
  ✎ in the header, or a long-press on the text, edits the note (✓ to finish). List: tap opens, long-press = Share/Delete.
- `NoteTranslator` (Translator.kt) translates a line at a time so the note's layout comes back exactly; the panel
  title says which engine did it. `OpusMt.kt`: JSON + SentencePiece-unigram tokenizer + greedy decoding with KV cache.

## Tests (run on the box)
- `tools/translate-harness/run.sh [--opus <models>] [--hf]` – language list vs ML Kit's AAR, chunking, Opus-MT routes,
  JSON, tokenizer, downloads; `--opus` runs real Opus-MT translations through ONNX Runtime and must match the Python
  reference in `opus-ref.json` (`make_ref.py`); `--hf` checks every catalog file at its pinned revision.
- `./gradlew testDebugUnitTest` – the first-run flow, Settings region/consent and model bookkeeping on Robolectric.
- `tools/notes-db-test/run.sh` – runs `NoteSchema.kt`'s SQL on real SQLite (upgrade, FTS, versions, soft delete).
- `tools/ws-harness/run.sh [--wss]` – live-dictation WebSocket client + parser against a fake Deepgram server.
