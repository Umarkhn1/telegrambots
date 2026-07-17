// Форматирование сообщений (HTML). Валюта — USD.
import { rubToUsd } from "./api.js";

export function esc(s) {
  return String(s ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

const PLAT = {
  steam: "Steam",
  windows: "Windows",
  win: "Windows",
  xbox: "Xbox",
  playstation: "PlayStation",
  ps: "PlayStation",
  mac: "macOS",
  apple: "macOS",
  linux: "Linux",
  origin: "Origin",
  uplay: "Uplay",
  ubisoft: "Uplay",
  epic: "Epic",
  gog: "GOG",
  battlenet: "Battle.net",
  rockstar: "Rockstar",
  other: null,
};
export function platforms(list) {
  const out = [];
  for (const p of list || []) {
    const label = PLAT[p];
    if (label && !out.includes(label)) out.push(label);
  }
  return out;
}

export function usd(rub, rate) {
  const v = rubToUsd(rub, rate);
  if (v == null) return null;
  return "$" + (v < 10 ? v.toFixed(2) : Math.round(v).toString());
}

// Старая цена (до скидки) из текущей цены и процента.
function oldUsd(rub, sale, rate) {
  if (!rub || !sale || sale <= 0) return null;
  const old = rub / (1 - sale / 100);
  return usd(old, rate);
}

// Короткая строка цены для кнопки.
export function priceTag(g, rate) {
  const p = usd(g.priceRub, rate);
  if (!p) return "";
  return g.sale > 0 ? `−${g.sale}% · ${p}` : p;
}

// Карточка игры (для фото-сообщения). g: результат поиска.
export function gameCard(g, rate, wished) {
  const L = [];
  L.push(`🎮 <b>${esc(g.name)}</b>`);

  const meta = [];
  if (g.year) meta.push(`📅 ${esc(g.year)}`);
  if (g.genres) meta.push(`🏷 ${esc(g.genres)}`);
  const plats = platforms(g.plats?.length ? g.plats : [g.drm]);
  if (plats.length) meta.push(`🖥 ${esc(plats.join(", "))}`);
  if (meta.length) L.push(`<blockquote>${meta.join("\n")}</blockquote>`);

  const price = usd(g.priceRub, rate);
  const old = oldUsd(g.priceRub, g.sale, rate);
  const pr = [];
  if (g.sale > 0) {
    pr.push(`🔥 Скидка <b>−${g.sale}%</b>`);
    if (old) pr.push(`<s>${old}</s>  ➜  <b>${price}</b>`);
    else if (price) pr.push(`💵 <b>${price}</b>`);
  } else if (price) {
    pr.push(`💵 <b>${price}</b>`);
  } else {
    pr.push("💵 цена уточняется");
  }
  L.push(`<blockquote>${pr.join("\n")}</blockquote>`);

  L.push(wished ? "⭐️ В вашем вишлисте" : "☆ Не в вишлисте");
  return L.join("\n");
}

// Строка сделки в списке скидок (внутри общего blockquote).
export function dealLine(c, rate) {
  const price = usd(c.priceRub, rate);
  const old = oldUsd(c.priceRub, c.sale, rate);
  const bits = [`<b>${esc(c.name)}</b>`];
  const p = [];
  if (c.sale > 0) p.push(`−${c.sale}%`);
  if (old && price) p.push(`<s>${old}</s>→<b>${price}</b>`);
  else if (price) p.push(`<b>${price}</b>`);
  return bits.join("") + (p.length ? "\n   " + p.join(" · ") : "");
}

// Уведомление о новой/большей скидке.
export function dealAlert(g, rate, prevSale) {
  const price = usd(g.priceRub, rate);
  const old = oldUsd(g.priceRub, g.sale, rate);
  const head =
    prevSale > 0
      ? `📉 <b>Скидка выросла!</b> (было −${prevSale}%)`
      : `🔥 <b>Новая скидка на игру из вишлиста!</b>`;
  const L = [head, "", `🎮 <b>${esc(g.name)}</b>`];
  const pr = [`Скидка <b>−${g.sale}%</b>`];
  if (old && price) pr.push(`<s>${old}</s>  ➜  <b>${price}</b>`);
  else if (price) pr.push(`💵 <b>${price}</b>`);
  L.push(`<blockquote>${pr.join("\n")}</blockquote>`);
  return L.join("\n");
}
