// Persistent JSON-хранилище: пользователи, вишлисты, состояние скидок.
import { readFileSync, writeFileSync, renameSync, mkdirSync, existsSync } from "node:fs";
import { DATA_DIR } from "./config.js";

if (!existsSync(DATA_DIR)) mkdirSync(DATA_DIR, { recursive: true });

function load(name, fallback) {
  try {
    return JSON.parse(readFileSync(DATA_DIR + name, "utf8"));
  } catch {
    return fallback;
  }
}
function save(name, obj) {
  const p = DATA_DIR + name;
  writeFileSync(p + ".tmp", JSON.stringify(obj));
  renameSync(p + ".tmp", p);
}

// users: { [chatId]: { notify, wishlist: [{slug,name}] } }
// sales: { [slug]: lastSalePercent }  — глобальное состояние для детекта улучшений
let users = load("users.json", {});
let sales = load("sales.json", {});

// Реестр slug<->id: длинные slug в callback_data не влезают (лимит 64 байта),
// поэтому в кнопках используем короткий числовой id.
let slugmap = load("slugmap.json", { slugs: [] });
const slugIndex = new Map(slugmap.slugs.map((s, i) => [s, i]));
export function slugId(slug) {
  let i = slugIndex.get(slug);
  if (i === undefined) {
    i = slugmap.slugs.length;
    slugmap.slugs.push(slug);
    slugIndex.set(slug, i);
    save("slugmap.json", slugmap);
  }
  return i;
}
export function slugById(id) {
  return slugmap.slugs[id];
}

export function getUser(chatId) {
  return users[String(chatId)];
}
export function ensureUser(chatId) {
  const id = String(chatId);
  if (!users[id]) {
    users[id] = { notify: true, wishlist: [] };
    save("users.json", users);
  }
  return users[id];
}
export function setNotify(chatId, notify) {
  const u = ensureUser(chatId);
  u.notify = notify;
  save("users.json", users);
  return u;
}

export function inWishlist(chatId, slug) {
  const u = getUser(chatId);
  return !!u?.wishlist?.some((g) => g.slug === slug);
}
export function addWishlist(chatId, game) {
  const u = ensureUser(chatId);
  if (!u.wishlist.some((g) => g.slug === game.slug)) {
    u.wishlist.push({ slug: game.slug, name: game.name });
    save("users.json", users);
  }
  return u;
}
export function removeWishlist(chatId, slug) {
  const u = ensureUser(chatId);
  u.wishlist = u.wishlist.filter((g) => g.slug !== slug);
  save("users.json", users);
  return u;
}
export function wishlist(chatId) {
  return getUser(chatId)?.wishlist || [];
}

// Все уникальные slug из вишлистов всех пользователей.
export function allWishedSlugs() {
  const set = new Map(); // slug -> name
  for (const u of Object.values(users)) {
    for (const g of u.wishlist || []) if (!set.has(g.slug)) set.set(g.slug, g.name);
  }
  return [...set.entries()].map(([slug, name]) => ({ slug, name }));
}

// Кто хочет уведомления по данному slug.
export function subscribersForSlug(slug) {
  return Object.entries(users)
    .filter(([, u]) => u.notify && (u.wishlist || []).some((g) => g.slug === slug))
    .map(([id]) => Number(id));
}

export function getLastSale(slug) {
  return sales[slug];
}
export function setLastSale(slug, percent) {
  sales[slug] = percent;
  save("sales.json", sales);
}
export function setLastSaleBulk(map) {
  Object.assign(sales, map);
  save("sales.json", sales);
}
export function hasSaleState(slug) {
  return Object.prototype.hasOwnProperty.call(sales, slug);
}
