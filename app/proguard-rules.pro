# Views referenced from layouts are kept automatically by AAPT rules.
-assumenosideeffects class android.util.Log { public static int v(...); public static int d(...); }

# ONNX Runtime (Opus-MT translation): its native code looks up these Java classes, fields and constructors by name,
# and the AAR ships no keep rules of its own.
-keep class ai.onnxruntime.** { *; }

# Sherpa-ONNX (on-device speech, slot 3): its JNI code reads these Kotlin config classes' fields and calls the result
# constructors by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
