# Локальный запуск бота вместе с мини-приложением.
# Токен берётся из файла .env рядом со скриптом (он в .gitignore) или из переменной BOT_TOKEN.
# Публичный https-адрес даёт бесплатный туннель Cloudflare (без аккаунта): по нему бот ставит
# webhook и кнопку мини-приложения. Пока бот запущен здесь, сервис на хостинге должен быть выключен.
Set-Location $PSScriptRoot

$envFile = Join-Path $PSScriptRoot ".env"
if (Test-Path $envFile) {
    foreach ($line in Get-Content $envFile -Encoding UTF8) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
            Set-Item "env:$($Matches[1])" $Matches[2].Trim('"')
        }
    }
}
if (-not $env:BOT_TOKEN) {
    Write-Host "Нет BOT_TOKEN: создайте файл .env со строкой BOT_TOKEN=<токен от @BotFather>"
    exit 1
}
if (-not $env:PORT) { $env:PORT = "8080" }
if (-not $env:LMS_DEBUG) { $env:LMS_DEBUG = "true" }

$jar = "build/libs/lms-bot.jar"
if (-not (Test-Path $jar)) {
    Write-Host "Нет $jar — соберите: cd webapp; npm ci; npm run build; cd ..; .\gradlew jar"
    exit 1
}

$cf = Join-Path $env:LOCALAPPDATA "cloudflared\cloudflared.exe"
if (-not (Test-Path $cf)) {
    New-Item -ItemType Directory -Force (Split-Path $cf) | Out-Null
    Write-Host "Скачиваю cloudflared..."
    Invoke-WebRequest "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe" -OutFile $cf
}

$log = Join-Path $env:TEMP "cloudflared-lms.log"
Remove-Item $log -ErrorAction SilentlyContinue
$tunnel = Start-Process $cf -ArgumentList "tunnel --no-autoupdate --url http://localhost:$($env:PORT) --logfile `"$log`"" -PassThru -WindowStyle Hidden

$url = $null
for ($i = 0; $i -lt 60 -and -not $url; $i++) {
    Start-Sleep -Seconds 1
    if (Test-Path $log) {
        $m = Select-String -Path $log -Pattern 'https://[a-z0-9-]+\.trycloudflare\.com' | Select-Object -First 1
        if ($m) { $url = $m.Matches[0].Value }
    }
}
if (-not $url) {
    Write-Host "Туннель не поднялся, смотрите $log"
    Stop-Process $tunnel -ErrorAction SilentlyContinue
    exit 1
}
# Новому адресу нужно несколько секунд, чтобы его увидел Telegram.
Start-Sleep -Seconds 8
$env:WEBHOOK_URL = $url
Write-Host "Публичный адрес: $url"
Write-Host "Мини-приложение: $url/app/"

try {
    # Ограничение памяти: куча до 256 МБ, простой сборщик мусора (отдаёт память системе), меньше стеки потоков.
    # Аргументы -D в кавычках: Windows PowerShell 5.1 иначе режет их по точке.
    java "-Xms32m" "-Xmx256m" "-XX:+UseSerialGC" "-Xss512k" "-XX:MaxMetaspaceSize=128m" "-XX:ReservedCodeCacheSize=48m" "-Dfile.encoding=UTF-8" "-Duser.timezone=Asia/Tashkent" -jar $jar
} finally {
    Stop-Process $tunnel -ErrorAction SilentlyContinue
}
