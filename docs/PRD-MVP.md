# Daily Whisper — PRD + MVP Scope

## Purpose
Calm, on-device Android notes app for short and longer whispers. Capture text (or live dictation), keep it local, translate offline, and manage notes with history, soft-delete, archive, highlight and search.

## Target users
People who want a private, fast notes surface on Android — especially those who dictate in one language and want to read or share in another. Primary context: Australia, with support for common languages spoken there.

## Core value
- Notes never leave the phone.
- Offline translation (Opus-MT for the chosen pair once its model is downloaded, ML Kit otherwise).
- Soft-delete + recoverable history.
- Minimal friction after a short first-run setup.

## MVP features (implemented or in current code)
- Notes with append-only versioning (`note_versions`). Feed shows latest; History lists versions; Restore creates a new version.
- Soft-delete (status = deleted, deleted_at) with an Undo bar. Deleted view (⋮ → Deleted, most recent first) with Restore. Hard delete ("Delete forever", removes the note's history too) only from that view, with confirm. No expiry.
- Archive (status = archived). Separate list; open to restore.
- Highlight colour on notes (configurable default).
- FTS4 full-text search (prefix per word) across active notes (optional include archived; deleted never).
- First-run setup (once): welcome screen (2.5 s, adjustable), source language, target language, explicit region, data-residency disclosure for live dictation (plain language: no provider, endpoint, engine or model names on setup screens; "Full details are in the Privacy Policy."), "You can change these later in Settings.", then notes list. The chosen pair's Opus-MT model starts downloading in the background after the region step.
- Offline translation:
  - ML Kit as the fallback (until Opus-MT is ready, for pairs Opus-MT lacks such as English→Japanese/Korean, or if Opus-MT fails). Language packs (~30 MB each) download on first use.
  - Opus-MT (Helsinki-NLP, int8 ONNX, ONNX Runtime 1.28.0) for the pair derived from source + target, direct or through English. Background download on any network (mobile data included; "Download on Wi-Fi only" is off by default), Range resume, SHA-256 check. 4-beam search by default (Settings → Opus-MT search width).
  - Open note in reading view; tap text to translate. Result panel with Copy / Keep both / Replace (Undo). Pencil or long-press to edit.
- Share (system share sheet, plain text: from the note header and the list's long-press menu).
- Multi-select soft-delete (long-press menu → Select → ticks + top bar ✕ / "N selected" / Select all / Delete; one Undo bar).
- Live dictation with 4 BYOK speech slots: Deepgram (slot 1, regional endpoints, mip_opt_out) and Sarvam AI (slot 2, India; adds Malayalam and 10 other Indian languages Deepgram lacks) with automatic fallback; slots 3–4 empty. Language list offers "Multilingual (Deepgram)" and "Auto (Sarvam AI)" (Sarvam's own detection for Malayalam, English and other Indian languages, slot 2 only). Adjustable pause before a sentence ends (default 1.3 s). Mic hidden until residency consent if required.
- Settings for translation languages, model management, region/consent, theme, highlight default, search options, etc.

## Explicitly out of MVP (later)
- Gesture customisation (swipe-to-share etc.).
- Providers for speech slots 3 and 4 (the slots exist, empty).
- Full commercial monetisation tiers (coffee / pizza / biriyani).
- Broader model choices beyond ML Kit + Opus-MT pairs.
- Cloud sync or multi-device.

## Success criteria for MVP
- User can complete first-run, create notes, translate offline, soft-delete and recover, search, archive, highlight, share, and multi-select delete without data leaving the device (except consented live-dictation audio).
- App remains usable on 64-bit Android devices (Android 11+, arm64) with the current size footprint: release APK about 47 MB (ML Kit 16 MB + ONNX Runtime 29 MB native), plus ~110–140 MB per Opus-MT model and ~30 MB per ML Kit language pack once downloaded.

## Status (Oct 2026)
- Current release: v0.1.10 (versionCode 11), 27 Oct 2026, signed release APK about 47 MB. New since v0.1.9: the "Auto (Sarvam AI)" dictation language (code `auto-sarvam`, sends Sarvam `language_code=auto`, speech slot 2 only, shows "Sarvam AI hears <language>" when the detected language changes) and Deepgram's automatic choice renamed "Multilingual (Deepgram)" (code `multi` unchanged).
- Tested on a Galaxy Z Fold 5 (Snapdragon 8 Gen 2) on mobile data: Opus-MT en-zh downloaded (119 MB) and translated multi-sentence notes; ML Kit downloaded en-ko and translated, and translated en-ja. No crashes.
- Not yet tested on the Fold 8 (Snapdragon 8 Elite Gen 5, the SME processor ONNX Runtime 1.28.0 fixes), the unfolded inner screen, or live dictation with real Deepgram / Sarvam AI keys (so Auto (Sarvam AI) and mixed Malayalam/English in one sentence are untested on a phone).
