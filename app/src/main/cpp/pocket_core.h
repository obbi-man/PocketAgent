#pragma once
#include <string>
#include <functional>

// Минимальное ядро инференса поверх llama.cpp (без JNI) — тестируется на ПК.
namespace pocket {

struct GenParams {
    int   max_tokens  = 512;
    float temperature = 0.7f;
    int   top_k       = 20;
    float top_p       = 0.8f;
    float repeat_penalty = 1.05f;
};

// on_piece получает куски текста, всегда содержащие только целые UTF-8 символы.
// Вернуть false — остановить генерацию.
using PieceCallback = std::function<bool(const std::string &)>;

bool load(const std::string & path, int n_ctx, int n_threads, std::string & err);
bool is_loaded();
void unload();
// prompt — полностью сформированный ChatML-текст. Возвращает весь сгенерированный текст.
// Пустая строка + err при ошибке.
std::string generate(const std::string & prompt, const GenParams & p, const PieceCallback & cb, std::string & err);
int  count_tokens(const std::string & text);
void request_stop();
std::string system_info();

} // namespace pocket
