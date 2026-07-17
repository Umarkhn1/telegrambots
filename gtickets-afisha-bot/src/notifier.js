// Фоновый опрос афиши: детект новых фильмов и рассылка подписчикам.
import { InlineKeyboard } from "grammy";
import * as api from "./api.js";
import * as store from "./store.js";
import * as fmt from "./format.js";
import { POLL_INTERVAL_MS } from "./config.js";

async function checkCity(bot, cityId) {
  let releases;
  try {
    releases = await api.getPlaybill(cityId);
  } catch (e) {
    console.error(`[notifier] city ${cityId} fetch failed:`, e.message);
    return;
  }
  const currentIds = new Set(releases.map((r) => r.id));

  // Первый прогон для города — просто запоминаем текущий набор, без рассылки.
  if (!store.hasSeenInit(cityId)) {
    store.saveSeen(cityId, currentIds);
    console.log(`[notifier] seeded city ${cityId} with ${currentIds.size} movies`);
    return;
  }

  const seen = store.getSeen(cityId);
  const fresh = releases.filter((r) => !seen.has(r.id));
  if (!fresh.length) return;

  const subs = store.subscribers(cityId);
  const cityName = await api
    .getCities()
    .then((cs) => cs.find((c) => c.id === cityId)?.name || "")
    .catch(() => "");

  console.log(`[notifier] city ${cityId}: ${fresh.length} new movie(s), ${subs.length} subscriber(s)`);

  for (const r of fresh) {
    const q = fmt.qualifierBadges(r).trim();
    const genres = (r.genres || []).join(", ").trim();
    const info = [];
    if (q) info.push(q);
    if (genres) info.push(`🎭 <i>${fmt.esc(genres)}</i>`);
    if ((r.formats || []).length) info.push(`🖥 ${fmt.esc(r.formats.join(", "))}`);
    if (r.cinema_seances?.seances_count)
      info.push(`🎟 ${r.cinema_seances.seances_count} сеансов в ${r.cinema_seances.cinema_count} кинотеатрах`);
    const caption =
      `🆕 <b>Новый фильм в афише!</b>${cityName ? " · " + fmt.esc(cityName) : ""}\n\n` +
      `🎬 <b>${fmt.esc(r.title)}</b>${r.age_rating ? ` <code>${fmt.esc(r.age_rating)}</code>` : ""}\n` +
      (info.length ? `<blockquote>${info.join("\n")}</blockquote>` : "");
    const keyboard = new InlineKeyboard().text("🎬 Смотреть сеансы", `m:${r.id}`);

    for (const chatId of subs) {
      try {
        await bot.api.sendPhoto(chatId, api.posterBig(r.id), {
          caption,
          parse_mode: "HTML",
          reply_markup: keyboard,
        });
      } catch (e) {
        const msg = e?.description || e?.message || "";
        // Пользователь заблокировал бота — отключаем ему уведомления.
        if (/blocked|deactivated|chat not found/i.test(msg)) {
          store.setNotify(chatId, false);
        } else {
          console.error(`[notifier] send to ${chatId} failed:`, msg);
        }
      }
      await new Promise((res) => setTimeout(res, 60)); // мягкий троттлинг
    }
  }

  // Обновляем «виденное» (объединяем — старые релизы могут временно исчезать из выборки по дате).
  for (const id of currentIds) seen.add(id);
  store.saveSeen(cityId, seen);
}

async function tick(bot) {
  // Проверяем города, где есть подписчики, плюс уже засеянные (чтобы seen не устаревал).
  const cities = new Set([...store.citiesWithSubscribers()]);
  // Всегда держим Ташкент засеянным, даже без подписчиков.
  cities.add(1);
  for (const cityId of cities) {
    await checkCity(bot, cityId);
  }
}

export function startNotifier(bot) {
  const run = () => tick(bot).catch((e) => console.error("[notifier] tick error:", e.message));
  run(); // сразу при старте (сеет seen)
  setInterval(run, POLL_INTERVAL_MS);
  console.log(`[notifier] started, interval ${POLL_INTERVAL_MS / 1000}s`);
}
