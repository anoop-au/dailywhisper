# Daily Whisper — User Flows + Risks / Assumptions

## Key user flows

### First-run (once)
1. Short welcome screen (icon + one line, 2.5 s by default, adjustable in Settings, or tap).
2. Source language (“Which language do you usually write or speak your notes in?” + note that keyboard language does not matter).
3. Target language (“Which language would you like your notes translated into?”).
4. Explicit region selection. After it, the chosen pair's Opus-MT model starts downloading in the background (any network unless "Download on Wi-Fi only" is on), and ML Kit's packs are prefetched; ML Kit covers until Opus-MT is ready.
5. Data-residency disclosure for live dictation:
   - In-region endpoint available → short statement that audio stays in-region.
   - Otherwise → name destination region + yes/no consent. Without consent the mic stays hidden.
6. “You can change these later in Settings.” → notes list.

### Create / edit note
- Composer at bottom (or open note → pencil / long-press text to edit).
- Save creates or updates note and appends a version.
- Live dictation (mic) inserts text when consented. It uses speech slot 1 (Deepgram), else slot 2 (Sarvam AI) when slot 1 has no key, can't connect, or doesn't know the language (e.g. Malayalam). Picking "Auto (Sarvam AI)" sends every session to slot 2 with Sarvam's automatic language detection (Malayalam, English and other Indian languages without switching settings); a short "Sarvam AI hears …" message names the detected language when it changes. If slot 2 has no key, is off, or India isn't allowed, the mic says which.

### Translate
- Open note → reading view.
- Tap text → translate to target language (Opus-MT if ready, else ML Kit). The panel title names the engine.
- First ML Kit use of a language fetches its ~30 MB pack ("Getting ready…"), on mobile data too unless "Download on Wi-Fi only" is on (then it says it's waiting for Wi-Fi).
- Panel shows result with Copy / Keep both (new note) / Replace (new version + Undo; original remains in History).

### Soft-delete + recover
- Long-press note on list → Delete (soft; a confirm only if "Ask before deleting" is on).
- Undo bar on feed (an archived note goes back to the archive), or ⋮ → Deleted → tap → Restore (back to the notes list).
- Hard delete ("Delete forever") only from Deleted view with confirmation; it also removes the note's history.

### Multi-select delete
- Long-press → Select (that note starts ticked).
- Ticks appear; top bar shows ✕ (cancel) + "N selected" + Select all (notes on screen, follows search) + Delete (greyed until one is picked). Tapping a note toggles it instead of opening it.
- Delete soft-deletes all selected at once, shows one Undo bar ("N notes deleted") and exits selection mode. ✕ or Back exits without deleting.

### Share
- From note header (saves first) or long-press menu on list: Android's share sheet with the note's plain text.

### Search / archive / highlight
- Search bar: FTS4 prefix search (active notes; optional archived).
- Archive / unarchive from note.
- Highlight colour from note; default in Settings.

## Risks
- **Model size and data use**: APK about 47 MB (ML Kit 16 MB + ONNX Runtime 29 MB native); ML Kit packs ~30 MB per language; Opus-MT ~110–140 MB per model (twice that through English). Downloads use mobile data by default ("Download on Wi-Fi only" is off), which suits users mostly on mobile data but costs data on capped plans.
- **Memory**: a loaded Opus-MT model uses roughly 570 MB while translating.
- **Offline quality**: ML Kit is casual-grade and pivots non-English pairs through English. Opus-MT is better on covered pairs but not universal. Greedy decoding made en-zh loop on short sentences; 4-beam search fixes the cases tested but costs about 2× the time per sentence (0.3–0.7 s on a Fold 5). en-zh still writes some clause breaks as spaces instead of commas, and wording can vary by a word between runs (multi-threaded maths).
- **Opus-MT integration**: ONNX Runtime + tokenizer must stay stable; catalog pins revisions and SHA-256. ONNX Runtime 1.28.0 fixes the SIGILL on Arm SME processors (Snapdragon 8 Elite Gen 5 / Galaxy Z Fold 8) but hasn't been run on one yet; the crash guard falls back to ML Kit if it ever dies there. Updating to 1.29+ would bring telemetry.
- **Data residency**: Live dictation audio leaves the device only after explicit consent and only to the disclosed region. Misconfiguration or provider change could violate user expectation.
- **Soft-delete recovery**: No automatic expiry window currently; deleted notes stay until hard-deleted. Users may accumulate them.
- **First-run friction**: Region + consent steps add length. Too long risks drop-off; too short risks unclear consent.
- **64-bit only**: App targets arm64; older 32-bit devices unsupported.
- **BYOK speech**: a Deepgram or Sarvam AI key is required for live dictation. Key storage and rotation are user responsibility.
- **Sarvam AI**: India-hosted only, so users outside India must consent to sending audio to India. Its retention is set per account on the Sarvam dashboard (the app can't send an opt-out flag like Deepgram's `mip_opt_out`). The Sarvam path was tested against a fake server built from its docs, not with a real key.
- **Sarvam auto-detect**: Sarvam documents `language_code=auto` as "adaptive automatic language detection" and returns the detected language per transcript, but doesn't document how well it handles switching languages inside one sentence (partials are said to stay in a "sticky" language). Mixed Malayalam/English is untested. The "Code-mixed" Sarvam mode (English words kept in English) can help.

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
- Providers for speech slots 3 and 4, and their residency.
- Monetisation tiers and which features sit behind them.
