# Daily Whisper — Privacy Policy notes

Working notes for the Privacy Policy the setup screens point to ("Full details are in the Privacy Policy."). Not the policy itself.

## Notes
- Stored only on the phone (app-private SQLite, excluded from cloud backup and device transfer). Never uploaded.

## Translation
- Runs on the phone (ML Kit, Opus-MT). Note text is never sent anywhere. Models are downloaded once from Google (ML Kit) or Hugging Face (Opus-MT).

## Live dictation
- Deepgram (slot 1) and Sarvam AI (slot 2): audio is streamed to the provider the user set up with their own key, only after consent when it leaves the user's region. Deepgram gets `mip_opt_out=true`; Sarvam retention is set on the user's Sarvam account.
- On this phone (slot 3): speech is turned into text on the phone (Sherpa-ONNX). Audio never leaves the phone and nothing is kept after it is typed. No key, no account and no consent step are needed.
- When there's no internet, or the connection drops while dictating, the app uses On this phone instead (switch in Settings). It checks for internet with Android's own network status; it sends nothing to do so.

## Speech packs (downloads)
- The on-device speech packs (73–366 MB) are downloaded only when the user asks (setup offer or Settings → On this phone → Speech packs), from Hugging Face over HTTPS: akm9191/sherpa-onnx-streaming-zipformer-en-int8, akm9191/sherpa-onnx-indicconformer-ml-int8, akm9191/silero-vad-onnx, and csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12 for "All languages (1,600+)". Hugging Face sees the phone's IP address and the file requested, as with any download; no account or identifier is sent.
- Each file is pinned to a revision and checked with SHA-256 before use. Packs are stored in the app's no-backup folder and removed with Delete or when the app is uninstalled.
- Downloads use mobile data unless "Download on Wi-Fi only" is on.

## Licences and credit
- Sherpa-ONNX (k2-fsa), Apache-2.0. English model: k2-fsa streaming zipformer, Apache-2.0. Malayalam model: AI4Bharat IndicConformer, MIT (ONNX export by jeswinjestin, CC-BY-4.0; Malayalam-only head and int8 by us). Silero VAD, MIT. Omnilingual ASR (Meta), Apache-2.0.
