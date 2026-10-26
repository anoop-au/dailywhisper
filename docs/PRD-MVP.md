# Daily Whisper — PRD + MVP Scope

## Purpose
Calm, on-device Android notes app for short and longer whispers. Capture text (or live dictation), keep it local, translate offline, and manage notes with history, soft-delete, archive, highlight and search.

## Target users
People who want a private, fast notes surface on Android — especially those who dictate in one language and want to read or share in another. Primary context: Australia, with support for common languages spoken there.

## Core value
- Notes never leave the phone.
- Offline translation (ML Kit by default, Opus-MT for chosen pairs).
- Soft-delete + recoverable history.
- Minimal friction after a short first-run setup.

## MVP features (implemented or in current code)
- Notes with append-only versioning (`note_versions`). Feed shows latest; History lists versions; Restore creates a new version.
- Soft-delete (status = deleted, deleted_at). Recently Deleted view with Restore. Hard delete only from that view with confirm.
- Archive (status = archived). Separate list; open to restore.
- Highlight colour on notes (configurable default).
- FTS4 full-text search (prefix per word) across active notes (optional include archived).
- First-run setup (once): welcome screen, source language, target language, explicit region, data-residency disclosure for live dictation, then notes list.
- Offline translation:
  - ML Kit as immediate fallback / default.
  - Opus-MT (Helsinki-NLP, int8 ONNX) for the pair derived from source + target. Background download, Wi-Fi only by default, SHA-256 check.
  - Open note in reading view; tap text to translate. Result panel with Copy / Keep both / Replace (Undo). Pencil or long-press to edit.
- Share (from note header and long-press menu on list).
- Multi-select soft-delete (long-press menu → Select → checkboxes + top-bar Delete).
- Live dictation via Deepgram (BYOK, regional endpoints, mip_opt_out). Mic hidden until residency consent if required.
- Settings for translation languages, model management, region/consent, theme, highlight default, search options, etc.

## Explicitly out of MVP (later)
- Gesture customisation (swipe-to-share etc.).
- Additional speech provider slots beyond Deepgram + planned Sarvam fallback.
- Full commercial monetisation tiers (coffee / pizza / biriyani).
- Broader model choices beyond ML Kit + Opus-MT pairs.
- Cloud sync or multi-device.

## Success criteria for MVP
- User can complete first-run, create notes, translate offline, soft-delete and recover, search, archive, highlight, share, and multi-select delete without data leaving the device (except consented live-dictation audio).
- App remains usable on 64-bit Android devices with the current size footprint (~ML Kit + ONNX Runtime).
