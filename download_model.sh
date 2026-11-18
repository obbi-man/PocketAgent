#!/bin/sh
# Скачивает модель в assets (нужно перед сборкой; в исходниках её нет из-за размера 192 МБ)
set -e
F=MNN-Pocket-0.3B-Instruct-Q4_1.gguf
mkdir -p app/src/main/assets
curl -L -o app/src/main/assets/$F https://huggingface.co/taobao-mnn/MNN-Pocket-0.3B-GGUF/resolve/main/$F \
 || curl -L -o app/src/main/assets/$F https://hf-mirror.com/taobao-mnn/MNN-Pocket-0.3B-GGUF/resolve/main/$F
echo "a281c127e388b16537fd3f5d31ab32d3f87739e6dc514ac1093c80d9eac8d097  app/src/main/assets/$F" | sha256sum -c
