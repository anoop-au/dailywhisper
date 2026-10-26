# Views referenced from layouts are kept automatically by AAPT rules.
-assumenosideeffects class android.util.Log { public static int v(...); public static int d(...); }

# ONNX Runtime (Opus-MT translation): its native code looks up these Java classes, fields and constructors by name,
# and the AAR ships no keep rules of its own.
-keep class ai.onnxruntime.** { *; }
