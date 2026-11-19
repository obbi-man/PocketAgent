# Сторонние компоненты и лицензии

PocketAgent сам распространяется по лицензии MIT (см. [LICENSE](LICENSE)).
Ниже — компоненты, которые входят в приложение или скачиваются по желанию пользователя.
Полные тексты MIT и Apache 2.0 также показываются в приложении: меню → «Приватность и лицензии».
Политика конфиденциальности: https://obbi-man.github.io/PocketAgent/

## Движок

- **llama.cpp / ggml** — MIT License, Copyright (c) 2023–2026 The ggml authors.
  https://github.com/ggml-org/llama.cpp
  Исходники, используемые при сборке, лежат в `third_party/llama.cpp`.

## Языковые модели (веса)

В облегчённой (lite) сборке модель **не вшита** в APK. При первом запуске по команде пользователя
скачивается квантованный GGUF-файл (около 351 МБ):

- **Vikhr-Qwen-2.5-0.5B-Instruct** (файл `Vikhr-Qwen-2.5-0.5B-instruct-Q4_0.gguf`) —
  Apache License 2.0, © Vikhrmodels.
  https://huggingface.co/Vikhrmodels/Vikhr-Qwen-2.5-0.5B-instruct-GGUF
  https://huggingface.co/Vikhrmodels/Vikhr-Qwen-2.5-0.5b-Instruct
- Основана на **Qwen2.5-0.5B-Instruct** — Apache License 2.0, © Alibaba Cloud (Qwen team).
  https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct

По желанию пользователь может скачать или импортировать другие GGUF-модели, в том числе:

- Vikhr-Qwen-2.5-1.5B-Instruct — Apache License 2.0, © Vikhrmodels; основана на Qwen2.5-1.5B-Instruct (Apache 2.0, © Alibaba Cloud).
- MNN-Pocket-0.3B — Apache License 2.0, © Alibaba (taobao-mnn / MNN team).
  https://huggingface.co/taobao-mnn/MNN-Pocket-0.3B

Модели используются без изменений (готовые квантованные GGUF-файлы авторов). Обе лицензии Apache 2.0
разрешают распространение в бесплатном приложении при сохранении уведомления о лицензии.

## Библиотеки Android

- AndroidX (core, appcompat, recyclerview), Material Components for Android, Kotlin stdlib — Apache License 2.0, © Google LLC / JetBrains s.r.o.
- Значки интерфейса — по мотивам Material Icons / Material Symbols (Google), Apache License 2.0.
- PDFBox Android (`com.tom-roush:pdfbox-android`) — Apache License 2.0, на основе Apache PDFBox. Только чтение текста PDF на устройстве.

## Данные онлайн-поиска (если пользователь не выключил «Поиск в интернете»)

- Википедия — тексты CC BY-SA 4.0; в ответе указывается источник.
- Прогноз погоды Open-Meteo — CC BY 4.0 (open-meteo.com); в ответе указывается источник.
- Курсы валют — Банк России (cbr.ru / cbr-xml-daily.ru).
