// Конфигурация бота.
// Токен задаётся через переменную окружения BOT_TOKEN.
export const BOT_TOKEN = process.env.BOT_TOKEN;
if (!BOT_TOKEN) {
  console.error("Не задана переменная окружения BOT_TOKEN");
  process.exit(1);
}

export const SITE = "https://zaka-zaka.com";

// TTL кэша листингов/поиска (мс).
export const CACHE_TTL_MS = 2 * 60 * 1000;

// Интервал проверки скидок для вишлистов (мс).
export const POLL_INTERVAL_MS = Number(process.env.POLL_INTERVAL_MS) || 30 * 60 * 1000;

// Обновление курса валют (мс).
export const FX_TTL_MS = 6 * 3600 * 1000;

export const DATA_DIR = new URL("./data/", import.meta.url).pathname;

// Разделы каталога: постраничная подгрузка с сайта (по 10 карточек на страницу).
// onlySale — показывать только карточки со скидкой.
export const CATEGORIES = [
  { key: "new", emoji: "🆕", title: "Новинки", path: "/game/new/page" },
  { key: "popular", emoji: "⭐", title: "Популярные", path: "/search/page" },
  { key: "preorder", emoji: "⏳", title: "Ожидаемые", path: "/game/preorder/page" },
  { key: "sale", emoji: "🔥", title: "Все скидки", path: "/game/sale/page", onlySale: true },
];

export function categoryByKey(key) {
  return CATEGORIES.find((c) => c.key === key);
}
