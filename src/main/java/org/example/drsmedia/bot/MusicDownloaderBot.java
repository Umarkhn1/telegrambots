package org.example.drsmedia.bot;

import lombok.extern.slf4j.Slf4j;
import org.example.drsmedia.services.VideoService;
import org.example.drsmedia.util.UrlDetector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class MusicDownloaderBot extends TelegramLongPollingBot {

    private final VideoService videoService;
    private final String botToken;
    private final String botUsername;

    @Value("${telegram.channel.username:biz_ness_uz}")
    private String channelUsername;

    private final Map<Long, String> userLang = new ConcurrentHashMap<>();
    private final Map<String, File> pendingAudio = new ConcurrentHashMap<>();

    // ===================== LANG PACKS =====================
    private static final Map<String, String> RU = new HashMap<>();
    private static final Map<String, String> UZ = new HashMap<>();
    private static final Map<String, String> UZC = new HashMap<>();

    static {
        /* =========== RUSSIAN =========== */
        RU.put("lang_btn", "🇷🇺 Русский");
        RU.put("subscribe_msg",
                "**\\· Доступ закрыт \\·**\n\n" +
                        "> 🔒 Чтобы использовать бота,\n" +
                        "> подпишитесь на наш канал\\.\n\n" +
                        "Нажмите *Подписаться*, затем *Я подписался* ✅");
        RU.put("subscribe_btn", "📢 Подписаться на канал");
        RU.put("check_sub_btn", "✅ Я подписался");
        RU.put("not_subscribed", "❌ Вы ещё не подписались\\. Подпишитесь и нажмите кнопку ещё раз\\.");
        RU.put("greeting",
                "🎬 *BN Insta\\-TikTok\\-YouTube Saver*\n\n" +
                        "> 👋 Привет\\! Я скачиваю видео\n" +
                        "> с YouTube, TikTok и Instagram\n" +
                        "> и конвертирую в MP3\n" +
                        "> одним нажатием кнопки\\.\n\n" +
                        "> 📥 *Что умею:*\n" +
                        "> • Скачать видео\n" +
                        "> • Конвертировать в MP3\n" +
                        "> • Работаю в группах \\(для админов\\)\n\n" +
                        "> 💡 *Как использовать:*\n" +
                        "> Просто отправь ссылку\\!");
        RU.put("downloading", "⏳ Скачиваю видео\\.\\.\\.");
        RU.put("extracting", "🎵 Конвертирую в MP3\\.\\.\\.");
        RU.put("unknown_platform",
                "> ❓ *Платформа не определена*\n" +
                        "> Поддерживаются: YouTube, TikTok, Instagram");
        RU.put("download_error",
                "> ❌ *Не удалось скачать видео*\n" +
                        "> Проверьте ссылку и попробуйте снова");
        RU.put("audio_error",
                "> ⚠️ Видео отправлено,\n" +
                        "> но конвертация в MP3 не удалась");
        RU.put("error",
                "> ❌ Ошибка при обработке запроса");
        RU.put("audio_btn", "🎵 Скачать MP3");
        RU.put("video_caption",
                "✅ *Видео готово\\!*\n\n" +
                        "> 👇 Нажми кнопку для MP3");
        RU.put("not_url",
                "> ❓ Это не похоже на ссылку\\.\n" +
                        "> Отправь ссылку YouTube, TikTok\n" +
                        "> или Instagram");
        RU.put("change_lang_btn", "🌐 Сменить язык");
        RU.put("help_btn", "ℹ️ Помощь");
        RU.put("add_to_group_btn", "➕ Добавить в группу");
        RU.put("group_welcome",
                "🎬 *BN Insta\\-TikTok\\-YouTube Saver*\n\n" +
                        "> 👋 Привет\\! / Salom\\! / Салом\\!\n\n" +
                        "> 📥 *RU:* Отправьте ссылку YouTube,\n" +
                        "> TikTok или Instagram — скачаю видео\n" +
                        "> и дам кнопку для MP3\\.\n\n" +
                        "> 📥 *UZ:* YouTube, TikTok yoki Instagram\n" +
                        "> havolasini yuboring — video yuklayman\n" +
                        "> va MP3 tugmasini ko'rsataman\\.\n\n" +
                        "> ⚙️ Для работы нужны права *админа*\\.\n" +
                        "> Ishlash uchun *admin* huquqi kerak\\.\n\n" +
                        "> 📢 *Поддержите нас и вступите в канал ниже*\\.");
        RU.put("channel_support_btn", "📢 Наш канал");
        RU.put("need_admin",
                "> ⚙️ *Для работы в группе*\n" +
                        "> дайте боту права администратора\\.");
        RU.put("join_channel_btn", "📢 Вступить в канал");

        /* =========== UZBEK LATIN =========== */
        UZ.put("lang_btn", "🇺🇿 O'zbekcha");
        UZ.put("subscribe_msg",
                "**\\· Kirish yopiq \\·**\n\n" +
                        "> 🔒 Botdan foydalanish uchun\n" +
                        "> kanalimizga obuna bo'ling\\.\n\n" +
                        "*Obuna bo'lish* tugmasini bosing, so'ng *Obuna bo'ldim* ✅");
        UZ.put("subscribe_btn", "📢 Kanalga obuna bo'lish");
        UZ.put("check_sub_btn", "✅ Obuna bo'ldim");
        UZ.put("not_subscribed", "❌ Siz hali obuna bo'lmadingiz\\. Obuna bo'lib, tugmani yana bosing\\.");
        UZ.put("greeting",
                "🎬 *BN Insta\\-TikTok\\-YouTube Saver*\n\n" +
                        "> 👋 Salom\\! Men YouTube, TikTok\n" +
                        "> va Instagram'dan video yuklayman\n" +
                        "> va MP3 ga aylantiraman\n" +
                        "> bitta tugma bilan\\.\n\n" +
                        "> 📥 *Nima qila olaman:*\n" +
                        "> • Video yuklab olish\n" +
                        "> • MP3 ga aylantirish\n" +
                        "> • Guruhlarda ishlaydi \\(adminlar uchun\\)\n\n" +
                        "> 💡 *Qanday ishlatish:*\n" +
                        "> Shunchaki havola yuboring\\!");
        UZ.put("downloading", "⏳ Video yuklanmoqda\\.\\.\\.");
        UZ.put("extracting", "🎵 MP3 ga aylantirilmoqda\\.\\.\\.");
        UZ.put("unknown_platform",
                "> ❓ *Platforma aniqlanmadi*\n" +
                        "> Qo'llab\\-quvvatlanadi: YouTube, TikTok, Instagram");
        UZ.put("download_error",
                "> ❌ *Video yuklab bo'lmadi*\n" +
                        "> Havolani tekshiring va qayta urining");
        UZ.put("audio_error",
                "> ⚠️ Video yuborildi,\n" +
                        "> lekin MP3 ga aylantirib bo'lmadi");
        UZ.put("error",
                "> ❌ So'rovni qayta ishlashda xatolik");
        UZ.put("audio_btn", "🎵 MP3 yuklab olish");
        UZ.put("video_caption",
                "✅ *Video tayyor\\!*\n\n" +
                        "> 👇 MP3 uchun tugmani bosing");
        UZ.put("not_url",
                "> ❓ Bu havola emas\\.\n" +
                        "> YouTube, TikTok yoki Instagram\n" +
                        "> havolasini yuboring");
        UZ.put("change_lang_btn", "🌐 Tilni o'zgartirish");
        UZ.put("help_btn", "ℹ️ Yordam");
        UZ.put("add_to_group_btn", "➕ Guruhga qo'shish");
        UZ.put("group_welcome",
                "🎬 *BN Insta\\-TikTok\\-YouTube Saver*\n\n" +
                        "> 👋 Привет\\! / Salom\\! / Салом\\!\n\n" +
                        "> 📥 *RU:* Отправьте ссылку YouTube,\n" +
                        "> TikTok или Instagram — скачаю видео\n" +
                        "> и дам кнопку для MP3\\.\n\n" +
                        "> 📥 *UZ:* YouTube, TikTok yoki Instagram\n" +
                        "> havolasini yuboring — video yuklayman\n" +
                        "> va MP3 tugmasini ko'rsataman\\.\n\n" +
                        "> ⚙️ Для работы нужны права *админа*\\.\n" +
                        "> Ishlash uchun *admin* huquqi kerak\\.\n\n" +
                        "> 📢 *Поддержите нас и вступите в канал ниже*\\.");
        UZ.put("channel_support_btn", "📢 Bizning kanal");
        UZ.put("need_admin",
                "> ⚙️ *Guruhda ishlashi uchun*\n" +
                        "> botga administrator huquqini bering\\.");
        UZ.put("join_channel_btn", "📢 Kanalga kirish");

        /* =========== UZBEK CYRILLIC =========== */
        UZC.put("lang_btn", "🇺🇿 Ўзбекча");
        UZC.put("subscribe_msg",
                "**\\· Кириш ёпиқ \\·**\n\n" +
                        "> 🔒 Ботдан фойдаланиш учун\n" +
                        "> каналимизга обуна бўлинг\\.\n\n" +
                        "*Обуна бўлиш* тугмасини босинг, сўнг *Обуна бўлдим* ✅");
        UZC.put("subscribe_btn", "📢 Каналга обуна бўлиш");
        UZC.put("check_sub_btn", "✅ Обуна бўлдим");
        UZC.put("not_subscribed", "❌ Сиз ҳали обуна бўлмадингиз\\. Обуна бўлиб, тугмани яна босинг\\.");
        UZC.put("greeting",
                "🎬 *BN Insta\\-TikTok\\-YouTube Saver*\n\n" +
                        "> 👋 Салом\\! Мен YouTube, TikTok\n" +
                        "> ва Instagram'дан видео юклайман\n" +
                        "> ва MP3 га айлантираман\n" +
                        "> битта тугма билан\\.\n\n" +
                        "> 📥 *Нима қила оламан:*\n" +
                        "> • Видео юклаб олиш\n" +
                        "> • MP3 га айлантириш\n" +
                        "> • Гуруҳларда ишлайди \\(админлар учун\\)\n\n" +
                        "> 💡 *Қандай ишлатиш:*\n" +
                        "> Шунчаки ҳавола юборинг\\!");
        UZC.put("downloading", "⏳ Видео юкланмоқда\\.\\.\\.");
        UZC.put("extracting", "🎵 MP3 га айлантирилмоқда\\.\\.\\.");
        UZC.put("unknown_platform",
                "> ❓ *Платформа аниқланмади*\n" +
                        "> Қўллаб\\-қувватланади: YouTube, TikTok, Instagram");
        UZC.put("download_error",
                "> ❌ *Видео юклаб бўлмади*\n" +
                        "> Ҳаволани текширинг ва қайта уринг");
        UZC.put("audio_error",
                "> ⚠️ Видео юборилди,\n" +
                        "> лекин MP3 га айлантириб бўлмади");
        UZC.put("error",
                "> ❌ Сўровни қайта ишлашда хатолик");
        UZC.put("audio_btn", "🎵 MP3 юклаб олиш");
        UZC.put("video_caption",
                "✅ *Видео тайёр\\!*\n\n" +
                        "> 👇 MP3 учун тугмани босинг");
        UZC.put("not_url",
                "> ❓ Бу ҳавола эмас\\.\n" +
                        "> YouTube, TikTok ёки Instagram\n" +
                        "> ҳаволасини юборинг");
        UZC.put("change_lang_btn", "🌐 Тилни ўзгартириш");
        UZC.put("help_btn", "ℹ️ Ёрдам");
        UZC.put("add_to_group_btn", "➕ Гуруҳга қўшиш");
        UZC.put("group_welcome",
                "🎬 *BN Insta\\-TikTok\\-YouTube Saver*\n\n" +
                        "> 👋 Привет\\! / Salom\\! / Салом\\!\n\n" +
                        "> 📥 *RU:* Отправьте ссылку YouTube,\n" +
                        "> TikTok или Instagram — скачаю видео\n" +
                        "> и дам кнопку для MP3\\.\n\n" +
                        "> 📥 *UZ:* YouTube, TikTok yoki Instagram\n" +
                        "> havolasini yuboring — video yuklayman\n" +
                        "> va MP3 tugmasini ko'rsataman\\.\n\n" +
                        "> ⚙️ Для работы нужны права *админа*\\.\n" +
                        "> Ishlash uchun *admin* huquqi kerak\\.\n\n" +
                        "> 📢 *Поддержите нас и вступите в канал ниже*\\.");
        UZC.put("channel_support_btn", "📢 Бизнинг канал");
        UZC.put("need_admin",
                "> ⚙️ *Гуруҳда ишлаши учун*\n" +
                        "> ботга администратор ҳуқуқини беринг\\.");
        UZC.put("join_channel_btn", "📢 Каналга кириш");
    }

    // ===================== CONSTRUCTOR =====================
    public MusicDownloaderBot(
            @Value("${telegram.bot.token}") String botToken,
            @Value("${telegram.bot.username}") String botUsername,
            VideoService videoService) {
        this.botToken = botToken;
        this.botUsername = botUsername;
        this.videoService = videoService;
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    // ===================== UPDATE =====================
    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
                return;
            }
            if (!update.hasMessage()) return;

            Message msg = update.getMessage();
            Long chatId = msg.getChatId();

            if (isGroupChat(msg)) {
                if (msg.getNewChatMembers() != null) {
                    for (org.telegram.telegrambots.meta.api.objects.User u : msg.getNewChatMembers()) {
                        if (u.getUserName() != null && u.getUserName().equalsIgnoreCase(botUsername)) {
                            sendGroupWelcome(chatId);
                            return;
                        }
                    }
                }

                if (msg.getFrom() == null) return;

                if (msg.hasText()) {
                    String url = UrlDetector.extractFirstUrl(msg.getText());
                    if (url != null) {
                        if (!isBotAdmin(chatId)) {
                            sendMd(chatId, RU.get("need_admin"));
                            return;
                        }
                        if (!isAdmin(chatId, msg.getFrom().getId())) return;
                        handleUrl(chatId, url, "ru");
                    }
                }
                return;
            }

            if (msg.hasText()) {
                handlePrivate(chatId, msg.getText().trim());
            }
        } catch (Exception e) {
            log.error("Ошибка обработки апдейта", e);
        }
    }

    // ===================== PRIVATE CHAT =====================
    private void handlePrivate(Long chatId, String text) throws TelegramApiException {
        String lang = userLang.get(chatId);

        if (text.equals("/start") || lang == null) {
            sendLangSelection(chatId);
            return;
        }

        Map<String, String> L = getL(lang);

        if (!isSubscribed(chatId)) {
            sendSubscribePrompt(chatId, lang);
            return;
        }

        if (text.equals(L.get("change_lang_btn"))) {
            sendLangSelection(chatId);
            return;
        }
        if (text.equals(L.get("help_btn"))) {
            sendGreeting(chatId, lang);
            return;
        }

        String url = UrlDetector.extractFirstUrl(text);
        if (url != null) {
            handleUrl(chatId, url, lang);
        } else {
            sendMd(chatId, L.get("not_url"));
        }
    }

    // ===================== URL HANDLER =====================
    private void handleUrl(Long chatId, String url, String lang) throws TelegramApiException {
        Map<String, String> L = getL(lang);
        String platform = videoService.detectPlatform(url);

        if ("unknown".equals(platform)) {
            sendMd(chatId, L.get("unknown_platform"));
            return;
        }

        sendMd(chatId, L.get("downloading"));
        File video = null;
        try {
            video = videoService.downloadVideoTemp(url, platform);
            if (video == null) {
                sendMd(chatId, L.get("download_error"));
                return;
            }

            String key = chatId + "_" + System.currentTimeMillis();
            pendingAudio.put(key, video);

            SendVideo sv = new SendVideo();
            sv.setChatId(chatId.toString());
            sv.setVideo(new InputFile(video));
            sv.setCaption(L.get("video_caption"));
            sv.setParseMode("MarkdownV2");
            sv.setReplyMarkup(audioMarkup(L.get("audio_btn"), L.get("join_channel_btn"), key));
            execute(sv);

        } catch (Exception e) {
            log.error("Ошибка при обработке ссылки", e);
            sendMd(chatId, L.get("error"));
            if (video != null) videoService.cleanup(video);
        }
    }

    // ===================== CALLBACK =====================
    private void handleCallback(CallbackQuery cb) throws TelegramApiException {
        String data = cb.getData();
        Long chatId = cb.getMessage().getChatId();
        int msgId = cb.getMessage().getMessageId();

        if (data.startsWith("lang:")) {
            String lang = data.substring(5);
            userLang.put(chatId, lang);
            deleteMsg(chatId, msgId);
            if (!isSubscribed(chatId)) {
                sendSubscribePrompt(chatId, lang);
            } else {
                sendGreeting(chatId, lang);
            }
            return;
        }

        if (data.equals("check_sub")) {
            String lang = userLang.getOrDefault(chatId, "ru");
            Map<String, String> L = getL(lang);
            if (isSubscribed(chatId)) {
                deleteMsg(chatId, msgId);
                sendGreeting(chatId, lang);
            } else {
                answerCb(cb.getId(), unescapeMd(L.get("not_subscribed")), true);
            }
            return;
        }

        if (data.startsWith("audio:")) {
            String key = data.substring(6);
            String lang = userLang.getOrDefault(chatId, "ru");
            Map<String, String> L = getL(lang);
            File videoFile = pendingAudio.get(key);

            if (videoFile == null || !videoFile.exists()) {
                answerCb(cb.getId(), "❌ Файл устарел. Отправьте ссылку заново.", true);
                return;
            }
            answerCb(cb.getId(), "🎵 Конвертирую...", false);
            sendMd(chatId, L.get("extracting"));

            File audio = videoService.extractAudio(videoFile);
            if (audio != null) {
                SendAudio sa = new SendAudio();
                sa.setChatId(chatId.toString());
                sa.setAudio(new InputFile(audio));
                execute(sa);
                videoService.cleanup(audio);
            } else {
                sendMd(chatId, L.get("audio_error"));
            }
        }
    }

    // ===================== SUBSCRIPTION =====================
    private boolean isSubscribed(Long userId) {
        try {
            GetChatMember gcm = new GetChatMember();
            gcm.setChatId("@" + channelUsername);
            gcm.setUserId(userId);
            ChatMember cm = execute(gcm);
            String s = cm.getStatus();
            return "member".equals(s) || "administrator".equals(s) || "creator".equals(s);
        } catch (Exception e) {
            log.warn("Ошибка проверки подписки для {}: {}", userId, e.getMessage());
            return false;
        }
    }

    private boolean isAdmin(Long chatId, Long userId) {
        try {
            GetChatMember gcm = new GetChatMember();
            gcm.setChatId(chatId.toString());
            gcm.setUserId(userId);
            ChatMember cm = execute(gcm);
            String s = cm.getStatus();
            return "administrator".equals(s) || "creator".equals(s);
        } catch (Exception e) {
            log.warn("Ошибка проверки роли: {}", e.getMessage());
            return false;
        }
    }

    private boolean isBotAdmin(Long chatId) {
        try {
            User me = getMe();
            if (me == null) return false;

            GetChatMember gcm = new GetChatMember();
            gcm.setChatId(chatId.toString());
            gcm.setUserId(me.getId());
            ChatMember cm = execute(gcm);
            String s = cm.getStatus();
            return "administrator".equals(s) || "creator".equals(s);
        } catch (Exception e) {
            log.warn("Ошибка проверки прав бота: {}", e.getMessage());
            return false;
        }
    }

    private boolean isGroupChat(Message msg) {
        String t = msg.getChat().getType();
        return "group".equals(t) || "supergroup".equals(t);
    }

    // ===================== SENDERS =====================
    private void sendGroupWelcome(Long chatId) {
        try {
            InlineKeyboardButton channelBtn = new InlineKeyboardButton();
            channelBtn.setText("📢 @" + channelUsername);
            channelBtn.setUrl("https://t.me/" + channelUsername);

            InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
            markup.setKeyboard(Collections.singletonList(
                    Collections.singletonList(channelBtn)
            ));

            SendMessage sm = new SendMessage();
            sm.setChatId(chatId.toString());
            sm.setText(RU.get("group_welcome"));
            sm.setParseMode("MarkdownV2");
            sm.setReplyMarkup(markup);
            execute(sm);
        } catch (Exception e) {
            log.error("Ошибка отправки приветствия в группу", e);
        }
    }

    private void sendLangSelection(Long chatId) throws TelegramApiException {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(Collections.singletonList(Arrays.asList(
                btn(RU.get("lang_btn"), "lang:ru"),
                btn(UZ.get("lang_btn"), "lang:uz"),
                btn(UZC.get("lang_btn"), "lang:uzc")
        )));
        SendMessage sm = new SendMessage();
        sm.setChatId(chatId.toString());
        sm.setText("🌐 Выберите язык / Tilni tanlang / Тилни танланг:");
        sm.setReplyMarkup(markup);
        execute(sm);
    }

    private void sendSubscribePrompt(Long chatId, String lang) throws TelegramApiException {
        Map<String, String> L = getL(lang);

        InlineKeyboardButton subBtn = new InlineKeyboardButton();
        subBtn.setText(L.get("subscribe_btn"));
        subBtn.setUrl("https://t.me/" + channelUsername);

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(Arrays.asList(
                Collections.singletonList(subBtn),
                Collections.singletonList(btn(L.get("check_sub_btn"), "check_sub"))
        ));

        SendMessage sm = new SendMessage();
        sm.setChatId(chatId.toString());
        sm.setText(L.get("subscribe_msg"));
        sm.setParseMode("MarkdownV2");
        sm.setReplyMarkup(markup);
        execute(sm);
    }

    private void sendGreeting(Long chatId, String lang) throws TelegramApiException {
        Map<String, String> L = getL(lang);

        KeyboardRow row = new KeyboardRow();
        row.add(new KeyboardButton(L.get("help_btn")));
        row.add(new KeyboardButton(L.get("change_lang_btn")));

        ReplyKeyboardMarkup rkm = new ReplyKeyboardMarkup();
        rkm.setKeyboard(Collections.singletonList(row));
        rkm.setResizeKeyboard(true);

        InlineKeyboardButton addGroupBtn = new InlineKeyboardButton();
        addGroupBtn.setText(L.get("add_to_group_btn"));
        addGroupBtn.setUrl("https://t.me/" + botUsername + "?startgroup=start");

        InlineKeyboardMarkup inlineMarkup = new InlineKeyboardMarkup();
        inlineMarkup.setKeyboard(Collections.singletonList(
                Collections.singletonList(addGroupBtn)
        ));

        SendMessage sm = new SendMessage();
        sm.setChatId(chatId.toString());
        sm.setText(L.get("greeting"));
        sm.setParseMode("MarkdownV2");
        sm.setReplyMarkup(inlineMarkup); // кнопка теперь у основного сообщения
        execute(sm);
    }

    private void sendMd(Long chatId, String text) {
        try {
            SendMessage sm = new SendMessage();
            sm.setChatId(chatId.toString());
            sm.setText(text);
            sm.setParseMode("MarkdownV2");
            execute(sm);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения", e);
        }
    }

    private void deleteMsg(Long chatId, int msgId) {
        try {
            DeleteMessage dm = new DeleteMessage();
            dm.setChatId(chatId.toString());
            dm.setMessageId(msgId);
            execute(dm);
        } catch (Exception ignored) {
        }
    }

    private void answerCb(String cbId, String text, boolean alert) {
        try {
            AnswerCallbackQuery acq = new AnswerCallbackQuery();
            acq.setCallbackQueryId(cbId);
            acq.setText(text);
            acq.setShowAlert(alert);
            execute(acq);
        } catch (Exception ignored) {
        }
    }

    // ===================== HELPERS =====================
    private InlineKeyboardMarkup audioMarkup(String audioLabel, String channelLabel, String key) {
        InlineKeyboardButton audioBtn = btn(audioLabel, "audio:" + key);

        InlineKeyboardButton channelBtn = new InlineKeyboardButton();
        channelBtn.setText(channelLabel);
        channelBtn.setUrl("https://t.me/" + channelUsername);

        InlineKeyboardMarkup m = new InlineKeyboardMarkup();
        m.setKeyboard(Collections.singletonList(
                Arrays.asList(audioBtn, channelBtn)
        ));
        return m;
    }

    private InlineKeyboardButton btn(String text, String callback) {
        InlineKeyboardButton b = new InlineKeyboardButton();
        b.setText(text);
        b.setCallbackData(callback);
        return b;
    }

    private String unescapeMd(String s) {
        return s.replace("\\.", ".").replace("\\!", "!").replace("\\-", "-")
                .replace("\\(", "(").replace("\\)", ")").replace(">", "");
    }

    private Map<String, String> getL(String lang) {
        if ("uz".equals(lang)) return UZ;
        if ("uzc".equals(lang)) return UZC;
        return RU;
    }
}