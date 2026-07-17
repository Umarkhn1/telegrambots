// Клиент к zaka-zaka: JSON-поиск, HTML-листинги скидок, курс валют.
import { SITE, CACHE_TTL_MS, FX_TTL_MS } from "./config.js";

const HEADERS = {
  "User-Agent":
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36",
  "X-Requested-With": "XMLHttpRequest",
  Accept: "text/html,application/json,*/*",
  Referer: SITE + "/",
};

const cache = new Map();

async function fetchText(url, ttl = CACHE_TTL_MS) {
  const now = Date.now();
  const hit = cache.get(url);
  if (hit && now - hit.at < ttl) return hit.data;
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), 20000);
  try {
    const res = await fetch(url, { headers: HEADERS, signal: ctrl.signal });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.text();
    cache.set(url, { at: now, data });
    return data;
  } finally {
    clearTimeout(t);
  }
}

// Цена из строки вида '1399 <i class="rouble">c</i>' -> число рублей.
function priceRub(s) {
  if (s == null) return null;
  const m = String(s).match(/([\d\s]+)/);
  if (!m) return null;
  const n = Number(m[1].replace(/\s/g, ""));
  return Number.isFinite(n) && n > 0 ? n : null;
}

// --- Поиск (JSON) ---
// -> [{ slug, name, priceRub, sale, image, year, genres, drm }]
export async function search(query) {
  const url = `${SITE}/search/ajax/?game=${encodeURIComponent(query)}`;
  let arr;
  try {
    arr = JSON.parse(await fetchText(url));
  } catch {
    return [];
  }
  if (!Array.isArray(arr)) return [];
  return arr
    .filter((g) => g && g.type === "game" && g.url)
    .map((g) => ({
      slug: g.url,
      name: g.name,
      priceRub: priceRub(g.price),
      sale: g.sale ? Number(g.sale) : 0,
      image: g.image ? SITE + g.image : null,
      year: g.year || null,
      genres: g.genres || null,
      drm: g.drm || null,
    }));
}

// Найти игру по slug (через поиск по названию).
export async function lookupBySlug(slug, name) {
  const q = (name || slug.replace(/-/g, " ")).split(/\s+/).slice(0, 4).join(" ");
  const res = await search(q);
  return res.find((g) => g.slug === slug) || null;
}

// Декодирование HTML-сущностей в текстовых полях карточек.
function decode(s) {
  return String(s)
    .replace(/&nbsp;/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
    .replace(/&laquo;/g, "«")
    .replace(/&raquo;/g, "»")
    .replace(/&mdash;/g, "—")
    .replace(/&ndash;/g, "–")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/\s+/g, " ")
    .trim();
}

// --- Парсер HTML-карточек (game-block) ---
function parseCards(html) {
  const cards = [];
  const re = /<a class="game-block[^"]*" href="([^"]+)">([\s\S]*?)<\/a>/g;
  let m;
  while ((m = re.exec(html))) {
    const href = m[1];
    const body = m[2];
    const slug = (href.match(/\/game\/([a-z0-9-]+)/) || [])[1];
    const name = (body.match(/game-block-name">([^<]+)/) || [])[1];
    if (!slug || !name) continue;
    const desc = ((body.match(/game-block-desc">([^<]*)/) || [])[1] || "").trim();
    const disc = (body.match(/game-block-discount">-?(\d+)%/) || [])[1];
    const price = (body.match(/game-block-price">\s*([\d\s]+)/) || [])[1];
    const plats = (body.match(/game-block-icons"[\s\S]*?<\/div>/) || [""])[0]
      .match(/<span class="(\w+)">/g)
      ?.map((s) => s.match(/"(\w+)"/)[1]) || [];
    const img = (body.match(/game-block-image"[^>]*url\(([^)]+)\)/) || [])[1];
    // desc = "2010, Экшен, Приключения" -> year + genres
    const ym = desc.match(/^(\d{4})\s*,?\s*(.*)$/);
    cards.push({
      slug,
      name: decode(name),
      desc: decode(desc),
      year: ym ? ym[1] : null,
      genres: ym ? decode(ym[2]) : decode(desc) || null,
      sale: disc ? Number(disc) : 0,
      priceRub: price ? Number(price.replace(/\s/g, "")) : null,
      plats,
      image: img ? (img.startsWith("http") ? img : SITE + img) : null,
    });
  }
  return cards;
}

// Одна страница раздела каталога (по 10 карточек). page — 1-based.
// -> { cards, maxPage }. Если category.onlySale — только карточки со скидкой.
export async function listSection(category, page) {
  const url = SITE + category.path + page;
  let html;
  try {
    html = await fetchText(url, 5 * 60 * 1000);
  } catch {
    return { cards: [], maxPage: page };
  }
  let cards = parseCards(html);
  if (category.onlySale) cards = cards.filter((c) => c.sale > 0);
  // Максимум страниц из ссылок пагинации (fallback: если пришла полная страница — есть ещё).
  const pageNums = [...html.matchAll(/page(\d+)/g)].map((m) => Number(m[1]));
  let maxPage = pageNums.length ? Math.max(...pageNums) : 1;
  if (maxPage <= page && cards.length >= 10) maxPage = page + 1; // эвристика «есть ещё»
  return { cards, maxPage };
}

// --- Курс валют RUB per 1 USD ---
let fx = { rate: 90, at: 0 };
export async function usdRate() {
  const now = Date.now();
  if (now - fx.at < FX_TTL_MS && fx.at) return fx.rate;
  const sources = [
    { url: "https://open.er-api.com/v6/latest/USD", pick: (j) => j?.rates?.RUB },
    {
      url: "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/usd.json",
      pick: (j) => j?.usd?.rub,
    },
  ];
  for (const s of sources) {
    try {
      const r = await fetch(s.url, { signal: AbortSignal.timeout(15000) });
      const j = await r.json();
      const rate = s.pick(j);
      if (rate && rate > 0) {
        fx = { rate, at: now };
        return rate;
      }
    } catch {}
  }
  return fx.rate; // последнее известное (или дефолт)
}

export function rubToUsd(rub, rate) {
  if (rub == null || !rate) return null;
  return rub / rate;
}
