#!/usr/bin/env bash
# Создаёт update.json для автообновления PocketAgent из собранного APK.
#   tools/make_update_manifest.sh <app.apk> <apkUrl> [changelog.txt] > update.json
# apkUrl для Google Диска: https://drive.usercontent.google.com/download?id=<FILE_ID>&export=download&confirm=t
# changelog.txt — текст «Что нового» по-русски (UTF-8); без него берётся раздел «Новое в X» из README_RELEASE_RU.md.
set -euo pipefail
APK="${1:?укажите путь к APK}"; URL="${2:?укажите прямую ссылку на APK}"; NOTES="${3:-}"
AAPT="${AAPT:-$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)}"
[ -x "$AAPT" ] || { echo "не найден aapt2 (задайте AAPT=/путь/к/aapt2)" >&2; exit 1; }
BADGING="$("$AAPT" dump badging "$APK")"
VC=$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$BADGING" | head -1)
VN=$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGING" | head -1)
MIN=$(sed -n "s/^minSdkVersion:'\([0-9]*\)'.*/\1/p" <<<"$BADGING" | head -1)
PKG=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$BADGING" | head -1)
[ "$PKG" = "ru.pocketagent" ] || { echo "это не PocketAgent ($PKG)" >&2; exit 1; }
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
SIZE=$(stat -c %s "$APK")
if [ -n "$NOTES" ]; then CHANGES="$(cat "$NOTES")"
else
  README="$(dirname "$0")/../README_RELEASE_RU.md"
  CHANGES="$(awk -v v="$VN" '$0 ~ "^## Новое в "v {f=1; next} f && /^## / {exit} f' "$README" 2>/dev/null | sed '/^\s*$/d' | head -25)"
fi
python3 - "$VC" "$VN" "$URL" "$SHA" "$MIN" "$SIZE" "$CHANGES" <<'PY'
import json, sys
vc, vn, url, sha, mn, size, notes = sys.argv[1:8]
print(json.dumps({"versionCode": int(vc), "versionName": vn, "apkUrl": url, "sha256": sha,
                  "minSdk": int(mn or 26), "size": int(size), "changelog_ru": notes.strip()}, ensure_ascii=False, indent=2))
PY
