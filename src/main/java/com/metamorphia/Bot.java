package com.metamorphia;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.ChatJoinRequest;
import org.telegram.telegrambots.meta.api.methods.groupadministration.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class Bot extends TelegramLongPollingBot {
    private static final String TOKEN = System.getenv("BOT_TOKEN");
    private static final String USERNAME = System.getenv("BOT_USERNAME");

    public static void start() throws Exception {
        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        Bot bot = new Bot();
        api.registerBot(bot);
        BotSender.set(bot); // <-- ВАЖНО! иначе отправка из вебхука рухнет
    }

    @Override public String getBotToken() { return TOKEN; }
    @Override public String getBotUsername() { return USERNAME; }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMessage() && update.getMessage().hasText()) {
                var msg = update.getMessage();
                var text = msg.getText().trim();
                var user = msg.getFrom();

                // ensure user in DB
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

            // Inline кнопки (оплата)
            if (update.hasCallbackQuery()) {
                var cq = update.getCallbackQuery();
                var data = cq.getData();
                if (data.equals("pay")) {
                    int amountRub = Integer.parseInt(DB.get("price_rub"));
                    String paymentUrl = YooKassaClient.createPayment(cq.getFrom().getId(), amountRub, "RUB");
                    execute(SendMessage.builder()
                            .chatId(cq.getMessage().getChatId())
                            .text("Ссылка для оплаты на месяц: " + paymentUrl + "\nПосле оплаты вернитесь в чат — бот всё сделает автоматически ✨")
                            .build());
                } else if (data.equals("support")) {
                    execute(SendMessage.builder()
                            .chatId(cq.getMessage().getChatId())
                            .text("Поддержка: @your_support_handle")
                            .build());
                }
            }

            // Заявки в канал — approve/decline по подписке
            if (update.hasChatJoinRequest()) {
                ChatJoinRequest r = update.getChatJoinRequest();
                long userId = r.getUser().getId();
                boolean active = SubscriptionService.hasActiveSubscription(userId);
                if (active) {
                    var req = new ApproveChatJoinRequest(String.valueOf(r.getChat().getId()), userId);
                    execute(req);
                    // приветствие в ЛС
                    execute(SendMessage.builder()
                            .chatId(userId)
                            .text("Ваш доступ в канал одобрен. Добро пожаловать 🌙")
                            .build());
                } else {
                    var dec = new DeclineChatJoinRequest(String.valueOf(r.getChat().getId()), userId);
                    execute(dec);
                    execute(SendMessage.builder()
                            .chatId(userId)
                            .text("У вас нет активной подписки. Оформите оплату и повторите вход.")
                            .build());
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendStart(Long chatId) throws Exception {
        String text = """
    ✨ Превращение начинается здесь.

    Metamorphia — это пространство глубоких трансформаций, практик и знаний 𓋹.

    Чтобы сохранить качество контента※ и атмосферу сообщества, канал является платным.

    Стоимость доступа: 5000 рублей

    Для получения доступа нажмите кнопку «Оплатить доступ» ниже.
    После успешной оплаты бот автоматически предоставит вам ссылку для входа в канал.

    Что вы получаете:
    · Доступ на месяц в закрытое пространство преображения

    Если в процессе оплаты возникли трудности, нажмите кнопку «Связь с поддержкой»

    С любовью ☾ , 𝓙𝓲𝓿𝓪.
    """;
        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(
                List.of(InlineKeyboardButton.builder().text("🏛️ Оплатить 5000 руб.").callbackData("pay").build()),
                List.of(InlineKeyboardButton.builder().text("📞 Связь с поддержкой").callbackData("support").build())
        ));
        execute(SendMessage.builder().chatId(chatId).text(text).replyMarkup(kb).build());
    }

    private void sendMySub(Long chatId, long userId) throws Exception {
        var sub = SubscriptionService.get(userId);
        if (sub == null || !"active".equals(sub.status)) {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("У вас нет активной подписки. Нажмите «Оплатить», чтобы оформить доступ.")
                    .replyMarkup(new InlineKeyboardMarkup(List.of(
                            List.of(InlineKeyboardButton.builder().text("🏛️ Оплатить").callbackData("pay").build())
                    )))
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
}