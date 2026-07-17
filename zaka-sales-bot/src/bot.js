import { Bot, InlineKeyboard } from "grammy";
import { BOT_TOKEN, categoryByKey } from "./config.js";
import * as api from "./api.js";
import * as store from "./store.js";
import * as fmt from "./format.js";
import * as kb from "./keyboards.js";

export const bot = new Bot(BOT_TOKEN);

const PAGE = 6;
const gameCache = new Map(); // slug -> { at, game }
const session = new Map(); // chatId -> { lastListCb, search: {query, results} }

function sess(chatId) {
  const id = String(chatId);
  if (!session.has(id)) session.set(id, {});
  return session.get(id);
}

function cacheGames(list) {
  const now = Date.now();
  for (const g of list) if (g?.slug) gameCache.set(g.slug, { at: now, game: g });
}
async function getGame(slug, name) {
  const hit = gameCache.get(slug);
  if (hit && Date.now() - hit.at < 3 * 60 * 1000) return hit.game;
  const g = await api.lookupBySlug(slug, name);
  if (g) cacheGames([g]);
  return g || (hit ? hit.game : null);
}

// Рендер текстового экрана: edit если можно, иначе новое сообщение.
async function screen(ctx, text, keyboard) {
  const msg = ctx.callbackQuery?.message;
  const opts = { reply_markup: keyboard, parse_mode: "HTML", link_preview_options: { is_disabled: true } };
  if (msg?.photo) {
    try { await ctx.deleteMessage(); } catch {}
    return ctx.reply(text, opts);
  }
  if (msg) {
    try { return await ctx.editMessageText(text, opts); }
    catch (e) { if (String(e).includes("not modified")) return; return ctx.reply(text, opts); }
  }
  return ctx.reply(text, opts);
}

// --- экраны ---
async function showMenu(ctx) {
  const chatId = ctx.chat?.id ?? ctx.from.id;
  const u = store.ensureUser(chatId);
  const text =
    `🎮 <b>Game Sales</b>\n\n` +
    `<blockquote>Ищите игры, добавляйте в вишлист и получайте уведомления, когда на них падает цена. Цены — в долларах 💵</blockquote>\n` +
    `Вишлист: <b>${store.wishlist(chatId).length}</b> · Уведомления: <b>${u.notify !== false ? "вкл" : "выкл"}</b>`;
  await screen(ctx, text, kb.mainMenuKb(u));
}

async function promptSearch(ctx) {
  await screen(
    ctx,
    "🔎 <b>Поиск игры</b>\n\n<blockquote>Напишите название игры сообщением — покажу цены и скидки.</blockquote>",
    kb.backKb("menu"),
  );
}

async function doSearch(ctx, query) {
  const chatId = ctx.chat?.id ?? ctx.from.id;
  const q = query.trim();
  if (q.length < 2) return ctx.reply("Введите минимум 2 символа 🙂");
  let results = [];
  try { results = await api.search(q); } catch {}
  cacheGames(results);
  const s = sess(chatId);
  s.search = { query: q, results };
  s.lastListCb = "s:0";
  const rate = await api.usdRate();
  if (!results.length) {
    return ctx.reply(
      `🔎 <b>${fmt.esc(q)}</b>\n\n<blockquote>😔 Ничего не найдено. Попробуйте другое название.</blockquote>`,
      { parse_mode: "HTML", reply_markup: kb.backKb("menu") },
    );
  }
  const text = `🔎 Результаты по запросу <b>${fmt.esc(q)}</b>\n\n<blockquote>Найдено: <b>${results.length}</b>. Выберите игру 👇</blockquote>`;
  await ctx.reply(text, { parse_mode: "HTML", reply_markup: kb.dealsKb(results, 0, PAGE, "s:", "menu", rate) });
}

async function showSearchPage(ctx, page) {
  const chatId = ctx.chat?.id ?? ctx.from.id;
  const s = sess(chatId).search;
  if (!s) return promptSearch(ctx);
  const rate = await api.usdRate();
  sess(chatId).lastListCb = `s:${page}`;
  const text = `🔎 Результаты по запросу <b>${fmt.esc(s.query)}</b>\n\n<blockquote>Найдено: <b>${s.results.length}</b>. Выберите игру 👇</blockquote>`;
  await screen(ctx, text, kb.dealsKb(s.results, page, PAGE, "s:", "menu", rate));
}

