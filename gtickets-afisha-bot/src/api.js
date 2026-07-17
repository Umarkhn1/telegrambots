// Клиент к публичному API gtickets.uz (реверс-инжиниринг фронтенда).
// Все методы кэшируются на CACHE_TTL_MS, чтобы не долбить бэкенд при пагинации/кликах.
import { API_BASE, AGGREGATOR_ID, CACHE_TTL_MS } from "./config.js";

const HEADERS = {
  "User-Agent":
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36",
  Accept: "application/json",
  Origin: "https://gtickets.uz",
  Referer: "https://gtickets.uz/",
};

const cache = new Map(); // url -> { at, data }

function qs(params) {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === "") continue;
    p.set(k, String(v));
  }
  const s = p.toString();
  return s ? "?" + s : "";
}

async function get(path, params = {}, { ttl = CACHE_TTL_MS } = {}) {
  const url = API_BASE + path + qs(params);
  const now = Date.now();
  const hit = cache.get(url);
  if (hit && now - hit.at < ttl) return hit.data;

  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), 20000);
  try {
    const res = await fetch(url, { headers: HEADERS, signal: ctrl.signal });
    if (!res.ok) throw new Error(`HTTP ${res.status} for ${path}`);
    const data = await res.json();
    cache.set(url, { at: now, data });
    return data;
  } finally {
    clearTimeout(t);
  }
}

// --- Публичные методы ---

// Список городов: [{id, name}]
export function getCities() {
  return get("/api/city", { aggregatorid: AGGREGATOR_ID }, { ttl: 6 * 3600 * 1000 });
}

// Кинотеатры города: [{id, title, address, operating_mode, city, city_id}]
export function getCinemas(cityId) {
  return get("/api/cinema", { aggregatorid: AGGREGATOR_ID, cityid: cityId });
}

// Актуальные даты сеансов: ["YYYY-MM-DD", ...]
export function getDates(cityId, { cinemaId, releaseId } = {}) {
  return get("/api/actualseancedates", {
    aggregatorid: AGGREGATOR_ID,
    cityid: cityId,
    cinemas: cinemaId,
    releaseid: releaseId,
  });
}

// Афиша (список фильмов): { releases: [...] }
export async function getPlaybill(cityId, { date, cinemaId } = {}) {
  const data = await get("/api/playbill3", {
    aggregatorid: AGGREGATOR_ID,
    cityid: cityId,
    date,
    cinemas: cinemaId,
  });
  return Array.isArray(data?.releases) ? data.releases : [];
}

// Детали фильма на дату (включая cinemas[].seances[])
export function getReleaseInfo(releaseId, cityId, date) {
  return get(`/api/releaseinfo2/${releaseId}`, {
    aggregatorid: AGGREGATOR_ID,
    cityid: cityId,
    date,
  });
}

// Свободные места на сеанс: { id, free_places_count }
export function getSeanceInfo(seanceId, cinemaId) {
  return get(
    "/api/seanceinfo",
    { seanceid: seanceId, cinemaid: cinemaId },
    { ttl: 30 * 1000 },
  );
}

export function posterBig(releaseId) {
  return `${API_BASE}/poster/big/${releaseId}`;
}
