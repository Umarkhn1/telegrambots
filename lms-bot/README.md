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