async function showCategories(ctx) {
  await screen(
    ctx,
    "🎮 <b>Каталог</b>\n\n<blockquote>Выберите раздел.</blockquote>",
    kb.categoriesKb(),
  );
}

async function showCategory(ctx, key, page) {
  const chatId = ctx.chat?.id ?? ctx.from.id;
  const cat = categoryByKey(key);
  if (!cat) return showCategories(ctx);
  const { cards, maxPage } = await api.listSection(cat, page + 1); // сайт-страницы 1-based
  cacheGames(cards);
  const rate = await api.usdRate();
  sess(chatId).lastListCb = `cat:${key}:${page}`;
  const back = key === "sale" ? "menu" : "cats";
  if (!cards.length) {
    return screen(ctx, `${cat.emoji} <b>${cat.title}</b>\n\n<blockquote>😔 Пусто. Загляните позже.</blockquote>`, kb.backKb(back));
  }
  const note = cat.onlySale ? "Отсортировано по размеру скидки 👇" : "Выберите игру 👇";
  const text = `${cat.emoji} <b>${cat.title}</b>\n\n<blockquote>${note}</blockquote>`;
  await screen(ctx, text, kb.sectionKb(cards, page, maxPage, `cat:${key}:`, back, rate));
}

async function showGame(ctx, slug) {
  const chatId = ctx.chat?.id ?? ctx.from.id;
  const g = await getGame(slug);
  if (!g) return ctx.answerCallbackQuery({ text: "Не удалось загрузить игру", show_alert: true });
  const rate = await api.usdRate();
  const wished = store.inWishlist(chatId, slug);
  const back = sess(chatId).lastListCb || "menu";
  let caption = fmt.gameCard(g, rate, wished);
  if (caption.length > 1024) caption = caption.slice(0, 1020) + "…";
  const keyboard = kb.gameKb(slug, wished, back);

  const msg = ctx.callbackQuery?.message;
  if (msg) { try { await ctx.deleteMessage(); } catch {} }
  if (g.image) {
    try {
      return await ctx.replyWithPhoto(g.image, { caption, parse_mode: "HTML", reply_markup: keyboard });
    } catch {}
  }
  await ctx.reply(caption, { parse_mode: "HTML", reply_markup: keyboard, link_preview_options: { is_disabled: true } });
}

async function toggleWishlist(ctx, slug) {
  const chatId = ctx.chat?.id ?? ctx.from.id;
  const g = await getGame(slug);
  const name = g?.name || slug;
  let added;
  if (store.inWishlist(chatId, slug)) {
    store.removeWishlist(chatId, slug);
    added = false;
  } else {
    store.addWishlist(chatId, { slug, name });
    added = true;
    // фиксируем текущую скидку, чтобы уведомлять только об УЛУЧШении
    if (g && !store.hasSaleState(slug)) store.setLastSale(slug, g.sale || 0);
  }
  await ctx.answerCallbackQuery({ text: added ? "⭐ Добавлено в вишлист" : "➖ Убрано из вишлиста" });
  return showGame(ctx, slug);
}

