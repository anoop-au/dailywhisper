# Daily Whisper

A calm, on-device Android notes app. The composer at the bottom is focused on launch, so Vion (or any
dictation app / keyboard) lands text straight into it; tap the round button to save it as a note.

- Plain Kotlin + Android framework for the app's own code. One library: Google ML Kit on-device translation (it brings
  AndroidX/Play-services pieces in transitively, and a 16 MB native library, arm64 only).
- Permissions: RECORD_AUDIO + INTERNET (live dictation to Deepgram; ML Kit's one-time language packs).
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

## Translation (offline, ML Kit)
- First run (and once after updating): Setup asks which language you write in and which to translate to, then
  fetches that pair's ML Kit packs (~30 MB per language, once; Wi-Fi only by default). Settings → Translation changes
  the languages and can re-run setup.
- Open a note: it opens to read. **Tap the text to translate**; the result shows in a panel below with Copy,
  Keep both (saves the translation as a new note) and Replace (new version, Undo right there, original in History).
  ✎ in the header, or a long-press on the text, edits the note (✓ to finish). List: tap opens, long-press = Share/Delete.
- `NoteTranslator` (Translator.kt) runs ML Kit a line at a time so the note's layout comes back exactly.

## Tests (run on the box)
- `tools/translate-harness/run.sh` – the language list vs ML Kit's TranslateLanguage (from the AAR) and the chunking.
- `tools/notes-db-test/run.sh` – runs `NoteSchema.kt`'s SQL on real SQLite (upgrade, FTS, versions, soft delete).
- `tools/ws-harness/run.sh [--wss]` – live-dictation WebSocket client + parser against a fake Deepgram server.
