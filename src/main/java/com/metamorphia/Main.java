package com.metamorphia;

public class Main {
    public static void main(String[] args) throws Exception {
        // Инициализация БД
        DB.init(System.getenv().getOrDefault("DB_PATH", "data/app.db"));
        DB.applySchema();

        // Значения по умолчанию
        DB.putIfAbsent("price_rub", "5000");
        DB.putIfAbsent("channel_id", "");      // установим через /setgroup
        DB.putIfAbsent("request_link", "");    // создастся через /setgroup

        // Telegram бот
        Bot.start();   // Long Polling; Payments идут через апдейты

        // Планировщик напоминаний/отключений
        Scheduler.start();
    }
}