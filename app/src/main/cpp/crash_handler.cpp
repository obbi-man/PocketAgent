// Ловушка нативных крашей: SIGSEGV/SIGABRT/SIGILL/SIGBUS/SIGFPE → короткий отчёт в файл,
// затем штатное падение (системный обработчик). Плюс кольцевой буфер последних логов llama.cpp.
#include <jni.h>
#include <android/log.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
#include <stdio.h>
#include <unwind.h>
#include <dlfcn.h>
#include <mutex>
#include <string>
#include "ggml.h"
#include "llama.h"

static char g_path[512] = {0};
static struct sigaction g_old[32];
static char g_ring[8192];
static size_t g_ring_pos = 0;
static std::mutex g_ring_mu;
static char g_abort_msg[1024] = {0};
static volatile const char * g_stage = "idle";
static volatile bool g_verbose = false;   // логи в logcat только в debug-сборке

extern "C" void pocket_set_stage(const char * s) { g_stage = s; }

static void ring_append(const char * s) {
    std::lock_guard<std::mutex> lk(g_ring_mu);
    for (const char * p = s; *p; ++p) { g_ring[g_ring_pos % sizeof(g_ring)] = *p; g_ring_pos++; }
}

static void log_cb(ggml_log_level level, const char * text, void *) {
    if (!text || level == GGML_LOG_LEVEL_DEBUG) return;
    ring_append(text);
    if (g_verbose) __android_log_print(level >= GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, "llama", "%s", text);
}

static void abort_cb(const char * msg) {
    strncpy(g_abort_msg, msg ? msg : "", sizeof(g_abort_msg) - 1);
    __android_log_print(ANDROID_LOG_FATAL, "PocketAgent", "ggml abort: %s", g_abort_msg);
}

struct BtState { void ** cur; void ** end; };
static _Unwind_Reason_Code unwind_cb(struct _Unwind_Context * ctx, void * arg) {
    auto * st = (BtState *) arg;
    uintptr_t pc = _Unwind_GetIP(ctx);
    if (pc) { if (st->cur == st->end) return _URC_END_OF_STACK; *st->cur++ = (void *) pc; }
    return _URC_NO_REASON;
}

static void wr(int fd, const char * s) { if (s) { ssize_t r = write(fd, s, strlen(s)); (void) r; } }

static void handler(int sig, siginfo_t * info, void * uctx) {
    int fd = g_path[0] ? open(g_path, O_WRONLY | O_CREAT | O_TRUNC, 0644) : -1;
    if (fd >= 0) {
        char buf[256];
        snprintf(buf, sizeof(buf), "NATIVE CRASH: signal %d (%s), code %d, addr %p\nstage: %s\n",
                 sig, sig == SIGILL ? "SIGILL — недоступная инструкция CPU" : sig == SIGSEGV ? "SIGSEGV" :
                      sig == SIGABRT ? "SIGABRT (abort/assert)" : sig == SIGBUS ? "SIGBUS" : sig == SIGFPE ? "SIGFPE" : "?",
                 info ? info->si_code : 0, info ? info->si_addr : nullptr, (const char *) g_stage);
        wr(fd, buf);
        if (g_abort_msg[0]) { wr(fd, "ggml abort: "); wr(fd, g_abort_msg); wr(fd, "\n"); }
        void * frames[32]; BtState st{frames, frames + 32};
        _Unwind_Backtrace(unwind_cb, &st);
        wr(fd, "backtrace:\n");
        for (void ** f = frames; f < st.cur; ++f) {
            Dl_info di; memset(&di, 0, sizeof(di));
            dladdr(*f, &di);
            snprintf(buf, sizeof(buf), "  #%02d pc %p %s (%s+0x%lx)\n", (int) (f - frames), *f,
                     di.dli_fname ? di.dli_fname : "?", di.dli_sname ? di.dli_sname : "?",
                     (unsigned long) ((char *) *f - (char *) (di.dli_saddr ? di.dli_saddr : di.dli_fbase)));
            wr(fd, buf);
        }
        wr(fd, "last llama log:\n");
        size_t n = g_ring_pos < sizeof(g_ring) ? g_ring_pos : sizeof(g_ring);
        size_t start = g_ring_pos < sizeof(g_ring) ? 0 : g_ring_pos % sizeof(g_ring);
        for (size_t i = 0; i < n; i++) { char c = g_ring[(start + i) % sizeof(g_ring)]; ssize_t r = write(fd, &c, 1); (void) r; }
        close(fd);
    }
    // вернуть старый обработчик и упасть штатно
    sigaction(sig, &g_old[sig], nullptr);
    raise(sig);
}

extern "C" JNIEXPORT void JNICALL
Java_ru_pocketagent_LlamaBridge_nativeInstallCrashHandler(JNIEnv * env, jobject, jstring path) {
    const char * p = env->GetStringUTFChars(path, nullptr);
    strncpy(g_path, p, sizeof(g_path) - 1);
    env->ReleaseStringUTFChars(path, p);
    llama_log_set(log_cb, nullptr);
    ggml_set_abort_callback(abort_cb);
    // отдельный стек для обработчика (на случай переполнения стека)
    static char altstack[64 * 1024];
    stack_t ss; ss.ss_sp = altstack; ss.ss_size = sizeof(altstack); ss.ss_flags = 0;
    sigaltstack(&ss, nullptr);
    struct sigaction sa; memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = handler; sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    for (int s : {SIGSEGV, SIGABRT, SIGILL, SIGBUS, SIGFPE}) sigaction(s, &sa, &g_old[s]);
}

extern "C" JNIEXPORT jstring JNICALL
Java_ru_pocketagent_LlamaBridge_nativeRecentLog(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lk(g_ring_mu);
    size_t n = g_ring_pos < sizeof(g_ring) ? g_ring_pos : sizeof(g_ring);
    size_t start = g_ring_pos < sizeof(g_ring) ? 0 : g_ring_pos % sizeof(g_ring);
    std::string s; s.reserve(n);
    for (size_t i = 0; i < n; i++) { char c = g_ring[(start + i) % sizeof(g_ring)]; s += (c & 0x80) ? '?' : c; }
    return env->NewStringUTF(s.c_str());   // только ASCII — безопасно для modified UTF-8
}

extern "C" JNIEXPORT void JNICALL
Java_ru_pocketagent_LlamaBridge_nativeSetVerbose(JNIEnv *, jobject, jboolean v) { g_verbose = v; }
