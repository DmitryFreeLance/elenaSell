package com.metamorphia;

import static spark.Spark.*;

import com.google.gson.*;

import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

public class WebhookServer {
    private static final Gson gson = new Gson();

    public static void start() {
        port(8080);

        path("/yookassa", () -> {
            // redirect return (не обязателен для логики — просто «спасибо»)
            get("/return", (req, res) -> "Спасибо! Ожидайте подтверждение оплаты.");

            // Webhook уведомления
            post("/webhook", (req, res) -> {
                JsonObject event = gson.fromJson(req.body(), JsonObject.class);
                String type = event.get("event").getAsString();           // payment.succeeded / payment.canceled
                JsonObject obj = event.getAsJsonObject("object");
                String paymentId = obj.get("id").getAsString();
                String status = obj.get("status").getAsString();
                JsonObject metadata = obj.has("metadata")? obj.getAsJsonObject("metadata"): new JsonObject();
                long userId = metadata.has("user_id")? metadata.get("user_id").getAsLong(): -1;

                // Обновим статус платежа
                try (var ps = DB.get().prepareStatement("UPDATE payments SET status=? WHERE id=?")) {
                    ps.setString(1, status); ps.setString(2, paymentId); ps.executeUpdate();
                }

                if ("payment.succeeded".equals(type) && "succeeded".equals(status) && userId>0) {
                    // Активируем подписку
                    SubscriptionService.activate(userId, paymentId);

                    // Отправим ссылку-заявку в канал
                    String link = DB.get("request_link");
                    BotSender.send(SendMessage.builder()
                            .chatId(userId)
                            .text("Оплата прошла успешно ✅\nСсылка для входа в канал: " + link + "\nНажмите и подайте заявку — я одобрю автоматически.")
                            .build());
                }
                res.status(200);
                return "OK";
            });
        });
    }
}