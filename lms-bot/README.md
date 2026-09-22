# TUIT LMS Telegram Bot (Java)

Java + Gradle Telegram bot для TUIT LMS (lms.tuit.uz)

## 📁 Структура

```
src/main/java/uz/tuit/lmsbot/
├── Main.java
├── config/
│   └── AppConfig.java          # Загрузка application.yml
├── model/
│   ├── Course.java
│   ├── AttendanceRecord.java
│   ├── Activity.java
│   └── CourseSummary.java
├── service/
│   ├── LmsService.java         # Вся логика LMS API
│   └── InMemoryCookieJar.java
└── bot/
    └── LmsBot.java             # Telegram bot handler
```

## ⚙️ Настройка

### 1. Создайте бота через @BotFather

### 2. Заполните `src/main/resources/application.yml`:
```yaml
bot:
  token: 1234567890:ABCdefGHI...
  username: my_lms_bot
```

### Или через переменные окружения:
```bash
export BOT_TOKEN=1234567890:ABCdefGHI...
export BOT_USERNAME=my_lms_bot
```

## 🚀 Запуск

```bash
# Собрать fat JAR
./gradlew jar

# Запустить
java -jar build/libs/lms-bot.jar

# Или через Gradle
./gradlew run
```

## 📱 Команды бота

| Команда | Описание |
|---------|----------|
| `/start` | Приветствие |
| `/login` | Войти в LMS |
| `/courses` | Мои предметы |
| `/logout` | Выйти |

## 🔧 Функционал

- ✅ Авторизация в LMS (сессия per-user)
- 📚 Список предметов с сортировкой
- 📊 Посещаемость с деталями пропусков
- 📋 Активности/дедлайны с баллами
- 📥 Ссылки на скачивание заданий
- 📅 Переключение между семестрами

## 📱 Mini App

Фронтенд — `webapp/` (React + Vite + framer-motion, иконки lucide). Собирается в
`src/main/resources/webapp` и отдаётся тем же HTTP-сервером, что health-check (`$PORT`):

| Путь | Что |
|------|-----|
| `/` | health-check |
| `/app/` | мини-приложение |
| `/api/*` | JSON API; пользователь — из подписанной `initData` Telegram |

Приложение работает в той же LMS-сессии, что и бот: вход в боте = вход в приложении и наоборот.
Кнопка меню бота ставится автоматически, если есть `WEBAPP_URL` или `RENDER_EXTERNAL_URL`; команда `/app` шлёт кнопку запуска.

```bash
cd webapp && npm ci && npm run build   # затем ./gradlew jar
```

Docker собирает фронтенд сам. Для отладки API без Telegram: `WEBAPP_DEV_USER=<telegram id>` (только локально!).

## 🔁 Сессии и сон хостинга

Бесплатный сервис Render засыпает без входящих запросов, а диск стирается при каждом
деплое и пробуждении. Чтобы вход не приходилось повторять, работают три вещи.

**Webhook.** Апдейт приходит обычным POST на `/tg/<хэш токена>`, поэтому сообщение
пользователя само будит уснувший сервис — при long polling спящий бот просто не
забирает апдейты и молчит, пока его не разбудит кто-то извне. Включается сам, если
известен публичный https-адрес (`WEBHOOK_URL` или `RENDER_EXTERNAL_URL`) и задан `$PORT`.
Путь и заголовок `X-Telegram-Bot-Api-Secret-Token` выводятся из токена бота.

**Токен OneID.** После входа через OneID бот хранит выданный JWT (зашифрован AES-GCM,
ключ из `STATE_KEY`, иначе из токена бота). Когда сессия LMS истекает, бот сам проходит
`sso/v1/generate` → callback и получает новую — пароль нигде не сохраняется и второй
фактор не запрашивается. `/logout` токен стирает.

**Touch LMS.** Раз в 25 минут бот делает по одному запросу за каждого вошедшего: время
простоя сессии обнуляется, и она не истекает вовсе. Работает, только пока сервис не спит.

| Переменная | Что делает |
|------------|-----------|
| `WEBHOOK_URL` | Публичный https-адрес сервиса; на Render берётся `RENDER_EXTERNAL_URL` |
| `WEBHOOK=off` | Принудительно вернуть long polling |
| `STATE_KEY` | Ключ шифрования токенов OneID и резервной копии состояния |
| `KEEPALIVE_URL` | Что пинговать, чтобы сервис не уснул |
| `KEEPALIVE_HOURS` | Окно активности, напр. `7-1`; вне окна сервис засыпает — тогда touch не работает и сессию поднимает токен OneID |

Снаружи стоит добавить пингер (UptimeRobot, cron-job.org) раз в 10 минут: разбудить
уже уснувший сервис изнутри нельзя.

