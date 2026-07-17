// Форматирование текстов сообщений (HTML parse mode).

const WEEKDAYS = ["вс", "пн", "вт", "ср", "чт", "пт", "сб"];
const MONTHS = [
  "января", "февраля", "марта", "апреля", "мая", "июня",
  "июля", "августа", "сентября", "октября", "ноября", "декабря",
];

export function esc(s) {
  return String(s ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

// "2026-07-11" -> { short: "11 июля, пт", weekday: "пт", isToday, isTomorrow }
export function parseDate(iso, todayIso) {
  const [y, m, d] = iso.split("-").map(Number);
  const dt = new Date(Date.UTC(y, m - 1, d));
  const wd = WEEKDAYS[dt.getUTCDay()];
  const short = `${d} ${MONTHS[m - 1]}, ${wd}`;
  let label = short;
  if (iso === todayIso) label = "сегодня";
  return { iso, short, weekday: wd, label };
}

// Короткая метка для кнопки даты.
export function dateButton(iso, todayIso, tomorrowIso) {
  const [, m, d] = iso.split("-").map(Number);
  if (iso === todayIso) return "Сегодня";
  if (iso === tomorrowIso) return "Завтра";
  const p = parseDate(iso);
  return `${d} ${MONTHS[m - 1].slice(0, 3)}, ${p.weekday}`;
}

export function ageBadge(r) {
  return r.age_rating ? ` <code>${esc(r.age_rating)}</code>` : "";
}

export function qualifierBadges(r) {
  const q = r.qualifiers || [];
  const out = [];
  if (q.includes("premiere")) out.push("🔥 Премьера");
  if (q.includes("for_kids")) out.push("🧸 Детям");
  return out.length ? "  " + out.join(" · ") : "";
}

// Строка одного фильма в списке афиши.
export function movieListLine(r) {
  const genres = (r.genres || []).join(", ").trim();
  const fmt = (r.formats || []).join("/");
  const seances = r.cinema_seances || {};
  const parts = [`<b>${esc(r.title)}</b>${ageBadge(r)}`];
  const meta = [];
  if (fmt) meta.push(fmt);
  if (r.rating) meta.push(`⭐ ${esc(r.rating)}`);
  if (seances.seances_count)
    meta.push(`🎟 ${seances.seances_count} сеан. в ${seances.cinema_count} к/т`);
  if (meta.length) parts.push(meta.join(" · "));
  if (genres) parts.push(`<i>${esc(genres)}</i>`);
  return parts.join("\n");
}

// Полная карточка фильма (для releaseinfo2).
export function movieCard(info) {
  const L = [];
  const q = qualifierBadges(info).trim();
  L.push(`🎬 <b>${esc(info.title)}</b>${ageBadge(info)}${q ? "\n" + q : ""}`);

  const meta = [];
  if (info.genres?.length) meta.push(`🎭 ${esc(info.genres.join(", ").trim())}`);
  const line2 = [];
  if (info.year) line2.push(`📅 ${info.year}`);
  if (info.duration) line2.push(`⏱ ${info.duration} мин`);
  if (info.countries?.length) line2.push(`🌍 ${esc(info.countries.join(", "))}`);
  if (line2.length) meta.push(line2.join(" · "));
  const line3 = [];
  if (info.formats?.length) line3.push(`🖥 ${esc(info.formats.join(", "))}`);
  if (info.rating) line3.push(`⭐ ${esc(info.rating)}`);
  if (line3.length) meta.push(line3.join(" · "));
  if (info.directors?.length) meta.push(`🎬 <i>${esc(info.directors.join(", "))}</i>`);
  if (info.cast?.length) meta.push(`🎭 <i>${esc(info.cast.slice(0, 5).join(", "))}</i>`);
  if (meta.length) L.push(`<blockquote>${meta.join("\n")}</blockquote>`);

  if (info.description) {
    let d = info.description.trim();
    if (d.length > 500) d = d.slice(0, 497) + "…";
    L.push(`<blockquote expandable>${esc(d)}</blockquote>`);
  }
  return L.join("\n");
}

// Строка сеанса с ценой и свободными местами.
export function seanceLine(s, freePlaces) {
  const time = s.datetime?.time || "--:--";
  const hall = s.hall?.title ? esc(s.hall.title) : "зал";
  const vip = s.hall?.is_vip ? " 👑" : "";
  const fmt = (s.formats || []).join("/");
  const price =
    s.price?.min_text != null
      ? s.price.min_text === s.price.max_text
        ? `${s.price.min_text} сум`
        : `${s.price.min_text}–${s.price.max_text} сум`
      : "";
  let places = "";
  if (freePlaces != null) {
    const icon = freePlaces === 0 ? "🔴" : freePlaces <= 10 ? "🟡" : "🟢";
    places = freePlaces === 0 ? ` ${icon} мест нет` : ` ${icon} ${freePlaces} мест`;
  }
  const bits = [`🕐 <b>${time}</b>`, hall + vip];
  if (fmt) bits.push(fmt);
  if (price) bits.push(`💰 ${price}`);
  return bits.join(" · ") + places;
}
