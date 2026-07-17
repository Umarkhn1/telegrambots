// Конфигурация бота АФИША (gtickets.uz)
// Токен задаётся через переменную окружения BOT_TOKEN.
export const BOT_TOKEN = process.env.BOT_TOKEN;
if (!BOT_TOKEN) {
  console.error("Не задана переменная окружения BOT_TOKEN");
  process.exit(1);
}

// Базовый URL бэкенда gtickets и агрегатор кино.
export const API_BASE = "https://api.gtickets.uz";
export const AGGREGATOR_ID = 6; // 6 = кинотеатры

// Город по умолчанию (Ташкент).
export const DEFAULT_CITY_ID = 1;

// Как часто опрашивать афишу на предмет новинок (мс).
export const POLL_INTERVAL_MS = Number(process.env.POLL_INTERVAL_MS) || 10 * 60 * 1000;

// TTL кэша данных API (мс) — чтобы не бить бэкенд на каждый клик.
export const CACHE_TTL_MS = 90 * 1000;

// Путь к файлам состояния.
export const DATA_DIR = new URL("./data/", import.meta.url).pathname;
