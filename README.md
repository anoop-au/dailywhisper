# Daily Whisper

A calm, on-device Android notes app. The composer at the bottom is focused on launch, so Vion (or any
dictation app / keyboard) lands text straight into it; tap the round button to save it as a note.

- Plain Kotlin + Android framework, no third-party libraries, no permissions (no network).
- Notes in app-private SQLite (`notes.db`), excluded from cloud backup and device transfer.
- Build: `source ~/android-env.sh && ./gradlew assembleRelease` -> `out/DailyWhisper-v<version>.apk`
- Release signing reads `~/dailywhisper-keys/keystore.properties` (outside the repo; override with
  `DAILYWHISPER_KEYSTORE_PROPS`). Never Vion's key.
