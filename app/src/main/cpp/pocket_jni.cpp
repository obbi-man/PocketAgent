#include <jni.h>
#include <android/log.h>
#include <string>
#include "pocket_core.h"

#define TAG "PocketAgent"

static std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return "";
    // Берём байты через String.getBytes("UTF-8"), чтобы корректно передать эмодзи
    jclass cls = env->GetObjectClass(s);
    jmethodID mid = env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
    jstring enc = env->NewStringUTF("UTF-8");
    auto arr = (jbyteArray) env->CallObjectMethod(s, mid, enc);
    jsize len = env->GetArrayLength(arr);
    std::string out(len, '\0');
    env->GetByteArrayRegion(arr, 0, len, (jbyte *) out.data());
    env->DeleteLocalRef(arr); env->DeleteLocalRef(enc); env->DeleteLocalRef(cls);
    return out;
}

static jbyteArray jbytes(JNIEnv * env, const std::string & s) {
    jbyteArray a = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(a, 0, (jsize) s.size(), (const jbyte *) s.data());
    return a;
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_ru_pocketagent_LlamaBridge_nativeLoad(JNIEnv * env, jobject, jstring path, jint nCtx, jint nThreads) {
    std::string err;
    bool ok = pocket::load(jstr(env, path), nCtx, nThreads, err);
    return ok ? nullptr : env->NewStringUTF(err.c_str());   // err — только наши строки (валидный UTF-8, BMP)
}

JNIEXPORT void JNICALL
Java_ru_pocketagent_LlamaBridge_nativeUnload(JNIEnv *, jobject) { pocket::unload(); }

JNIEXPORT void JNICALL
Java_ru_pocketagent_LlamaBridge_nativeStop(JNIEnv *, jobject) { pocket::request_stop(); }

JNIEXPORT jint JNICALL
Java_ru_pocketagent_LlamaBridge_nativeCountTokens(JNIEnv * env, jobject, jstring text) {
    return pocket::count_tokens(jstr(env, text));
}

JNIEXPORT jstring JNICALL
Java_ru_pocketagent_LlamaBridge_nativeSystemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(pocket::system_info().c_str());
}

// callback: объект с методом boolean onPiece(byte[])
// Возвращает byte[] со всем ответом (UTF-8) или null при ошибке (текст ошибки — через onError)
JNIEXPORT jbyteArray JNICALL
Java_ru_pocketagent_LlamaBridge_nativeGenerate(JNIEnv * env, jobject, jstring prompt,
        jint maxTokens, jfloat temp, jint topK, jfloat topP, jfloat repeatPenalty, jobject callback) {
    pocket::GenParams p;
    p.max_tokens = maxTokens; p.temperature = temp; p.top_k = topK; p.top_p = topP; p.repeat_penalty = repeatPenalty;
    jclass cbCls = env->GetObjectClass(callback);
    jmethodID onPiece = env->GetMethodID(cbCls, "onPiece", "([B)Z");
    jmethodID onError = env->GetMethodID(cbCls, "onError", "([B)V");
    std::string err;
    std::string out = pocket::generate(jstr(env, prompt), p, [&](const std::string & piece) {
        jbyteArray a = jbytes(env, piece);
        jboolean cont = env->CallBooleanMethod(callback, onPiece, a);
        env->DeleteLocalRef(a);
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); return false; }
        return cont == JNI_TRUE;
    }, err);
    if (!err.empty()) {
        jbyteArray a = jbytes(env, err);
        env->CallVoidMethod(callback, onError, a);
        env->DeleteLocalRef(a);
        return nullptr;
    }
    return jbytes(env, out);
}

}
