# PocketAgent — офлайн ИИ-агент для Android

* Модель: **MNN-Pocket-0.3B** (Alibaba MNN / taobao-mnn, Apache 2.0), файл GGUF `MNN-Pocket-0.3B-Instruct-Q4_1.gguf`
  (QAT 4-бит, 192 МБ) — https://huggingface.co/taobao-mnn/MNN-Pocket-0.3B-GGUF
* Движок: llama.cpp (исходники в `third_party/llama.cpp`, статическая сборка через NDK, только CPU arm64-v8a).
* Модель вшита в APK (`app/src/main/assets`, без сжатия). При первом запуске копируется во внутреннюю память (~192 МБ), дальше всё офлайн.
* Резерв: импорт любого .gguf из памяти телефона (меню) и кнопка скачивания с HF / hf-mirror, если модели в APK нет.

## Лайт-версия (без модели в APK, ~11 МБ)
`./gradlew assembleRelease -Plite` — модель не вшивается; при первом запуске приложение предлагает скачать её
(Hugging Face → hf-mirror.com), с докачкой через HTTP Range, SHA-256 проверкой и сохранением прогресса в `.part`.

## Агент
1. `Router.kt` — правила для очевидных команд (рус/англ): время/дата, калькулятор, заметки, таймер, открыть сайт.
2. Если правило не сработало, но в тексте есть «намёк» на инструмент — модель вызывается как планировщик
   с описанием инструментов в JSON (родной формат chat_template модели: `<tools>…</tools>` / `<tool_call>{"name":…,"arguments":…}</tool_call>`).
   Ответ парсится (`ToolCallParser`) и проверяется (`Tools.validate`): неизвестный инструмент, неверные аргументы или
   неуместный вызов отбрасываются.
3. Иначе — обычный чат со стримингом.
Инструменты: get_datetime, calculator, save_note, read_notes, clear_notes, set_timer (интент «Часов» или встроенный), open_url (с подтверждением).

## Сборка
```
./download_model.sh
export ANDROID_HOME=~/Android/Sdk   # нужны platform 35, build-tools 35, NDK 27.2.12479018, CMake 3.22.1, JDK 17
./gradlew assembleRelease           # app/build/outputs/apk/release/app-release.apk (подпись: положите свой ключ в корень проекта и заполните keystore.properties по образцу keystore.properties.example; без них release подписывается debug-ключом)
./gradlew testReleaseUnitTest       # тесты логики агента
```

## Политика конфиденциальности

https://obbi-man.github.io/PocketAgent/

Обращения: https://github.com/obbi-man/PocketAgent/issues

Сторонние лицензии: [THIRD_PARTY.md](THIRD_PARTY.md). В приложении: меню → «Приватность и лицензии».

## Сборка для RuStore

Автообновление с Google Диска в магазинную сборку не входит:

```
./gradlew assembleRelease -Plite -Prustore
```

Обычные команды `assembleRelease` и `assembleRelease -Plite` не меняются.
