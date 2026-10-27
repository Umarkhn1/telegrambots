#!/usr/bin/env bash
# Локальный запуск бота вместе с мини-приложением (Linux/macOS) — то же, что start-local.ps1.
# Токен берётся из файла .env рядом со скриптом (он в .gitignore) или из переменной BOT_TOKEN.
# Публичный https-адрес даёт бесплатный туннель Cloudflare (без аккаунта): по нему бот ставит
# webhook и кнопку мини-приложения. Пока бот запущен здесь, сервис на хостинге должен быть выключен.
set -euo pipefail
cd "$(dirname "$0")"

if [ -f .env ]; then
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
fi
if [ -z "${BOT_TOKEN:-}" ]; then
    echo "Нет BOT_TOKEN: создайте файл .env со строкой BOT_TOKEN=<токен от @BotFather>"
    exit 1
fi
export PORT="${PORT:-8080}"
export LMS_DEBUG="${LMS_DEBUG:-true}"

JAR=build/libs/lms-bot.jar
if [ ! -f "$JAR" ]; then
    echo "Нет $JAR — соберите: (cd webapp && npm ci && npm run build) && ./gradlew jar"
    exit 1
fi

CF="$(command -v cloudflared || true)"
if [ -z "$CF" ]; then
    CF="$HOME/.local/bin/cloudflared"
    if [ ! -x "$CF" ]; then
        case "$(uname -s)-$(uname -m)" in
            Linux-x86_64)          ASSET=cloudflared-linux-amd64 ;;
            Linux-aarch64|Linux-arm64) ASSET=cloudflared-linux-arm64 ;;
            Linux-armv7l)          ASSET=cloudflared-linux-arm ;;
            *) echo "Установите cloudflared вручную: https://github.com/cloudflare/cloudflared/releases"; exit 1 ;;
        esac
        mkdir -p "$(dirname "$CF")"
        echo "Скачиваю cloudflared..."
        curl -fsSL "https://github.com/cloudflare/cloudflared/releases/latest/download/$ASSET" -o "$CF"
        chmod +x "$CF"
    fi
fi

LOG="$(mktemp -t cloudflared-lms.XXXXXX)"
"$CF" tunnel --no-autoupdate --url "http://localhost:$PORT" --logfile "$LOG" >/dev/null 2>&1 &
TUNNEL=$!
trap 'kill $TUNNEL 2>/dev/null || true; rm -f "$LOG"' EXIT

URL=""
for _ in $(seq 1 60); do
    sleep 1
    URL="$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$LOG" | head -n1 || true)"
    [ -n "$URL" ] && break
done
if [ -z "$URL" ]; then
    echo "Туннель не поднялся, смотрите $LOG"
    trap - EXIT
    kill $TUNNEL 2>/dev/null || true
    exit 1
fi
# Новому адресу нужно несколько секунд, чтобы его увидел Telegram.
sleep 8
export WEBHOOK_URL="$URL"
echo "Публичный адрес: $URL"
echo "Мини-приложение: $URL/app/"

# Ограничение памяти: куча до 256 МБ, простой сборщик мусора (отдаёт память системе), меньше стеки потоков.
# Без exec: после остановки бота trap закрывает туннель.
java -Xms32m -Xmx256m -XX:+UseSerialGC -Xss512k -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m \
     -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Tashkent -jar "$JAR"
