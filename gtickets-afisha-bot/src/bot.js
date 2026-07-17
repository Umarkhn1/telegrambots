import { Bot, InlineKeyboard } from "grammy";
import { BOT_TOKEN, DEFAULT_CITY_ID } from "./config.js";
import * as api from "./api.js";
import * as store from "./store.js";
import * as fmt from "./format.js";
import * as kb from "./keyboards.js";

export const bot = new Bot(BOT_TOKEN);

const PAGE = 7;
const BUY_URL = "https://gtickets.uz/cinemas";
const session = new Map(); // chatId -> { date }

// --- utils ---
function tashkentToday() {
  return new Date(Date.now() + 5 * 3600 * 1000).toISOString().slice(0, 10);
}
function tashkentTomorrow() {
  return new Date(Date.now() + 5 * 3600 * 1000 + 86400000).toISOString().slice(0, 10);
}

function userCity(chatId) {
  const u = store.getUser(chatId);
  return u?.cityId ?? DEFAULT_CITY_ID;
}

async function cityName(cityId) {
  try {
    const cities = await api.getCities();
    return cities.find((c) => c.id === cityId)?.name || "Ташкент";
  } catch {
    return "Ташкент";
  }
}

function sessDate(chatId) {
  return session.get(String(chatId))?.date || tashkentToday();
}
function setSessDate(chatId, date) {
  const id = String(chatId);
  session.set(id, { ...(session.get(id) || {}), date });
}

// Универсальный рендер текстового экрана (edit если можно, иначе новое сообщение).
async function screen(ctx, text, keyboard) {
  const msg = ctx.callbackQuery?.message;
  const opts = {
    reply_markup: keyboard,
    parse_mode: "HTML",
    link_preview_options: { is_disabled: true },
  };
  if (msg?.photo) {
    try { await ctx.deleteMessage(); } catch {}
    return ctx.reply(text, opts);
  }
  if (msg) {
    try {
      return await ctx.editMessageText(text, opts);
    } catch (e) {
      if (String(e).includes("message is not modified")) return;
      return ctx.reply(text, opts);
    }
  }
  return ctx.reply(text, opts);
}

// --- экраны ---
async function showMenu(ctx) {
  const chatId = ctx.chat?.id ?? ctx.from.id;
  const cityId = userCity(chatId);
  const u = store.getUser(chatId);
  const name = await cityName(cityId);
  const text =
    `🎬 <b>АФИША кинотеатров</b>\n\n` +
    `<blockquote>Фильмы, сеансы и свободные места в кинотеатрах Узбекистана.</blockquote>\n` +
    `🏙 Город: <b>${fmt.esc(name)}</b>\n` +
    `📅 Дата: <b>${fmt.parseDate(sessDate(chatId), tashkentToday()).label}</b>`;
  await screen(ctx, text, kb.mainMenuKb(u, name));
}

async function showAfisha(ctx, page) {
  const chatId = ctx.chat?.id ?? ctx.from.id;
  const cityId = userCity(chatId);
  const date = sessDate(chatId);
  const dinfo = fmt.parseDate(date, tashkentToday());
  let releases;
  try {
    releases = await api.getPlaybill(cityId, { date });
  } catch {
    return screen(ctx, "⚠️ Не удалось загрузить афишу. Попробуйте позже.", kb.mainMenuKb(store.getUser(chatId), await cityName(cityId)));
  }
  if (!releases.length) {
    return screen(ctx, `🎬 <b>Афиша</b> · ${dinfo.short}\n\n<blockquote>😔 На эту дату сеансов не найдено. Выберите другой день.</blockquote>`, kb.afishaDatesKb(await safeDates(cityId), tashkentToday(), tashkentTomorrow(), date));
  }
  const header =
    `🎬 <b>Афиша</b> · ${dinfo.short}\n\n` +
    `<blockquote>Фильмов в прокате: <b>${releases.length}</b>\nВыберите фильм — описание, сеансы и свободные места 👇</blockquote>`;
  await screen(ctx, header, kb.moviesKb(releases, page, PAGE, "af:", "menu"));
}

