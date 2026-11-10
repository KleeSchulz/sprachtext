// JNI-Brücke zwischen Java (de.sprachtext.app.Whisper) und whisper.cpp
#include <jni.h>
#include <atomic>
#include <string>
#include <android/log.h>
#include "whisper.h"

#define TAG "Sprachtext"

static std::atomic<bool> g_cancel{false};

struct Callbacks {
    JNIEnv *env;
    jobject listener;
    jmethodID onProgress;
    jmethodID onSegment;
};

static jbyteArray utf8(JNIEnv *env, const std::string &s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(arr, 0, (jsize) s.size(), (const jbyte *) s.data());
    return arr;
}

static void on_progress(whisper_context *, whisper_state *, int progress, void *ud) {
    auto *cb = (Callbacks *) ud;
    cb->env->CallVoidMethod(cb->listener, cb->onProgress, (jint) progress);
}

static void on_new_segment(whisper_context *ctx, whisper_state *, int n_new, void *ud) {
    auto *cb = (Callbacks *) ud;
    const int n = whisper_full_n_segments(ctx);
    std::string text;
    for (int i = n - n_new; i < n; i++) text += whisper_full_get_segment_text(ctx, i);
    jbyteArray arr = utf8(cb->env, text);
    cb->env->CallVoidMethod(cb->listener, cb->onSegment, arr);
    cb->env->DeleteLocalRef(arr);
}

// Wird aus den Rechen-Threads aufgerufen → nur atomares Flag lesen, kein JNI
static bool on_abort(void *) {
    return g_cancel.load();
}

static void log_cb(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

extern "C" JNIEXPORT jlong JNICALL
Java_de_sprachtext_app_Whisper_init(JNIEnv *env, jclass, jstring path) {
    whisper_log_set(log_cb, nullptr);
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return (jlong) ctx;
}

extern "C" JNIEXPORT void JNICALL
Java_de_sprachtext_app_Whisper_free(JNIEnv *, jclass, jlong ctx) {
    if (ctx) whisper_free((whisper_context *) ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_de_sprachtext_app_Whisper_cancel(JNIEnv *, jclass) {
    g_cancel.store(true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_de_sprachtext_app_Whisper_systemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

/** Gibt den Text als UTF-8-Bytes zurück, oder null bei Fehler/Abbruch. */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_de_sprachtext_app_Whisper_transcribe(JNIEnv *env, jclass, jlong ctxPtr, jfloatArray audio,
                                          jstring lang, jint threads, jobject listener) {
    auto *ctx = (whisper_context *) ctxPtr;
    if (!ctx) return nullptr;
    g_cancel.store(false);

    const char *l = env->GetStringUTFChars(lang, nullptr);
    std::string language(l);
    env->ReleaseStringUTFChars(lang, l);

    jclass cls = env->GetObjectClass(listener);
    Callbacks cb{env, listener,
                 env->GetMethodID(cls, "onProgress", "(I)V"),
                 env->GetMethodID(cls, "onSegment", "([B)V")};

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = language.c_str();
    p.translate = false;
    p.no_context = true;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.progress_callback = on_progress;
    p.progress_callback_user_data = &cb;
    p.new_segment_callback = on_new_segment;
    p.new_segment_callback_user_data = &cb;
    p.abort_callback = on_abort;
    p.abort_callback_user_data = nullptr;

    jsize n = env->GetArrayLength(audio);
    jfloat *data = env->GetFloatArrayElements(audio, nullptr);
    int rc = whisper_full(ctx, p, data, n);
    env->ReleaseFloatArrayElements(audio, data, JNI_ABORT);
    if (rc != 0 || g_cancel.load()) return nullptr;

    std::string text;
    const int segs = whisper_full_n_segments(ctx);
    for (int i = 0; i < segs; i++) text += whisper_full_get_segment_text(ctx, i);
    return utf8(env, text);
}
