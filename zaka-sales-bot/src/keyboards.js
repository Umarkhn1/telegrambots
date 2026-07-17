// Inline-клавиатуры: ровная сетка, пагинация, минимум кнопок.
import { InlineKeyboard } from "grammy";
import { CATEGORIES } from "./config.js";
import { slugId } from "./store.js";
import { priceTag } from "./format.js";

function chunk(a, n) {
  const o = [];
  for (let i = 0; i < a.length; i += n) o.push(a.slice(i, i + n));
  return o;
}
function grid(kb, items, perRow) {
  for (const row of chunk(items, perRow)) {
    for (const b of row) kb.text(b.label, b.data);
    kb.row();
  }
}
function pager(kb, page, pages, prefix) {
  if (pages <= 1) return;
  if (page > 0) kb.text("◀️", `${prefix}${page - 1}`);
  kb.text(`${page + 1} / ${pages}`, "noop");
  if (page < pages - 1) kb.text("▶️", `${prefix}${page + 1}`);
  kb.row();
}
function trunc(s, n) {
  return s.length > n ? s.slice(0, n - 1) + "…" : s;
}

export function mainMenuKb(user) {
  const on = user?.notify !== false;
  return new InlineKeyboard()
    .text("🔎 Поиск игры", "search")
    .row()
    .text("🎮 Каталог", "cats")
    .text("⭐ Вишлист", "wl:0")
    .row()
    .text("🔥 Все скидки", "cat:sale:0")
    .row()
    .text(on ? "🔔 Уведомления: вкл" : "🔕 Уведомления: выкл", "ntf");
}

export function categoriesKb() {
  const kb = new InlineKeyboard();
  grid(
    kb,
    CATEGORIES.map((c) => ({ label: `${c.emoji} ${c.title}`, data: `cat:${c.key}:0` })),
    2,
  );
  kb.text("◀️ Меню", "menu");
  return kb;
}

// Список игр из результатов поиска (клиентская пагинация по массиву).
export function dealsKb(items, page, pageSize, cbPrefix, backCb, rate) {
  const kb = new InlineKeyboard();
  const pages = Math.max(1, Math.ceil(items.length / pageSize));
  const p = Math.min(Math.max(0, page), pages - 1);
  for (const g of items.slice(p * pageSize, p * pageSize + pageSize)) {
    const tag = priceTag(g, rate);
    kb.text(`${trunc(g.name, 30)}${tag ? " · " + tag : ""}`, `g:${slugId(g.slug)}`).row();
  }
  pager(kb, p, pages, cbPrefix);
  kb.text("◀️ Назад", backCb);
  return kb;
}

// Список игр раздела каталога (серверная пагинация: page 0-based, maxPage 1-based).
export function sectionKb(cards, page, maxPage, cbPrefix, backCb, rate) {
  const kb = new InlineKeyboard();
  for (const g of cards) {
    const tag = priceTag(g, rate);
    kb.text(`${g.sale > 0 ? "🔥 " : ""}${trunc(g.name, 30)}${tag ? " · " + tag : ""}`, `g:${slugId(g.slug)}`).row();
  }
  if (page > 0) kb.text("◀️", `${cbPrefix}${page - 1}`);
  kb.text(`${page + 1} / ${maxPage}`, "noop");
  if (page + 1 < maxPage) kb.text("▶️", `${cbPrefix}${page + 1}`);
  kb.row();
  kb.text("◀️ Назад", backCb);
  return kb;
}

// Клавиатура карточки игры.
export function gameKb(slug, wished, backCb) {
  const id = slugId(slug);
  const kb = new InlineKeyboard();
  kb.text(wished ? "➖ Убрать из вишлиста" : "⭐ В вишлист", `w:${id}`).row();
  kb.text("◀️ Назад", backCb || "menu");
  return kb;
}

// Вишлист -> кнопки игр + пагинация.
export function wishlistKb(items, page, pageSize, rate) {
  const kb = new InlineKeyboard();
  const pages = Math.max(1, Math.ceil(items.length / pageSize));
  const p = Math.min(Math.max(0, page), pages - 1);
  for (const g of items.slice(p * pageSize, p * pageSize + pageSize)) {
    const tag = g.priceRub != null ? priceTag(g, rate) : "";
    kb.text(`${g.sale > 0 ? "🔥 " : ""}${trunc(g.name, 30)}${tag ? " · " + tag : ""}`, `g:${slugId(g.slug)}`).row();
  }
  pager(kb, p, pages, "wl:");
  kb.text("◀️ Меню", "menu");
  return kb;
}

export function notifyKb(on) {
  return new InlineKeyboard()
    .text(on ? "🔕 Выключить" : "🔔 Включить", on ? "ntf:0" : "ntf:1")
    .row()
    .text("◀️ Меню", "menu");
}

export function backKb(cb) {
  return new InlineKeyboard().text("◀️ Меню", cb || "menu");
}
