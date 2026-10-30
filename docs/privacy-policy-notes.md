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
- The on-device speech packs (8–662 MB) are downloaded only when the user asks (setup offer or Settings → On this phone → Speech packs), from Hugging Face over HTTPS: akm9191/sherpa-onnx-streaming-zipformer-en-int8, akm9191/sherpa-onnx-indicconformer-ml-int8, akm9191/silero-vad-onnx, csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12 for "All languages (1,600+)", and the optional English add-ons akm9191/sherpa-onnx-parakeet-tdt-0.6b-v2-int8 ("Sharper English"), akm9191/sherpa-onnx-parakeet-tdt-110m-int8 ("Sharper English (small)") and akm9191/sherpa-onnx-online-punct-en-int8 ("English punctuation"). The add-ons run on the phone like the other packs; audio still never leaves it. Hugging Face sees the phone's IP address and the file requested, as with any download; no account or identifier is sent.
- Each file is pinned to a revision and checked with SHA-256 before use. Packs are stored in the app's no-backup folder and removed with Delete or when the app is uninstalled.
- Downloads use mobile data unless "Download on Wi-Fi only" is on.

## Licences and credit
- Sherpa-ONNX (k2-fsa), Apache-2.0. English model: k2-fsa streaming zipformer, Apache-2.0. Malayalam model: AI4Bharat IndicConformer, MIT (ONNX export by jeswinjestin, CC-BY-4.0; Malayalam-only head and int8 by us). Silero VAD, MIT. Omnilingual ASR (Meta), Apache-2.0. Sharper English: NVIDIA Parakeet TDT 0.6B v2, CC-BY-4.0 (int8 ONNX export by csukuangfj / k2-fsa). Sharper English (small): NVIDIA Parakeet TDT 110M (parakeet-tdt_ctc-110m), CC-BY-4.0 (int8 ONNX export by k2-fsa). English punctuation: Edge-Punct-Casing (frankyoujian, via k2-fsa), Apache-2.0.
