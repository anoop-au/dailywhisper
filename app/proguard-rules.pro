# Views referenced from layouts are kept automatically by AAPT rules.
-assumenosideeffects class android.util.Log { public static int v(...); public static int d(...); }
# JNI (hymt_jni.cpp) calls Listener.onPartial(byte[]) by name.
-keep interface com.anoop.dailywhisper.HyMtNative$Listener { *; }
-keepclassmembers class * implements com.anoop.dailywhisper.HyMtNative$Listener { void onPartial(byte[]); }
