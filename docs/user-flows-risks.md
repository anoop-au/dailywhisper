# Daily Whisper — User Flows + Risks / Assumptions

## Key user flows

### First-run (once)
1. Short welcome screen (icon + one line, ~1.75–2.5 s or tap).
2. Source language (“Which language do you usually write or speak your notes in?” + note that keyboard language does not matter).
3. Target language (“Which language would you like your notes translated into?”).
4. Explicit region selection.
5. Data-residency disclosure for live dictation:
   - In-region endpoint available → short statement that audio stays in-region.
   - Otherwise → name destination region + yes/no consent. Without consent the mic stays hidden.
6. “You can change these later in Settings.” → notes list.

### Create / edit note
- Composer at bottom (or open note → pencil / long-press text to edit).
- Save creates or updates note and appends a version.
- Live dictation (mic) inserts text when consented.

### Translate
- Open note → reading view.
- Tap text → translate to target language (Opus-MT if ready, else ML Kit).
- Panel shows result with Copy / Keep both (new note) / Replace (new version + Undo; original remains in History).

### Soft-delete + recover
- Long-press note on list → Delete (soft).
- Undo bar on feed, or ⋮ → Deleted → Restore.
- Hard delete only from Deleted view with confirmation.

### Multi-select delete
- Long-press → Select.
- Checkboxes appear; top bar shows count + Select all + Cancel + Delete.
- Delete soft-deletes all selected; exit selection mode.

### Share
- From note header or long-press menu on list.

### Search / archive / highlight
- Search bar: FTS4 prefix search (active notes; optional archived).
- Archive / unarchive from note.
- Highlight colour from note; default in Settings.

## Risks
- **Model size**: ML Kit ~16 MB in APK + per-language packs; Opus-MT ~110–140 MB per model. Users on limited storage or data may delay or skip downloads.
- **Offline quality**: ML Kit is casual-grade and pivots non-English pairs through English. Opus-MT is better on covered pairs but not universal.
- **Opus-MT integration**: ONNX Runtime + tokenizer must stay stable; catalog pins revisions and SHA-256.
- **Data residency**: Live dictation audio leaves the device only after explicit consent and only to the disclosed region. Misconfiguration or provider change could violate user expectation.
- **Soft-delete recovery**: No automatic expiry window currently; deleted notes stay until hard-deleted. Users may accumulate them.
- **First-run friction**: Region + consent steps add length. Too long risks drop-off; too short risks unclear consent.
- **64-bit only**: App targets arm64; older 32-bit devices unsupported.
- **BYOK speech**: Deepgram key required for live dictation. Key storage and rotation are user responsibility.

## Assumptions
- Users accept a one-time first-run that asks language and region.
- Soft-delete + History is sufficient recovery for most accidental deletes.
- Majority of translation needs are covered by ML Kit or a small number of Opus-MT pairs.
- Live dictation is optional; the app is fully usable without it.
- No cloud sync is required for the MVP.
- Commercial use of Opus-MT (CC-BY) is acceptable with attribution if the app is later monetised.

## Open questions / later
- Exact recovery window for deleted notes (if any).
- Gesture defaults beyond current long-press menu.
- Additional speech providers and their residency.
- Monetisation tiers and which features sit behind them.
