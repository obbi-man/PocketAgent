# Как выпустить обновление PocketAgent через Google Диск

Приложение раз в сутки (по умолчанию только по Wi-Fi) и по кнопке «Проверить обновления» скачивает
`update.json` по постоянной ссылке, зашитой в сборку:

    https://drive.google.com/uc?export=download&id=1Ndu-3ihhgLO_xJYRdksAKHvlE75TjJxj

**Этот fileId менять нельзя**: в update.json меняется только содержимое (новая версия файла на Диске),
иначе старые установленные версии перестанут видеть обновления.

## Шаги
1. Поднимите `versionCode` в `app/build.gradle.kts` (2.3 = 7, 2.4 = 8, следующая — 9) и `versionName`.
   Добавьте раздел `## Новое в X` в README_RELEASE_RU.md (или файл tools/changelog_X.txt).
2. Соберите лёгкую версию **тем же ключом** (`pocketagent.keystore`). С другим ключом обновление будет отклонено:
   приложение сверяет подпись.

       ./gradlew -Plite assembleRelease
       cp app/build/outputs/apk/release/app-release.apk PocketAgent_lite_vX.apk

3. Загрузите `PocketAgent_lite_vX.apk` в папку «PocketAgent» на Google Диске.
   Откройте доступ: ⋮ → «Открыть доступ» → «Все, у кого есть ссылка» → «Читатель».
   Скопируйте ID файла (часть ссылки между `/d/` и `/view`).
4. Создайте манифест:

       tools/make_update_manifest.sh PocketAgent_lite_vX.apk \
         "https://drive.usercontent.google.com/download?id=<ID_APK>&export=download&confirm=t" \
         tools/changelog_X.txt > update.json

   В update.json будут versionCode, versionName, apkUrl, sha256, minSdk, size, changelog_ru.
5. Замените содержимое `update.json` на Диске, **сохранив тот же файл**: ⋮ → «Управление версиями» →
   «Загрузить новую версию» (не «Загрузить файл», иначе появится новый ID).
   Доступ «Все, у кого есть ссылка: читатель» у update.json должен остаться.
6. Проверьте: `curl -sL "https://drive.google.com/uc?export=download&id=1Ndu-3ihhgLO_xJYRdksAKHvlE75TjJxj"`
   должен вернуть новый JSON (кэш Диска может обновляться несколько минут).
   На телефоне: меню → «Проверить обновления».

## Как это работает в приложении
- Большие APK Диск отдаёт через страницу «Не удалось проверить файл на вирусы». Приложение распознаёт эту
  HTML-страницу и само повторяет запрос с подтверждением (`confirm`/`uuid`).
- Загрузка докачивается после обрыва (Range). Перед установкой проверяются sha256 из update.json,
  имя пакета, совпадение сертификата подписи с установленным приложением и что versionCode больше текущего.
- Установка идёт через системный PackageInstaller: Android покажет запрос «Разрешить установку из этого источника»
  (один раз), затем системное подтверждение. Тихой установки нет.
- Формат GitHub Releases (`/releases/latest` из API) тоже понимается. Тогда берётся asset с «lite» в имени,
  а sha256 — из текста релиза.
- Собрать версию без обновлений: `-PupdateUrl=` (пустая строка). Своя ссылка: `-PupdateUrl=https://…/update.json`.
