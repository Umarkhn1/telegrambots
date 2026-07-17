// Простое persistent-хранилище на JSON-файлах с атомарной записью.
// users.json: { [chatId]: { cityId, notify, seenAt } }
// seen.json:  { [cityId]: [releaseId, ...] }  — какие фильмы бот уже "видел" (для детекта новинок)
import { readFileSync, writeFileSync, renameSync, mkdirSync, existsSync } from "node:fs";
import { DATA_DIR } from "./config.js";

if (!existsSync(DATA_DIR)) mkdirSync(DATA_DIR, { recursive: true });

function load(name, fallback) {
  const p = DATA_DIR + name;
  try {
    return JSON.parse(readFileSync(p, "utf8"));
  } catch {
    return fallback;
  }
}

function save(name, obj) {
  const p = DATA_DIR + name;
  const tmp = p + ".tmp";
  writeFileSync(tmp, JSON.stringify(obj, null, 2));
  renameSync(tmp, p); // атомарная замена
}

let users = load("users.json", {});
let seen = load("seen.json", {});

// --- Пользователи ---
export function getUser(chatId) {
  return users[String(chatId)];
}

export function upsertUser(chatId, patch) {
  const id = String(chatId);
  users[id] = { cityId: null, notify: true, ...users[id], ...patch };
  save("users.json", users);
  return users[id];
}

export function setCity(chatId, cityId) {
  return upsertUser(chatId, { cityId });
}

export function setNotify(chatId, notify) {
  return upsertUser(chatId, { notify });
}

// Все подписчики уведомлений для заданного города.
export function subscribers(cityId) {
  return Object.entries(users)
    .filter(([, u]) => u.notify && u.cityId === cityId)
    .map(([id]) => Number(id));
}

// Города, где есть хотя бы один активный подписчик.
export function citiesWithSubscribers() {
  const set = new Set();
  for (const u of Object.values(users)) {
    if (u.notify && u.cityId != null) set.add(u.cityId);
  }
  return [...set];
}

export function allUserCount() {
  return Object.keys(users).length;
}

// --- Множество "виденных" релизов по городам ---
export function getSeen(cityId) {
  return new Set(seen[String(cityId)] || []);
}

export function saveSeen(cityId, idSet) {
  seen[String(cityId)] = [...idSet];
  save("seen.json", seen);
}

export function hasSeenInit(cityId) {
  return Array.isArray(seen[String(cityId)]);
}
