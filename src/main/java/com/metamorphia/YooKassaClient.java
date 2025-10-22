package com.metamorphia;

import okhttp3.*;
import com.google.gson.*;

import java.io.IOException;
import java.util.UUID;

public class YooKassaClient {
    private static final OkHttpClient http = new OkHttpClient();
    private static final Gson gson = new Gson();

    public static String createPayment(long userId, int amountRub, String currency) throws Exception {
        String shopId = System.getenv("YOO_SHOP_ID");
        String secret = System.getenv("YOO_SECRET_KEY");
        String returnUrl = System.getenv("PUBLIC_BASE_URL") + "/yookassa/return?userId=" + userId;

        var payload = new JsonObject();
        var amount = new JsonObject();
        amount.addProperty("value", String.format("%d.00", amountRub));
        amount.addProperty("currency", currency);
        payload.add("amount", amount);
        payload.addProperty("capture", true);
        payload.addProperty("description", "Metamorphia monthly access for user " + userId);

        var conf = new JsonObject();
        conf.addProperty("type", "redirect");
        conf.addProperty("return_url", returnUrl);
        payload.add("confirmation", conf);

        // Метаданные — чтобы в webhook понять чей платёж
        var meta = new JsonObject();
        meta.addProperty("user_id", userId);
        payload.add("metadata", meta);

        Request request = new Request.Builder()
                .url("https://api.yookassa.ru/v3/payments")
                .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")))
                .addHeader("Idempotence-Key", UUID.randomUUID().toString())
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", Credentials.basic(shopId, secret))
                .build();

        try (Response resp = http.newCall(request).execute()) {
            if (!resp.isSuccessful()) throw new IOException("YooKassa error: " + resp.code() + " " + resp.message());
            var body = gson.fromJson(resp.body().string(), JsonObject.class);
            String paymentId = body.get("id").getAsString();
            String confUrl = body.getAsJsonObject("confirmation").get("confirmation_url").getAsString();

            // Сохраняем платеж
            try (var ps = DB.get().prepareStatement(
                    "INSERT OR REPLACE INTO payments(id,user_id,amount,currency,status,created_ts,description) VALUES(?,?,?,?,?,strftime('%s','now'),?)")) {
                ps.setString(1, paymentId);
                ps.setLong(2, userId);
                ps.setInt(3, amountRub);
                ps.setString(4, currency);
                ps.setString(5, body.get("status").getAsString());
                ps.setString(6, "Metamorphia monthly");
                ps.executeUpdate();
            }
            return confUrl;
        }
    }
}