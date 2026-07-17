import { bot } from "./bot.js";
import { startNotifier } from "./notifier.js";
import * as api from "./api.js";

async function main() {
  await bot.api.setMyCommands([
    { command: "start", description: "Главное меню" },
    { command: "wishlist", description: "Мой вишлист" },
    { command: "help", description: "О боте" },
  ]);

  await api.usdRate().catch(() => {}); // прогреть курс

  startNotifier(bot);
  bot.start({
    drop_pending_updates: true,
    onStart: (me) => console.log(`✅ Бот @${me.username} запущен`),
  });
}

main().catch((e) => { console.error("Fatal:", e); process.exit(1); });
