// Построение inline-клавиатур. Все ряды строятся через chunk() — ровная сетка, без «кривых» рядов.
import { InlineKeyboard } from "grammy";
import { dateButton } from "./format.js";

function chunk(arr, n) {
  const out = [];
  for (let i = 0; i < arr.length; i += n) out.push(arr.slice(i, i + n));
  return out;
}

// Добавить кнопки сеткой по perRow в ряд. items: [{label, data}]
function addGrid(kb, items, perRow) {
  for (const row of chunk(items, perRow)) {
    for (const b of row) kb.text(b.label, b.data);
    kb.row();
  }
}

// Ряд навигации пагинации (без «Меню» — footer добавляется отдельно).
function addPager(kb, page, pages, prefix) {
  if (pages <= 1) return;
  const nav = [];
  if (page > 0) nav.push({ label: "◀️", data: `${prefix}${page - 1}` });
  nav.push({ label: `${page + 1} / ${pages}`, data: "noop" });
  if (page < pages - 1) nav.push({ label: "▶️", data: `${prefix}${page + 1}` });
  for (const b of nav) kb.text(b.label, b.data);
  kb.row();
}

function clampPage(page, total, size) {
  const pages = Math.max(1, Math.ceil(total / size));
  return { p: Math.min(Math.max(0, page || 0), pages - 1), pages };
}

function trunc(s, n) {
  return s.length > n ? s.slice(0, n - 1) + "…" : s;
}

export function mainMenuKb(user, cityNm) {
  const on = user?.notify !== false;
  return new InlineKeyboard()
    .text("🎬 Афиша", "af:0")
    .text("📅 Дата", "afd")
    .row()
    .text("🎦 Кинотеатры", "cin:0")
    .row()
    .text(`🏙 ${trunc(cityNm, 14)}`, "city")
    .text(on ? "🔔 Вкл" : "🔕 Выкл", "ntf")
    .row()
    .text("ℹ️ О боте", "about");
}

export function citiesKb(cities, currentId) {
  const kb = new InlineKeyboard();
  addGrid(
    kb,
    cities.map((c) => ({
      label: (c.id === currentId ? "✅ " : "") + c.name,
      data: `city:${c.id}`,
    })),
    2,
  );
  kb.text("◀️ Меню", "menu");
  return kb;
}

// Список фильмов (по одному в ряд) + пагинация. cbPrefix, напр. "af:" или "cinm:361:".
export function moviesKb(releases, page, pageSize, cbPrefix, backCb) {
  const kb = new InlineKeyboard();
  const { p, pages } = clampPage(page, releases.length, pageSize);
  for (const r of releases.slice(p * pageSize, p * pageSize + pageSize)) {
    const fire = (r.qualifiers || []).includes("premiere") ? "🔥 " : "";
    const age = r.age_rating ? ` · ${r.age_rating}` : "";
    kb.text(`${fire}${trunc(r.title, 30)}${age}`, `m:${r.id}`).row();
  }
  addPager(kb, p, pages, cbPrefix);
  kb.text("📅 Дата", "afd").text("◀️ Меню", backCb || "menu");
  return kb;
}

export function cinemasKb(cinemas, page, pageSize) {
  const kb = new InlineKeyboard();
  const { p, pages } = clampPage(page, cinemas.length, pageSize);
  for (const c of cinemas.slice(p * pageSize, p * pageSize + pageSize)) {
    kb.text(`🎦 ${trunc(c.title, 38)}`, `cinm:${c.id}:0`).row();
  }
  addPager(kb, p, pages, "cin:");
  kb.text("◀️ Меню", "menu");
  return kb;
}

// Даты афиши — компактно 3 в ряд, один footer.
export function afishaDatesKb(dates, todayIso, tomorrowIso, currentIso) {
  const kb = new InlineKeyboard();
  addGrid(
    kb,
    dates.map((iso) => ({
      label: (iso === currentIso ? "✅ " : "") + dateButton(iso, todayIso, tomorrowIso),
      data: `afd:${iso}`,
    })),
    3,
  );
  kb.text("◀️ Меню", "menu");
  return kb;
}

// Даты в карточке фильма — 3 в ряд.
export function movieDatesKb(rid, dates, todayIso, tomorrowIso) {
  const kb = new InlineKeyboard();
  addGrid(
    kb,
    dates.slice(0, 6).map((iso) => ({
      label: dateButton(iso, todayIso, tomorrowIso),
      data: `md:${rid}:${iso}:0`,
    })),
    3,
  );
  kb.text("◀️ Афиша", "af:0");
  return kb;
}

// Кинотеатры, где идёт фильм на дату — по одному в ряд + пагинация.
export function movieCinemasKb(rid, iso, cinemas, page, pageSize = 6) {
  const kb = new InlineKeyboard();
  const { p, pages } = clampPage(page, cinemas.length, pageSize);
  for (const c of cinemas.slice(p * pageSize, p * pageSize + pageSize)) {
    const n = c.seances?.length || 0;
    kb.text(`🎦 ${trunc(c.title, 26)} · ${n}🎟`, `sc:${rid}:${iso}:${c.id}`).row();
  }
  addPager(kb, p, pages, `md:${rid}:${iso}:`);
  kb.text("◀️ К фильму", `m:${rid}`);
  return kb;
}

export function scheduleKb(rid, iso, buyUrl) {
  const kb = new InlineKeyboard();
  if (buyUrl) kb.url("🎟 Купить билет", buyUrl).row();
  kb.text("◀️ Кинотеатры", `md:${rid}:${iso}:0`).text("🎬 Афиша", "af:0");
  return kb;
}

export function notifyKb(notifyOn) {
  return new InlineKeyboard()
    .text(notifyOn ? "🔕 Выключить" : "🔔 Включить", notifyOn ? "ntf:0" : "ntf:1")
    .row()
    .text("◀️ Меню", "menu");
}