async function safeDates(cityId, opts) {
  try {
    const d = await api.getDates(cityId, opts);
    return Array.isArray(d) && d.length ? d : [tashkentToday()];
  } catch {
    return [tashkentToday()];
  }
}

async function showAfishaDates(ctx) {
  const chatId = ctx.chat?.id ?? ctx.from.id;
  const cityId = userCity(chatId);
  const dates = await safeDates(cityId);
  await screen(
    ctx,
    "📅 <b>Выберите дату</b>\n\n<blockquote>Афиша обновится под выбранный день.</blockquote>",
    kb.afishaDatesKb(dates, tashkentToday(), tashkentTomorrow(), sessDate(chatId)),
  );
}

async function showCities(ctx) {
  const chatId = ctx.chat?.id ?? ctx.from.id;
  let cities;
  try { cities = await api.getCities(); } catch { cities = [{ id: 1, name: "Ташкент" }]; }
  await screen(ctx, "🏙 <b>Выберите город</b>", kb.citiesKb(cities, userCity(chatId)));
}

async function showCinemas(ctx, page) {
  const chatId = ctx.chat?.id ?? ctx.from.id;
  const cityId = userCity(chatId);
  let cinemas;
  try { cinemas = await api.getCinemas(cityId); } catch { cinemas = []; }
  if (!cinemas.length) return screen(ctx, "😔 Кинотеатры не найдены.", kb.mainMenuKb(store.getUser(chatId), await cityName(cityId)));
  const header =
    `🎦 <b>Кинотеатры</b> · ${fmt.esc(await cityName(cityId))}\n\n` +
    `<blockquote>Всего: <b>${cinemas.length}</b>\nВыберите кинотеатр — покажу его афишу.</blockquote>`;
  await screen(ctx, header, kb.cinemasKb(cinemas, page, PAGE));
}

async function showCinemaMovies(ctx, cinemaId, page) {
  const chatId = ctx.chat?.id ?? ctx.from.id;
  const cityId = userCity(chatId);
  const date = sessDate(chatId);
  let releases = [];
  let cinema = null;
  try {
    [releases, cinema] = await Promise.all([
      api.getPlaybill(cityId, { date, cinemaId }),
      api.getCinemas(cityId).then((cs) => cs.find((c) => c.id === cinemaId)),
    ]);
  } catch {}
  const title = cinema?.title || "Кинотеатр";
  const dinfo = fmt.parseDate(date, tashkentToday());
  if (!releases.length) {
    return screen(ctx, `🎦 <b>${fmt.esc(title)}</b>\n\n<blockquote>😔 На ${dinfo.short} сеансов нет. Выберите другую дату.</blockquote>`, new InlineKeyboard().text("📅 Дата", "afd").text("◀️ Кинотеатры", "cin:0"));
  }
  const addr = cinema?.address ? `📍 ${fmt.esc(cinema.address)}\n` : "";
  const header =
    `🎦 <b>${fmt.esc(title)}</b>\n\n` +
    `<blockquote>${addr}📅 ${dinfo.short} · фильмов: <b>${releases.length}</b>\nВыберите фильм 👇</blockquote>`;
  await screen(ctx, header, kb.moviesKb(releases, page, PAGE, `cinm:${cinemaId}:`, "cin:0"));
}

async function showMovie(ctx, rid) {
  const chatId = ctx.chat?.id ?? ctx.from.id;
  const cityId = userCity(chatId);
  const date = sessDate(chatId);
  let info;
  try {
    info = await api.getReleaseInfo(rid, cityId, date);
  } catch {
    return ctx.answerCallbackQuery({ text: "Не удалось загрузить фильм", show_alert: true });
  }
  if (!info || !info.id) {
    // фильм без сеансов на выбранную дату — берём инфо на ближайшую доступную
    const dates = await safeDates(cityId, { releaseId: rid });
    try { info = await api.getReleaseInfo(rid, cityId, dates[0]); } catch {}
  }
  if (!info || !info.id) {
    return ctx.answerCallbackQuery({ text: "Нет данных по фильму", show_alert: true });
  }
  let caption = fmt.movieCard(info);
  if (caption.length > 1024) caption = caption.slice(0, 1020) + "…";
  const dates = await safeDates(cityId, { releaseId: rid });
  const keyboard = kb.movieDatesKb(rid, dates, tashkentToday(), tashkentTomorrow());

  const msg = ctx.callbackQuery?.message;
  if (msg) { try { await ctx.deleteMessage(); } catch {} }
  try {
    await ctx.replyWithPhoto(api.posterBig(rid), { caption, parse_mode: "HTML", reply_markup: keyboard });
  } catch {
    await ctx.reply(caption, { parse_mode: "HTML", reply_markup: keyboard, link_preview_options: { is_disabled: true } });
  }
}

