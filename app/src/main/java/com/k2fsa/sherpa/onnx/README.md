# Sherpa-ONNX Kotlin API (vendored)

Unmodified copies of the Kotlin API files from [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
`sherpa-onnx/kotlin-api/`, commit `935e5ded3d949a8b557ac44ef4dad03a7d2038e7` (Apache License 2.0,
Copyright (c) Xiaomi Corporation and the sherpa-onnx authors). They bind to the JNI names in
`app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so`, which `tools/sherpa-android/build.sh` builds from the same
commit against the app's own ONNX Runtime 1.28.0. Only the files for streaming and offline speech recognition and the
voice-activity detector are here. The package name must stay `com.k2fsa.sherpa.onnx` (the native code looks the
classes up by name; see proguard-rules.pro). Daily Whisper's own code is in `com.anoop.dailywhisper.OnDeviceSpeech`.
