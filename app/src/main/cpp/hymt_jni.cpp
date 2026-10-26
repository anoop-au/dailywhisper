// JNI glue for HyMtNative.kt. Text crosses as UTF-8 byte arrays (NewStringUTF can't carry emoji).
#include <jni.h>
#include <string>
#include "hymt_engine.h"

namespace {
std::string bytes_to_string(JNIEnv * env, jbyteArray a) {
    jsize n = env->GetArrayLength(a);
    std::string s(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}
jbyteArray string_to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray a = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(a, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return a;
}
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_anoop_dailywhisper_HyMtNative_load(JNIEnv * env, jobject, jstring path, jint threads, jint n_ctx) {
    const char * p = env->GetStringUTFChars(path, nullptr);
    hymt::Engine * e = hymt::load(p, threads, n_ctx);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(e);
}

// Returns the UTF-8 reply, or null if cancelled / failed. [listener] (may be null) has onPartial(byte[]).
JNIEXPORT jbyteArray JNICALL
Java_com_anoop_dailywhisper_HyMtNative_generate(JNIEnv * env, jobject, jlong handle, jbyteArray prompt, jint max_new,
                                               jint seed, jobject listener) {
    auto * e = reinterpret_cast<hymt::Engine *>(handle);
    if (!e) return nullptr;
    jmethodID on_partial = nullptr;
    if (listener) on_partial = env->GetMethodID(env->GetObjectClass(listener), "onPartial", "([B)V");
    std::function<void(const std::string &)> cb;
    if (on_partial) cb = [&](const std::string & s) {
        jbyteArray b = string_to_bytes(env, s);
        env->CallVoidMethod(listener, on_partial, b);
        env->DeleteLocalRef(b);
    };
    std::string out, error;
    bool ok = hymt::generate(e, bytes_to_string(env, prompt), max_new, static_cast<uint32_t>(seed), cb, out, error);
    if (!ok) return nullptr;
    return string_to_bytes(env, out);
}

JNIEXPORT void JNICALL
Java_com_anoop_dailywhisper_HyMtNative_cancel(JNIEnv *, jobject, jlong handle) {
    auto * e = reinterpret_cast<hymt::Engine *>(handle);
    if (e) e->cancel.store(true);
}

JNIEXPORT void JNICALL
Java_com_anoop_dailywhisper_HyMtNative_free(JNIEnv *, jobject, jlong handle) {
    hymt::free_engine(reinterpret_cast<hymt::Engine *>(handle));
}

}  // extern "C"