// Выбор кинотеатра для фильма на дату.
async function showMovieCinemas(ctx, rid, iso, page) {
  const chatId = ctx.chat?.id ?? ctx.from.id;
  const cityId = userCity(chatId);
  setSessDate(chatId, iso);
  let info;
  try { info = await api.getReleaseInfo(rid, cityId, iso); } catch {}
  const cinemas = (info?.cinemas || []).filter((c) => (c.seances || []).length);
  const dinfo = fmt.parseDate(iso, tashkentToday());
  if (!cinemas.length) {
    return screen(ctx, `🎬 <b>${fmt.esc(info?.title || "Фильм")}</b>\n\n<blockquote>😔 На ${dinfo.short} сеансов нет.</blockquote>`, new InlineKeyboard().text("◀️ К фильму", `m:${rid}`));
  }
  const text =
    `🎬 <b>${fmt.esc(info.title)}</b> · ${dinfo.short}\n\n` +
    `<blockquote>Кинотеатров: <b>${cinemas.length}</b>. Рядом с названием — число сеансов 🎟</blockquote>`;
  await screen(ctx, text, kb.movieCinemasKb(rid, iso, cinemas, page));
}

// Расписание сеансов в кинотеатре на дату + свободные места.
async function showSchedule(ctx, rid, iso, cinemaId) {
  const chatId = ctx.chat?.id ?? ctx.from.id;
  const cityId = userCity(chatId);
  let info;
  try { info = await api.getReleaseInfo(rid, cityId, iso); } catch {}
  const cinema = (info?.cinemas || []).find((c) => c.id === cinemaId);
  const dinfo = fmt.parseDate(iso, tashkentToday());
  if (!cinema || !(cinema.seances || []).length) {
    return screen(ctx, "<blockquote>😔 Сеансы не найдены.</blockquote>", kb.scheduleKb(rid, iso, BUY_URL));
  }
  await ctx.answerCallbackQuery({ text: "Загружаю свободные места…" }).catch(() => {});
  const seances = cinema.seances;
  // свободные места параллельно
  const frees = await Promise.all(
    seances.map((s) =>
      api.getSeanceInfo(s.id, cinemaId).then((r) => r?.free_places_count).catch(() => null),
    ),
  );
  const lines = seances.map((s, i) => fmt.seanceLine(s, frees[i]));
  const text =
    `🎬 <b>${fmt.esc(info.title)}</b>\n` +
    `🎦 ${fmt.esc(cinema.title)} · 📅 ${dinfo.short}\n` +
    (cinema.address ? `📍 ${fmt.esc(cinema.address)}\n` : "") +
    (cinema.phone ? `☎️ ${fmt.esc(cinema.phone)}\n` : "") +
    `\n<blockquote>${lines.join("\n")}</blockquote>\n` +
    `🟢 много · 🟡 мало (≤10) · 🔴 нет мест`;
  await screen(ctx, text, kb.scheduleKb(rid, iso, BUY_URL));
}

async function showNotify(ctx) {
  const chatId = ctx.chat?.id ?? ctx.from.id;
  const u = store.getUser(chatId);
  const on = u?.notify !== false;
  const name = await cityName(userCity(chatId));
  const text =
    `🔔 <b>Уведомления о новинках</b>\n\n` +
    `<blockquote>Как только в афише города <b>${fmt.esc(name)}</b> появляется новый фильм — бот пришлёт вам карточку.</blockquote>\n` +
    `Статус: <b>${on ? "включены ✅" : "выключены ❌"}</b>`;
  await screen(ctx, text, kb.notifyKb(on));
}

