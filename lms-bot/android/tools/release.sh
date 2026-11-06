#!/usr/bin/env bash
# Выпуск обновления Android-приложения: сборка и релиз на GitHub.
# Установленные приложения находят его сами (Updater) и предлагают обновиться.
#
#   bash android/tools/release.sh "Что нового: …"
#
# Перед запуском поднимите versionCode и versionName в android/app/build.gradle.kts —
# тег релиза android-<versionCode>, приложение сравнивает именно versionCode.
set -euo pipefail
cd "$(dirname "$0")/.."

NOTES="${1:-}"
CODE=$(grep -E '^\s*versionCode\s*=' app/build.gradle.kts | grep -oE '[0-9]+')
NAME=$(grep -E '^\s*versionName\s*=' app/build.gradle.kts | grep -oE '"[^"]+"' | tr -d '"')
TAG="android-$CODE"

if gh release view "$TAG" --repo Umarkhn1/telegrambots >/dev/null 2>&1; then
  echo "Релиз $TAG уже есть — поднимите versionCode в app/build.gradle.kts" >&2
  exit 1
fi

./gradlew :app:assembleRelease --console=plain -q
cp app/build/outputs/apk/release/app-release.apk TUIT-LMS.apk

gh release create "$TAG" TUIT-LMS.apk \
  --repo Umarkhn1/telegrambots \
  --title "TUIT LMS $NAME" \
  --notes "${NOTES:-TUIT LMS $NAME}"

echo "Опубликовано: $TAG ($NAME). Приложения предложат обновиться при следующем открытии."
