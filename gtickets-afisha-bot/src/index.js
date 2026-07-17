import { bot } from "./bot.js";
import { startNotifier } from "./notifier.js";

async function main() {
  await bot.api.setMyCommands([
    { command: "start", description: "Главное меню" },
    { command: "afisha", description: "Афиша фильмов" },
    { command: "menu", description: "Меню" },
    { command: "help", description: "О боте" },
  ]);

  startNotifier(bot);

  bot.start({
    drop_pending_updates: true,
    onStart: (me) => console.log(`✅ Бот @${me.username} запущен`),
  });
}

main().catch((e) => {
  console.error("Fatal:", e);
  process.exit(1);
});
