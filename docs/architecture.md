# Daily Whisper — Architecture Overview + Data Model

## High-level architecture
- Native Android app (Kotlin, Android framework).
- Local-only storage: app-private SQLite (`notes.db`), excluded from cloud backup and device transfer.
- On-device translation:
  - Google ML Kit Translation (dynamic language packs ~30 MB each).
  - Opus-MT (Helsinki-NLP MarianMT int8 ONNX) via ONNX Runtime. Models downloaded on demand into app storage.
- Live dictation: WebSocket client to Deepgram (BYOK). Regional endpoints + explicit residency consent. `mip_opt_out=true`.
- Background work: JobScheduler for model downloads (resumable, Wi-Fi preferred).
- UI: Main list + composer, NoteActivity (reading view + edit), SetupActivity (first-run), SettingsActivity, CollectionActivity (deleted/archived).

## Key components
- `NoteSchema.kt` / `NoteStore.kt` — schema, migrations, queries, FTS.
- `Translator.kt` / `TranslateConfig.kt` / `OpusMt.kt` / `OpusMtCatalog.kt` / `OpusPacks.kt` — translation routing and model management.
- `SetupActivity.kt` / `Residency.kt` — first-run and data-residency logic.
- `LiveDictation.kt` / `WebSocketClient.kt` — speech.
- `Settings.kt` / `SettingsActivity.kt` — preferences.

## Data model (SQLite, schema v2)

### notes
| Column       | Type    | Notes                                      |
|--------------|---------|--------------------------------------------|
| id           | INTEGER | PK AUTOINCREMENT                           |
| text         | TEXT    | Current note content                       |
| created      | INTEGER | Timestamp                                  |
| updated      | INTEGER | Timestamp                                  |
| status       | TEXT    | 'active' \| 'archived' \| 'deleted' (default active) |
| deleted_at   | INTEGER | Set on soft-delete                         |
| highlight    | TEXT    | Palette key or NULL                        |

Indexes: `notes_created`, `notes_status`.

### note_versions
| Column   | Type    | Notes                          |
|----------|---------|--------------------------------|
| id       | INTEGER | PK                             |
| note_id  | INTEGER | FK to notes                    |
| text     | TEXT    | Version content                |
| created  | INTEGER | Timestamp of this version      |

Index: `note_versions_note`.

Every create/edit appends a version. History is never rewritten. Upgrade from v1 seeds one version per existing note.

### notes_fts
FTS4 virtual table (external content on notes.text) with triggers for insert/update/delete sync. Prefix search via `ftsQuery`.

### Preferences / config (not in notes.db)
- Source language, target language.
- Region (explicit user choice).
- Live-dictation residency consent.
- First-run completed flag.
- Highlight default, theme, Wi-Fi-only for downloads, search-include-archived, etc.

## Translation routing
1. Derive Opus-MT pair(s) from source + target (direct, or via English).
2. If models ready → Opus-MT.
3. Else → ML Kit (or until models finish downloading).
4. Chunk by line / sentence to preserve layout.

## Permissions
RECORD_AUDIO, INTERNET, ACCESS_NETWORK_STATE. Notes stay local.
