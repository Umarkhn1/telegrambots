// Фоновая проверка скидок на игры из вишлистов и рассылка уведомлений.
import { InlineKeyboard } from "grammy";
import * as api from "./api.js";
import * as store from "./store.js";
import * as fmt from "./format.js";
import { POLL_INTERVAL_MS } from "./config.js";
import { slugId } from "./store.js";

async function tick(bot) {
  const wished = store.allWishedSlugs();
  if (!wished.length) return;
  const rate = await api.usdRate();
  console.log(`[notifier] проверка ${wished.length} игр из вишлистов`);

  for (const { slug, name } of wished) {
    let g;
    try {
      g = await api.lookupBySlug(slug, name);
    } catch {
      continue;
    }
    await new Promise((r) => setTimeout(r, 300)); // троттлинг к сайту
    if (!g) continue;

    const cur = g.sale || 0;
    const prev = store.getLastSale(slug);

    // Первое наблюдение — просто запомнить, без уведомления.
    if (prev === undefined) {
      store.setLastSale(slug, cur);
      continue;
    }
    // Уведомляем только если скидка ПОЯВИЛАСЬ или ВЫРОСЛА.
    if (cur > prev && cur > 0) {
      const subs = store.subscribersForSlug(slug);
      const caption = fmt.dealAlert(g, rate, prev);
      const keyboard = new InlineKeyboard().text("🎮 Открыть", `g:${slugId(slug)}`);
      for (const chatId of subs) {
        try {
          if (g.image) await bot.api.sendPhoto(chatId, g.image, { caption, parse_mode: "HTML", reply_markup: keyboard });
          else await bot.api.sendMessage(chatId, caption, { parse_mode: "HTML", reply_markup: keyboard });
        } catch (e) {
          const msg = e?.description || e?.message || "";
          if (/blocked|deactivated|chat not found/i.test(msg)) store.setNotify(chatId, false);
          else console.error(`[notifier] send ${chatId}:`, msg);
        }
        await new Promise((r) => setTimeout(r, 60));
      }
    }
    store.setLastSale(slug, cur);
  }
}

export function startNotifier(bot) {
  const run = () => tick(bot).catch((e) => console.error("[notifier] tick:", e.message));
  setTimeout(run, 15000); // первый прогон чуть позже старта
  setInterval(run, POLL_INTERVAL_MS);
  console.log(`[notifier] запущен, интервал ${POLL_INTERVAL_MS / 60000} мин`);
}
