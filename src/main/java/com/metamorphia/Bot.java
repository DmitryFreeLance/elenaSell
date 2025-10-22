package com.metamorphia;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;

import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.ChatJoinRequest;

import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.groupadministration.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

// ДОБАВЬ/ИСПРАВЬ:
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice;
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery;

import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class Bot extends TelegramLongPollingBot {
    private static final String TOKEN      = System.getenv("BOT_TOKEN");
    private static final String USERNAME   = System.getenv("BOT_USERNAME");
    private static final String PROVIDER   = System.getenv("TG_PROVIDER_TOKEN"); // токен ЮKassa из BotFather

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
            // 1) PreCheckoutQuery ОБЯЗАТЕЛЕН
            if (update.hasPreCheckoutQuery()) {
                var pq = update.getPreCheckoutQuery();
                execute(AnswerPreCheckoutQuery.builder()
                        .preCheckoutQueryId(pq.getId())
                        .ok(true) // можно ok=false и errorMessage, если нужно отклонить
                        .build());
                return;
            }

            // 2) Успешная оплата: активируем подписку и выдаём ссылку
            if (update.hasMessage() && update.getMessage().hasSuccessfulPayment()) {
                var msg = update.getMessage();
                var user = msg.getFrom();
                long userId = user.getId();
                var sp = msg.getSuccessfulPayment();
                String paymentId = sp.getTelegramPaymentChargeId(); // можно сохранить

                // гарантируем наличие пользователя (FK)
                DB.upsertUser(userId, user.getUserName(), user.getFirstName(), user.getLastName());

                // активируем подписку на 30 дней
                SubscriptionService.activate(userId, paymentId);

                // выдаём ссылку: по умолчанию — заявка (request_link), либо раскомментируй "одноразовую" ниже
                String link = DB.get("request_link");

                // --- одноразовая ссылка (альтернатива): раскомментируй, если нужно вместо заявки ---
                // String channelId = DB.get("channel_id");
                // long expire = Instant.now().getEpochSecond() + 86400; // 24 часа
                // var req = CreateChatInviteLink.builder()
                //         .chatId(channelId)
                //         .name("One-time access")
                //         .memberLimit(1)
                //         .expireDate((int) expire)
                //         .build();
                // link = execute(req).getInviteLink();
                // -------------------------------------------------------------------------------

                execute(SendMessage.builder()
                        .chatId(userId)
                        .text("Оплата прошла успешно ✅\nСсылка для входа в канал: " + link + "\nЕсли ссылка истекла — нажмите /my_sub.")
                        .build());
                return;
            }

            // 3) Тексты, команды, коллбэки
            if (update.hasMessage() && update.getMessage().hasText()) {
                var msg = update.getMessage();
                var text = msg.getText().trim();
                var user = msg.getFrom();

                // пишущий пользователь — в БД
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
                if (text.startsWith("/setgroup")) { // /setgroup -1001234567890
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

            // Inline кнопки
            if (update.hasCallbackQuery()) {
                var cq = update.getCallbackQuery();
                var data = cq.getData();

                if (data.equals("pay")) {
                    // отправляем инвойс
                    sendInvoice(cq.getMessage().getChatId().toString(), cq.getFrom().getId());
                } else if (data.equals("support")) {
                    execute(SendMessage.builder()
                            .chatId(cq.getMessage().getChatId())
                            .text("Поддержка: @your_support_handle")
                            .build());
                }
                return;
            }

            // заявки в канал — автоапрув по активной подписке
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
        // цена из настроек
        String price = DB.get("price_rub");
        if (price == null) price = "5000";

        String text = """
    ✨ Превращение начинается здесь.

    Metamorphia — это пространство глубоких трансформаций, практик и знаний 𓋹.

    Чтобы сохранить качество контента※ и атмосферу сообщества, канал является платным.

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
                List.of(InlineKeyboardButton.builder().text("📞 Связь с поддержкой").callbackData("support").build())
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

    /** Отправка инвойса через Telegram Payments (ЮKassa) */
    private void sendInvoice(String chatId, long userId) throws Exception {
        String provider = System.getenv("TG_PROVIDER_TOKEN");
        if (provider == null || provider.isBlank()) {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Платежи временно недоступны: отсутствует TG_PROVIDER_TOKEN. Обратитесь в поддержку.")
                    .build());
            return;
        }

        int amountRub = Integer.parseInt(java.util.Optional.ofNullable(DB.get("price_rub")).orElse("5000"));
        java.util.List<LabeledPrice> prices =
                java.util.List.of(new LabeledPrice("Доступ на месяц", amountRub * 100)); // копейки

        String payload = "meta-" + userId + "-" + System.currentTimeMillis();

        SendInvoice invoice = SendInvoice.builder()
                .chatId(chatId)
                .title("Metamorphia — доступ на месяц")
                .description("Подписка на 30 дней в закрытый канал")
                .payload(payload)
                .providerToken(provider)
                .currency("RUB")
                .prices(prices)
                .startParameter("metamorphia") // <-- ОБЯЗАТЕЛЬНО, иначе NPE
                // .providerData(providerDataJson) // если понадобится чек 54-ФЗ
                .build();

        execute(invoice);
    }
}