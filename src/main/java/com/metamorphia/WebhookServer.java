package com.metamorphia;

import static spark.Spark.*;

import com.google.gson.*;

import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

public class WebhookServer {
    private static final Gson gson = new Gson();

    public static void start() {
        port(8080);

        // Глобальный обработчик исключений (на всякий случай)
        exception(Exception.class, (e, req, res) -> {
            e.printStackTrace();
            res.status(200);           // не даём ЮKassa получать 500, иначе ретраи
            res.body("OK");
        });

        path("/yookassa", () -> {

            get("/return", (req, res) -> "Спасибо! Ожидайте подтверждение оплаты.");

            post("/webhook", (req, res) -> {
                // 1) Логируем сырое тело — временно, для отладки
                System.out.println("YooKassa webhook RAW: " + req.body());

                try {
                    JsonObject event = gson.fromJson(req.body(), JsonObject.class);
                    if (event == null || !event.has("event")) {
                        System.out.println("Webhook: no 'event' field, skip.");
                        res.status(200);
                        return "OK";
                    }
                    String type = safeString(event, "event");

                    JsonObject obj = event.has("object") && event.get("object").isJsonObject()
                            ? event.getAsJsonObject("object")
                            : new JsonObject();

                    String paymentId = safeString(obj, "id");
                    String status    = safeString(obj, "status");

                    long userId = -1;
                    if (obj.has("metadata") && obj.get("metadata").isJsonObject()) {
                        JsonObject metadata = obj.getAsJsonObject("metadata");
                        if (metadata.has("user_id")) {
                            try { userId = metadata.get("user_id").getAsLong(); } catch (Exception ignore) {}
                        }
                    }

                    // 2) Обновим платеж, если id есть
                    if (paymentId != null) {
                        try (var ps = DB.get().prepareStatement("UPDATE payments SET status=? WHERE id=?")) {
                            ps.setString(1, status != null ? status : "unknown");
                            ps.setString(2, paymentId);
                            ps.executeUpdate();
                        } catch (Exception dbEx) {
                            System.out.println("DB update error: " + dbEx.getMessage());
                            dbEx.printStackTrace();
                        }
                    } else {
                        System.out.println("Webhook: no paymentId in object.");
                    }

                    // 3) Успешная оплата → активируем подписку и отправим ссылку
                    if ("payment.succeeded".equals(type) && "succeeded".equalsIgnoreCase(status) && userId > 0) {
                        try {
                            SubscriptionService.activate(userId, paymentId != null ? paymentId : "unknown");
                        } catch (Exception subEx) {
                            System.out.println("Subscription activate error: " + subEx.getMessage());
                            subEx.printStackTrace();
                        }

                        // ВАРИАНТ A: заявка (как у тебя сейчас)
                        String link = DB.get("request_link");

                        // (или ВАРИАНТ B: одноразовая ссылка — раскомментируй при необходимости)
                        // String channelId = DB.get("channel_id");
                        // String link = InviteLinkService.createOneUseLink(channelId);

                        try {
                            BotSender.send(SendMessage.builder()
                                    .chatId(userId)
                                    .text("Оплата прошла успешно ✅\nСсылка для входа в канал: " + link)
                                    .build());
                        } catch (Exception tgEx) {
                            System.out.println("Telegram send error: " + tgEx.getMessage());
                            tgEx.printStackTrace();
                        }
                    } else {
                        System.out.println("Skip: type=" + type + ", status=" + status + ", userId=" + userId);
                    }

                } catch (Exception parseEx) {
                    System.out.println("Parse/handle error: " + parseEx.getMessage());
                    parseEx.printStackTrace();
                }

                // ВСЕГДА возвращаем 200, чтобы не было ретраев и 500 в ЮKassa
                res.status(200);
                return "OK";
            });
        });
    }

    private static String safeString(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return null;
        try { return o.get(key).getAsString(); } catch (Exception e) { return null; }
    }
}