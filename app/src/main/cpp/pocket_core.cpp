#include "pocket_core.h"
#include "llama.h"
#include <vector>
#include <atomic>
#include <mutex>
#include <ctime>

extern "C" void pocket_set_stage(const char * s);

namespace pocket {

static llama_model   * g_model = nullptr;
static llama_context * g_ctx   = nullptr;
static std::vector<llama_token> g_cache;   // токены, уже лежащие в KV-кэше
static std::atomic<bool> g_stop{false};
static std::mutex g_mu;
static bool g_backend = false;

static size_t utf8_complete_prefix(const std::string & s) {
    // длина префикса, состоящего только из завершённых UTF-8 последовательностей
    size_t i = 0, n = s.size(), last_ok = 0;
    while (i < n) {
        unsigned char c = (unsigned char) s[i];
        size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
        if (i + len > n) break;
        i += len;
        last_ok = i;
    }
    return last_ok;
}

bool load(const std::string & path, int n_ctx, int n_threads, std::string & err) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (!g_backend) { llama_backend_init(); g_backend = true; }
    if (g_ctx)   { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_cache.clear();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    pocket_set_stage("load_model");
    g_model = llama_model_load_from_file(path.c_str(), mp);
    if (!g_model) { err = "не удалось загрузить модель (файл повреждён или не GGUF)"; return false; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    pocket_set_stage("init_context");
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        llama_model_free(g_model); g_model = nullptr;
        err = "не удалось создать контекст (мало памяти?)";
        return false;
    }
    pocket_set_stage("idle");
    return true;
}

bool is_loaded() { return g_ctx != nullptr; }

void unload() {
    std::lock_guard<std::mutex> lk(g_mu);
    if (g_ctx)   { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_cache.clear();
}

void request_stop() { g_stop = true; }

static std::vector<llama_token> tokenize(const std::string & text) {
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    int n = -llama_tokenize(vocab, text.c_str(), (int) text.size(), nullptr, 0, false, true);
    std::vector<llama_token> toks(n > 0 ? n : 0);
    if (n > 0) llama_tokenize(vocab, text.c_str(), (int) text.size(), toks.data(), n, false, true);
    return toks;
}

int count_tokens(const std::string & text) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (!g_model) return -1;
    return (int) tokenize(text).size();
}

std::string generate(const std::string & prompt, const GenParams & p, const PieceCallback & cb, std::string & err) {
    std::lock_guard<std::mutex> lk(g_mu);
    g_stop = false;
    if (!g_ctx) { err = "модель не загружена"; return ""; }
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    llama_memory_t mem = llama_get_memory(g_ctx);
    const int n_ctx = (int) llama_n_ctx(g_ctx);

    pocket_set_stage("tokenize");
    std::vector<llama_token> toks = tokenize(prompt);
    if (toks.empty()) { err = "пустой запрос"; return ""; }
    int max_new = p.max_tokens;
    if ((int) toks.size() + 8 > n_ctx) { err = "слишком длинный диалог для контекста"; return ""; }
    if ((int) toks.size() + max_new > n_ctx) max_new = n_ctx - (int) toks.size();

    // переиспользуем общий префикс с прошлым запросом (KV-кэш)
    size_t common = 0;
    while (common < g_cache.size() && common < toks.size() && g_cache[common] == toks[common]) common++;
    if (common == toks.size()) common--; // нужен хотя бы 1 токен, чтобы получить логиты
    if (!llama_memory_seq_rm(mem, 0, (llama_pos) common, -1)) {
        llama_memory_clear(mem, true);
        common = 0;
    }
    g_cache.resize(common);

    pocket_set_stage("prompt_decode");
    const int n_batch = 512;
    for (size_t i = common; i < toks.size(); i += n_batch) {
        int n = (int) std::min((size_t) n_batch, toks.size() - i);
        llama_batch b = llama_batch_get_one(toks.data() + i, n);
        if (llama_decode(g_ctx, b) != 0) {
            llama_memory_clear(mem, true); g_cache.clear();
            err = "ошибка обработки запроса (decode)"; return "";
        }
        g_cache.insert(g_cache.end(), toks.begin() + i, toks.begin() + i + n);
    }

    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sp);
    if (p.repeat_penalty > 1.0f)
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, p.repeat_penalty, 0.0f, 0.0f));
    if (p.temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(p.top_k));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(p.top_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(p.temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist((uint32_t) time(nullptr)));
    }

    pocket_set_stage("generate");
    std::string out, pending;
    char buf[256];
    for (int i = 0; i < max_new && !g_stop; i++) {
        llama_token t = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, t)) break;
        int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, true);
        if (n > 0) {
            pending.append(buf, n);
            size_t ok = utf8_complete_prefix(pending);
            if (ok > 0) {
                std::string piece = pending.substr(0, ok);
                pending.erase(0, ok);
                out += piece;
                if (cb && !cb(piece)) g_stop = true;
            }
        }
        llama_token tt = t;
        llama_batch b = llama_batch_get_one(&tt, 1);
        if (llama_decode(g_ctx, b) != 0) break;
        g_cache.push_back(t);
        // ChatML: модель иногда пишет <|im_start|> вместо EOS
        if (out.find("<|im_start|>") != std::string::npos || out.find("<|endoftext|>") != std::string::npos) break;
    }
    llama_sampler_free(smpl);
    pocket_set_stage("idle");
    return out;
}

std::string system_info() { return llama_print_system_info(); }

} // namespace pocket