async function showWishlist(ctx, page) {
  const chatId = ctx.chat?.id ?? ctx.from.id;
  const wl = store.wishlist(chatId);
  sess(chatId).lastListCb = `wl:${page}`;
  if (!wl.length) {
    return screen(ctx, "⭐ <b>Вишлист пуст</b>\n\n<blockquote>Найдите игру через поиск и добавьте её — будем следить за скидками.</blockquote>", kb.backKb("menu"));
  }
  const rate = await api.usdRate();
  // подтягиваем актуальные цены только для игр текущей страницы
  const p = Math.min(Math.max(0, page), Math.ceil(wl.length / PAGE) - 1);
  const slice = wl.slice(p * PAGE, p * PAGE + PAGE);
  const enriched = await Promise.all(
    slice.map(async (w) => {
      const g = await getGame(w.slug, w.name).catch(() => null);
      return g ? { ...g } : { slug: w.slug, name: w.name, sale: 0, priceRub: null };
    }),
  );
  // соберём полный список: enriched для страницы + заглушки для остальных (кнопки строятся по срезу)
  const full = wl.map((w) => {
    const e = enriched.find((g) => g.slug === w.slug);
    return e || { slug: w.slug, name: w.name, sale: 0, priceRub: null };
  });
  const onSale = enriched.filter((g) => g.sale > 0).length;
  const text = `⭐ <b>Ваш вишлист</b>\n\n<blockquote>Игр: <b>${wl.length}</b>${onSale ? ` · со скидкой сейчас: <b>${onSale}</b>` : ""}\nНажмите на игру для деталей 👇</blockquote>`;
  await screen(ctx, text, kb.wishlistKb(full, page, PAGE, rate));
}

async function showNotify(ctx) {
  const chatId = ctx.chat?.id ?? ctx.from.id;
  const u = store.ensureUser(chatId);
  const on = u.notify !== false;
  const text =
    `🔔 <b>Уведомления о скидках</b>\n\n` +
    `<blockquote>Как только на игру из вашего вишлиста появляется новая или увеличенная скидка — бот пришлёт уведомление.</blockquote>\n` +
    `Статус: <b>${on ? "включены ✅" : "выключены ❌"}</b>`;
  await screen(ctx, text, kb.notifyKb(on));
}

// --- команды ---
bot.command("start", async (ctx) => { store.ensureUser(ctx.chat.id); await showMenu(ctx); });
bot.command("menu", showMenu);
bot.command("wishlist", (ctx) => showWishlist(ctx, 0));
bot.command("help", showMenu);

// --- callback-роутинг ---
bot.on("callback_query:data", async (ctx) => {
  const data = ctx.callbackQuery.data;
  try {
    if (data === "noop") return ctx.answerCallbackQuery();
    if (data === "menu") { await ctx.answerCallbackQuery(); return showMenu(ctx); }
    if (data === "search") { await ctx.answerCallbackQuery(); return promptSearch(ctx); }
    if (data === "cats") { await ctx.answerCallbackQuery(); return showCategories(ctx); }
    if (data === "ntf") { await ctx.answerCallbackQuery(); return showNotify(ctx); }

    if (data.startsWith("s:")) { await ctx.answerCallbackQuery(); return showSearchPage(ctx, Number(data.slice(2)) || 0); }
    if (data.startsWith("cat:")) {
      const [, key, page] = data.split(":");
      await ctx.answerCallbackQuery();
      return showCategory(ctx, key, Number(page) || 0);
    }
    if (data.startsWith("wl:")) { await ctx.answerCallbackQuery(); return showWishlist(ctx, Number(data.slice(3)) || 0); }
    if (data.startsWith("g:")) {
      await ctx.answerCallbackQuery();
      const slug = store.slugById(Number(data.slice(2)));
      return slug ? showGame(ctx, slug) : ctx.reply("Игра не найдена, попробуйте поиск заново.");
    }
    if (data.startsWith("w:")) {
      const slug = store.slugById(Number(data.slice(2)));
      return slug ? toggleWishlist(ctx, slug) : ctx.answerCallbackQuery();
    }
    if (data.startsWith("ntf:")) {
      store.setNotify(ctx.chat.id, data.slice(4) === "1");
      await ctx.answerCallbackQuery({ text: data.slice(4) === "1" ? "Включено 🔔" : "Выключено 🔕" });
      return showNotify(ctx);
    }
    await ctx.answerCallbackQuery();
  } catch (e) {
    console.error("callback error:", data, e?.message);
    try { await ctx.answerCallbackQuery({ text: "Ошибка, попробуйте ещё раз" }); } catch {}
  }
});

// Любой текст (не команда) -> поиск.
bot.on("message:text", (ctx) => {
  if (ctx.message.text.startsWith("/")) return;
  return doSearch(ctx, ctx.message.text);
});

bot.catch((err) => console.error("Bot error:", err?.error?.message || err?.message || err));