async function showAbout(ctx) {
  const text =
    `ℹ️ <b>О боте</b>\n\n` +
    `<blockquote>Афиша кинотеатров Узбекистана: фильмы, описания, сеансы, цены и <b>свободные места</b> в реальном времени.</blockquote>\n` +
    `🎬 Афиша — фильмы на выбранную дату\n` +
    `🎦 Кинотеатры — афиша конкретного зала\n` +
    `📅 Дата — до 5 дней вперёд\n` +
    `🔔 Уведомления о новых фильмах\n\n` +
    `<blockquote>Данные: gtickets.uz. Покупка билетов — на сайте.</blockquote>`;
  await screen(ctx, text, new InlineKeyboard().url("🌐 Открыть сайт", BUY_URL).row().text("◀️ Меню", "menu"));
}

// --- команды ---
bot.command("start", async (ctx) => {
  const chatId = ctx.chat.id;
  if (!store.getUser(chatId)) store.upsertUser(chatId, { cityId: DEFAULT_CITY_ID, notify: true });
  await showMenu(ctx);
});
bot.command("afisha", (ctx) => showAfisha(ctx, 0));
bot.command("menu", showMenu);
bot.command("help", showAbout);

// --- callback-роутинг ---
bot.on("callback_query:data", async (ctx) => {
  const data = ctx.callbackQuery.data;
  try {
    if (data === "noop") return ctx.answerCallbackQuery();
    if (data === "menu") { await ctx.answerCallbackQuery(); return showMenu(ctx); }
    if (data === "about") { await ctx.answerCallbackQuery(); return showAbout(ctx); }
    if (data === "city") { await ctx.answerCallbackQuery(); return showCities(ctx); }
    if (data === "afd") { await ctx.answerCallbackQuery(); return showAfishaDates(ctx); }
    if (data === "ntf") { await ctx.answerCallbackQuery(); return showNotify(ctx); }

    if (data.startsWith("city:")) {
      const id = Number(data.slice(5));
      store.setCity(ctx.chat.id, id);
      await ctx.answerCallbackQuery({ text: "Город изменён ✅" });
      return showMenu(ctx);
    }
    if (data.startsWith("af:")) { await ctx.answerCallbackQuery(); return showAfisha(ctx, Number(data.slice(3)) || 0); }
    if (data.startsWith("afd:")) {
      setSessDate(ctx.chat.id, data.slice(4));
      await ctx.answerCallbackQuery({ text: "Дата выбрана 📅" });
      return showAfisha(ctx, 0);
    }
    if (data.startsWith("cinm:")) {
      const [, cid, page] = data.split(":");
      await ctx.answerCallbackQuery();
      return showCinemaMovies(ctx, Number(cid), Number(page) || 0);
    }
    if (data.startsWith("cin:")) { await ctx.answerCallbackQuery(); return showCinemas(ctx, Number(data.slice(4)) || 0); }
    if (data.startsWith("md:")) {
      const [, rid, iso, page] = data.split(":");
      await ctx.answerCallbackQuery();
      return showMovieCinemas(ctx, Number(rid), iso, Number(page) || 0);
    }
    if (data.startsWith("sc:")) {
      const [, rid, iso, cid] = data.split(":");
      return showSchedule(ctx, Number(rid), iso, Number(cid));
    }
    if (data.startsWith("m:")) { await ctx.answerCallbackQuery(); return showMovie(ctx, Number(data.slice(2))); }
    if (data.startsWith("ntf:")) {
      const on = data.slice(4) === "1";
      store.setNotify(ctx.chat.id, on);
      await ctx.answerCallbackQuery({ text: on ? "Уведомления включены 🔔" : "Уведомления выключены 🔕" });
      return showNotify(ctx);
    }
    await ctx.answerCallbackQuery();
  } catch (e) {
    console.error("callback error:", data, e?.message);
    try { await ctx.answerCallbackQuery({ text: "Ошибка, попробуйте ещё раз", show_alert: false }); } catch {}
  }
});

// Любой текст -> меню.
bot.on("message:text", (ctx) => showMenu(ctx));

bot.catch((err) => {
  console.error("Bot error:", err?.error?.message || err?.message || err);
});
