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
