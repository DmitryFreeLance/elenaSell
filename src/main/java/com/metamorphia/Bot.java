package com.metamorphia;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.objects.ChatJoinRequest;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.groupadministration.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice;
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery;
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice;
import org.telegram.telegrambots.meta.api.objects.payments.SuccessfulPayment;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Bot extends TelegramLongPollingBot {
    private static final String TOKEN      = System.getenv("BOT_TOKEN");
    private static final String USERNAME   = System.getenv("BOT_USERNAME");
    private static final String PROVIDER   = System.getenv("TG_PROVIDER_TOKEN"); // токен провайдера (ЮKassa через BotFather)

    // антидубль нажатия «Оплатить»
    private static final Map<Long, Long> lastPayClickTs = new ConcurrentHashMap<>();
    private static final long PAY_COOLDOWN_MS = 15_000; // 15 секунд

    public static void start() throws Exception {
        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        Bot bot = new Bot();
        api.registerBot(bot);
        BotSender.set(bot); // важно, чтобы отправлять из любых мест
    }

    @Override public String getBotToken() { return TOKEN; }
    @Override public String getBotUsername() { return USERNAME; }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            // === 1) PreCheckoutQuery (обязателен) ===
            if (update.hasPreCheckoutQuery()) {
                var pq = update.getPreCheckoutQuery();
                execute(AnswerPreCheckoutQuery.builder()
                        .preCheckoutQueryId(pq.getId())
                        .ok(true)
                        .build());
                return;
            }

            // === 2) Успешная оплата ===
            if (update.hasMessage() && update.getMessage().hasSuccessfulPayment()) {
                var msg = update.getMessage();
                var user = msg.getFrom();
                long userId = user.getId();
                SuccessfulPayment sp = msg.getSuccessfulPayment();
                String paymentId = sp.getTelegramPaymentChargeId(); // ID в Telegram

                // гарантируем наличие пользователя (для FK)
                DB.upsertUser(userId, user.getUserName(), user.getFirstName(), user.getLastName());

                // активируем подписку на 30 дней
                SubscriptionService.activate(userId, paymentId);

                // выдаём ссылку: по умолчанию — заявка (request_link)
                String link = DB.get("request_link");

                execute(SendMessage.builder()
                        .chatId(userId)
                        .text("Оплата прошла успешно ✅\nСсылка для входа в канал: " + link)
                        .build());
                return;
            }

            // === 3) Тексты/команды ===
            if (update.hasMessage() && update.getMessage().hasText()) {
                var msg = update.getMessage();
                var text = msg.getText().trim();
                var user = msg.getFrom();

                // логируем пользователя
                DB.upsertUser(user.getId(), user.getUserName(), user.getFirstName(), user.getLastName());

                if (text.startsWith("/start")) {
                    sendStart(msg.getChatId());
                    return;
                }
                if (text.equals("/my_sub")) {
                    sendMySub(msg.getChatId(), user.getId());
                    return;
                }
                if (text.startsWith("/adminadd")) { // /adminadd 123456
                    AdminService.handleAdminAdd(this, msg);
                    return;
                }
                if (text.startsWith("/setgroup")) { // /setgroup -1001234567890  или /setgroup @username
                    AdminService.handleSetGroup(this, msg);
                    return;
                }
                if (text.startsWith("/price")) { // /price 5000
                    AdminService.handlePrice(this, msg);
                    return;
                }
                if (text.startsWith("/stats")) {
                    AdminService.handleStats(this, msg);
                    return;
                }
            }

            // === 4) Inline-кнопки ===
            if (update.hasCallbackQuery()) {
                var cq = update.getCallbackQuery();
                var data = cq.getData();

                // Всегда отвечаем на callback, чтобы Telegram не ретраил
                try {
                    execute(AnswerCallbackQuery.builder()
                            .callbackQueryId(cq.getId())
                            .cacheTime(2)
                            .build());
                } catch (Exception ignore) {}

                if ("pay".equals(data)) {
                    long uid = cq.getFrom().getId();
                    long now = System.currentTimeMillis();
                    long last = lastPayClickTs.getOrDefault(uid, 0L);
                    if (now - last < PAY_COOLDOWN_MS) {
                        // мягко сообщим, что инвойс уже отправлен
                        try {
                            execute(AnswerCallbackQuery.builder()
                                    .callbackQueryId(cq.getId())
                                    .text("Инвойс уже отправлен 👌")
                                    .showAlert(false)
                                    .build());
                        } catch (Exception ignore) {}
                        return;
                    }
                    lastPayClickTs.put(uid, now);
                    sendInvoice(cq.getMessage().getChatId().toString(), uid);
                    return;
                }

                if ("support".equals(data)) {
                    // Кнопка перенаправляет к чату с поддержкой
                    execute(SendMessage.builder()
                            .chatId(cq.getMessage().getChatId())
                            .text("Перейдите в чат с поддержкой: [@SpringvestaJiva](tg://resolve?domain=SpringvestaJiva)")
                            .parseMode("Markdown")
                            .build());
                    return;
                }
            }

            // === 5) Заявки в канал — автоапрув при активной подписке ===
            if (update.hasChatJoinRequest()) {
                ChatJoinRequest r = update.getChatJoinRequest();
                long userId = r.getUser().getId();
                boolean active = SubscriptionService.hasActiveSubscription(userId);
                if (active) {
                    var req = new ApproveChatJoinRequest(String.valueOf(r.getChat().getId()), userId);
                    execute(req);
                    execute(SendMessage.builder()
                            .chatId(userId)
                            .text("Ваш доступ в канал одобрен. Добро пожаловать 🌙")
                            .build());
                } else {
                    var dec = new DeclineChatJoinRequest(String.valueOf(r.getChat().getId()), userId);
                    execute(dec);
                    execute(SendMessage.builder()
                            .chatId(userId)
                            .text("У вас нет активной подписки. Нажмите «Оплатить» и повторите вход.")
                            .build());
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendStart(Long chatId) throws Exception {
        String price = Optional.ofNullable(DB.get("price_rub")).orElse("5000");

        String text = """
    ✨ Превращение начинается здесь.

    Metamorphia — это пространство глубоких трансформаций, практик и знаний 𓋹.

    Чтобы сохранить качество контента и атмосферу сообщества, канал является платным.

    Стоимость доступа: %s рублей

    Для получения доступа нажмите кнопку «Оплатить доступ» ниже.
    После успешной оплаты бот автоматически предоставит вам ссылку для входа в канал.

    Что вы получаете:
    · Доступ на месяц в закрытое пространство преображения

    Если в процессе оплаты возникли трудности, нажмите кнопку «Связь с поддержкой»

    С любовью ☾ , 𝓙𝓲𝓿𝓪.
    """.formatted(price);

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(
                List.of(InlineKeyboardButton.builder().text("🏛️ Оплатить " + price + " руб.").callbackData("pay").build()),
                List.of(InlineKeyboardButton.builder().text("📞 Связь с поддержкой").url("tg://resolve?domain=SpringvestaJiva").build())
        ));
        execute(SendMessage.builder().chatId(chatId).text(text).replyMarkup(kb).build());
    }

    private void sendMySub(Long chatId, long userId) throws Exception {
        var sub = SubscriptionService.get(userId);
        if (sub == null || !"active".equals(sub.status) || sub.endTs <= Instant.now().getEpochSecond()) {
            InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(
                    List.of(InlineKeyboardButton.builder().text("🏛️ Оплатить").callbackData("pay").build())
            ));
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("У вас нет активной подписки. Нажмите «Оплатить», чтобы оформить доступ.")
                    .replyMarkup(kb)
                    .build());
            return;
        }
        String link = DB.get("request_link");
        String end = DateTimeFormatter.ofPattern("dd.MM.yyyy")
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(sub.endTs));
        execute(SendMessage.builder()
                .chatId(chatId)
                .text("Ваша подписка активна до: " + end + "\nСсылка для входа в канал: " + link + "\nДля продления — нажмите «Оплатить».")
                .replyMarkup(new InlineKeyboardMarkup(List.of(
                        List.of(InlineKeyboardButton.builder().text("Продлить на месяц").callbackData("pay").build())
                )))
                .build());
    }

    /** Отправка инвойса через Telegram Payments (ЮKassa). */
    private void sendInvoice(String chatId, long userId) throws Exception {
        if (PROVIDER == null || PROVIDER.isBlank()) {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Платежи временно недоступны. Обратитесь в поддержку.")
                    .build());
            return;
        }
        int amountRub = Integer.parseInt(Optional.ofNullable(DB.get("price_rub")).orElse("5000"));
        List<LabeledPrice> prices = List.of(new LabeledPrice("Доступ на месяц", amountRub * 100)); // копейки
        String payload = "meta-" + userId + "-" + System.currentTimeMillis();

        SendInvoice invoice = SendInvoice.builder()
                .chatId(chatId)
                .title("Metamorphia — доступ на месяц")
                .description("Доступ на 30 дней к материалам и сообществу. После оплаты бот пришлёт приглашение.")
                .payload(payload)
                .providerToken(PROVIDER)
                .currency("RUB")
                .prices(prices)
                .startParameter("metamorphia") // обязательно, иначе NPE
                .build();

        execute(invoice);
    }
}