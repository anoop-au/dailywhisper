# Daily Whisper — User Flows + Risks / Assumptions

## Key user flows

### First-run (once)
1. Short welcome screen (icon + one line, 2.5 s by default, adjustable in Settings, or tap).
2. Source language (“Which language do you usually write or speak your notes in?” + note that keyboard language does not matter). A short list: the phone's language first, then the current choice and English, a search box, and “All languages” for the full list. The same for step 3.
3. Target language (“Which language would you like your notes translated into?”).
4. Explicit region selection. After it, the chosen pair's Opus-MT model starts downloading in the background (any network unless "Download on Wi-Fi only" is on), and ML Kit's packs are prefetched; ML Kit covers until Opus-MT is ready.
5. Data-residency disclosure for live dictation:
   - In-region endpoint available → “Your voice stays close.” / “Live dictation keeps your voice in your region. Nothing is kept after it’s typed.”
   - Otherwise → name destination region + yes/no consent. Without consent the mic stays hidden.
   - Both add “Full details are in the Privacy Policy.” (plain text; the app has no privacy policy screen or URL yet). Setup shows no provider, endpoint, engine or model names; Settings still does.
   - The quiet line at the bottom reads in plain words, e.g. “Getting English → Chinese (Simplified) ready… 40%” then “English → Chinese (Simplified) is ready on this phone.” (no engine names or sizes).
6. “You can change these later in Settings.” → notes list. Above it, an optional card: “Dictate without internet (optional)” / “Get <language> on this phone so the mic works offline too. N MB, downloads in the background.” with a Download button (skippable; then “Getting it ready in the background. You can see it in Settings.”).

