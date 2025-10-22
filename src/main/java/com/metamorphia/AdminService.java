package com.metamorphia;

import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.api.methods.groupadministration.CreateChatInviteLink;

public class AdminService {

    // Дмитрий — админ по умолчанию + все из таблицы admins
    private static boolean isAdmin(long userId) throws Exception {
        if (userId == 726773708L) return true; // <-- твой ID
        try (var ps = DB.get().prepareStatement("SELECT 1 FROM admins WHERE user_id=?")) {
            ps.setLong(1, userId);
            try (var rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    public static void handleAdminAdd(AbsSender bot, Message msg) throws Exception {
        var parts = msg.getText().trim().split("\\s+");
        if (parts.length < 2) {
            bot.execute(SendMessage.builder().chatId(msg.getChatId()).text("Использование: /adminadd <user_id>").build());
            return;
        }
        long me = msg.getFrom().getId();
        if (!isAdmin(me)) { bot.execute(SendMessage.builder().chatId(msg.getChatId()).text("Нет прав").build()); return; }
        long uid = Long.parseLong(parts[1]);
        try (var ps = DB.get().prepareStatement("INSERT OR IGNORE INTO admins(user_id) VALUES(?)")) {
            ps.setLong(1, uid); ps.executeUpdate();
        }
        bot.execute(SendMessage.builder().chatId(msg.getChatId()).text("Админ добавлен: " + uid).build());
    }

    public static void handleSetGroup(AbsSender bot, Message msg) throws Exception {
        long me = msg.getFrom().getId();
        if (!isAdmin(me)) { bot.execute(SendMessage.builder().chatId(msg.getChatId()).text("Нет прав").build()); return; }
        var parts = msg.getText().trim().split("\\s+");
        if (parts.length < 2) {
            bot.execute(SendMessage.builder().chatId(msg.getChatId()).text("Использование: /setgroup <channel_id|@username>").build());
            return;
        }
        String raw = parts[1];
        String channelId = raw;

        // поддержка @username: резолвим в numeric id
        if (raw.startsWith("@")) {
            try {
                var get = new org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat(raw);
                var chat = bot.execute(get);
                channelId = chat.getId().toString();
            } catch (Exception e) {
                bot.execute(SendMessage.builder().chatId(msg.getChatId())
                        .text("Не удалось определить ID канала по " + raw + ": " + e.getMessage()).build());
                return;
            }
        }

        DB.put("channel_id", channelId);

        // создаём ссылку-заявку (бот должен быть админом канала)
        try {
            var req = CreateChatInviteLink.builder()
                    .chatId(channelId)
                    .createsJoinRequest(true)
                    .name("Metamorphia access")
                    .build();
            var link = bot.execute(req).getInviteLink();
            DB.put("request_link", link);

            bot.execute(SendMessage.builder().chatId(msg.getChatId())
                    .text("Канал установлен: " + channelId + "\nСсылка-заявка: " + link
                            + "\nПроверьте, что бот админ канала с правами «Приглашать» и «Одобрять заявки».")
                    .build());
        } catch (Exception e) {
            bot.execute(SendMessage.builder().chatId(msg.getChatId())
                    .text("Не удалось создать ссылку. Причина: " + e.getMessage()
                            + "\nПроверьте:\n• Бот добавлен админом канала\n• Есть права «Приглашать» и «Одобрять заявки»\n• ID корректный (обычно начинается с -100)")
                    .build());
        }
    }

    public static void handlePrice(AbsSender bot, Message msg) throws Exception {
        long me = msg.getFrom().getId();
        if (!isAdmin(me)) { bot.execute(SendMessage.builder().chatId(msg.getChatId()).text("Нет прав").build()); return; }
        var parts = msg.getText().trim().split("\\s+");
        if (parts.length < 2) {
            bot.execute(SendMessage.builder().chatId(msg.getChatId()).text("Текущая цена: " + DB.get("price_rub")).build());
            return;
        }
        DB.put("price_rub", parts[1]);
        bot.execute(SendMessage.builder().chatId(msg.getChatId()).text("Новая цена: " + parts[1]).build());
    }

    public static void handleStats(AbsSender bot, Message msg) throws Exception {
        long me = msg.getFrom().getId();
        if (!isAdmin(me)) { bot.execute(SendMessage.builder().chatId(msg.getChatId()).text("Нет прав").build()); return; }
        int totalUsers = scalarInt("SELECT COUNT(*) FROM users");
        int activeSubs = scalarInt("SELECT COUNT(*) FROM subscriptions WHERE status='active' AND end_ts>strftime('%s','now')");
        int expiring7 = scalarInt("SELECT COUNT(*) FROM subscriptions WHERE status='active' AND end_ts BETWEEN strftime('%s','now') AND strftime('%s','now','+7 day')");
        bot.execute(SendMessage.builder().chatId(msg.getChatId())
                .text("Статистика:\nПользователи: "+totalUsers+"\nАктивных подписок: "+activeSubs+"\nИстекает в 7 дней: "+expiring7)
                .build());
    }

    private static int scalarInt(String sql) throws Exception {
        try (var st = DB.get().createStatement(); var rs = st.executeQuery(sql)) { return rs.next()? rs.getInt(1):0; }
    }
}