### Create / edit note
- Composer at bottom (or open note → pencil / long-press text to edit).
- Save creates or updates note and appends a version.
- Live dictation (mic) inserts text when consented. It uses speech slot 1 (Deepgram), else slot 2 (Sarvam AI) when slot 1 has no key, can't connect, or doesn't know the language (e.g. Malayalam). Picking "Auto (Sarvam AI)" sends every session to slot 2 with Sarvam's automatic language detection (Malayalam, English and other Indian languages without switching settings); a short "Sarvam AI hears …" message names the detected language when it changes. If slot 2 has no key, is off, or India isn't allowed, the mic uses Deepgram (slot 1) in its Multilingual mode instead and says "Sarvam AI is off, using Deepgram" once per app run (Deepgram has no Malayalam, so Malayalam speech won't come out right then). Only if Deepgram can't be used either does the mic say what to set up. Slot 3 “On this phone” is tried last when online. With no internet at the start the mic uses it straight away (“No internet, using offline mode”); if the internet drops while dictating it carries on there (“Connection lost, using offline mode”), keeping what was already typed. English types word by word; Malayalam and “All languages (1,600+)” type a sentence at a time after each pause. With no pack for the language: “No internet. Download <language> in Settings → On this phone to dictate offline”.

### Translate
- Open note → reading view.
- Tap text → translate to target language (Opus-MT if ready, else ML Kit). The panel title names the engine.
- Malayalam (source or target): Opus-MT only. Before its models are on the phone the panel says "Malayalam works once it's downloaded (… MB)."; if Opus-MT can't run on the phone, "Malayalam translation isn't available on this phone."; for a pair with no route through English (e.g. Korean), "Malayalam can't be translated to or from Korean yet."
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
- **Model size and data use**: APK about 47 MB (ML Kit 16 MB + ONNX Runtime 29 MB native); ML Kit packs ~30 MB per language; Opus-MT ~85–150 MB per model (twice that through English). Downloads use mobile data by default ("Download on Wi-Fi only" is off), which suits users mostly on mobile data but costs data on capped plans.
- **Malayalam quality**: our int8 exports of Helsinki-NLP's 2020 models (Tatoeba BLEU 42.7 ml→en, 19.1 en→ml). Malayalam → English is good on plain sentences; English → Malayalam sometimes drops words ("I came home late." → "ഞാൻ വൈകി." with 4 beams). Code-mixed Malayalam + English input (what Sarvam AI's code-mixed dictation produces) translates badly, e.g. "ഞാൻ office-ൽ നിന്ന് late ആയി വന്നു." → "I came from 1983 to 1980.". No ML Kit fallback for Malayalam. The models live in Anoop's public Hugging Face repos (akm9191); if they are deleted or made private, Malayalam downloads fail (the pinned revision + SHA-256 stop anything else being used).
- **Memory**: a loaded Opus-MT model uses roughly 570 MB while translating.
- **Offline quality**: ML Kit is casual-grade and pivots non-English pairs through English. Opus-MT is better on covered pairs but not universal. Greedy decoding made en-zh loop on short sentences; 4-beam search fixes the cases tested but costs about 2× the time per sentence (0.3–0.7 s on a Fold 5). en-zh still writes some clause breaks as spaces instead of commas, and wording can vary by a word between runs (multi-threaded maths).
- **Opus-MT integration**: ONNX Runtime + tokenizer must stay stable; catalog pins revisions and SHA-256. ONNX Runtime 1.28.0 fixes the SIGILL on Arm SME processors (Snapdragon 8 Elite Gen 5 / Galaxy Z Fold 8) but hasn't been run on one yet; the crash guard falls back to ML Kit if it ever dies there. Updating to 1.29+ would bring telemetry.
- **Data residency**: Live dictation audio leaves the device only after explicit consent and only to the disclosed region. Misconfiguration or provider change could violate user expectation.
- **Soft-delete recovery**: No automatic expiry window currently; deleted notes stay until hard-deleted. Users may accumulate them.
- **First-run friction**: Region + consent steps add length. Too long risks drop-off; too short risks unclear consent.
- **64-bit only**: App targets arm64; older 32-bit devices unsupported.
- **BYOK speech**: a Deepgram or Sarvam AI key is required for live dictation. Key storage and rotation are user responsibility.
- **Sarvam AI**: India-hosted only, so users outside India must consent to sending audio to India. Its retention is set per account on the Sarvam dashboard (the app can't send an opt-out flag like Deepgram's `mip_opt_out`). The Sarvam path was tested against a fake server built from its docs, not with a real key.
- **Settings layout (Oct 2026)**: simpler main screen with a page per speech provider. Every setting is still reachable (checked by `SettingsLayoutTest`), but several moved: the code-mixed switch is on the Sarvam AI page (always shown there), consent is the "Audio …" line at the bottom of each provider page, the welcome time and "Run first-time setup again" are under Advanced, slots 3–4 are behind "+ Add provider". Messages that point into Settings were reworded to match (e.g. "Add your Sarvam AI API key in Settings → Sarvam AI …").
- **Sarvam auto-detect**: Sarvam documents `language_code=auto` as "adaptive automatic language detection" and returns the detected language per transcript, but doesn't document how well it handles switching languages inside one sentence (partials are said to stay in a "sticky" language). Mixed Malayalam/English is untested. Settings → Sarvam AI → "Keep English words in English" (used when the language is Auto (Sarvam AI), off by default) sends Sarvam `mode=codemix` so English words stay in English; Sarvam applies it to finals only, so the words shown while you are still speaking may show English words in Malayalam script until the sentence closes. Untested with a real key.

- **On-device speech (slot 3)**: measured outside the app on a Fold 5: Malayalam 28% word error (7% character error) on 20 FLEURS clips, about 13× real time; English exact on two clean LibriSpeech clips. The English pack is trained on LibriSpeech (read American/British speech), so Indian or Australian accents and noisy rooms may do worse. “All languages (1,600+)” is lower quality (Malayalam 42% word error on the box) and large (366 MB, roughly that much memory while loaded). Untested inside the app on a phone: the mid-session switch, the no-internet start, the setup offer, pack downloads, memory and battery. Native code could crash on the Fold 8's SME processor; the speech crash guard turns slot 3 off after one native crash. Packs live in Anoop's public Hugging Face repos (akm9191), pinned by revision + SHA-256; the “All languages” pack comes from csukuangfj's repo. Downloads follow “Download on Wi-Fi only” (off by default, so mobile data is used).

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
- A provider for speech slot 4, and its residency.
- Monetisation tiers and which features sit behind them.
