package com.metamorphia;

import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneId;

public class Scheduler {
    public static void start() throws Exception {
        SchedulerFactory f = new StdSchedulerFactory();
        org.quartz.Scheduler s = f.getScheduler();

        // Ежедневная проверка в 10:00 по системному времени контейнера
        JobDetail job = JobBuilder.newJob(CheckSubsJob.class).withIdentity("checkSubs").build();
        Trigger trigger = TriggerBuilder.newTrigger().withSchedule(
                CronScheduleBuilder.cronSchedule("0 0 10 * * ?")
        ).build();

        s.scheduleJob(job, trigger);
        s.start();
    }

    public static class CheckSubsJob implements Job {
        @Override public void execute(JobExecutionContext ctx) {
            try {
                long now = Instant.now().getEpochSecond();
                String channelId = DB.get("channel_id");
                // напоминания за 4 и 1 день
                try (var ps = DB.get().prepareStatement(
                        "SELECT user_id,end_ts FROM subscriptions WHERE status='active'")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            long uid = rs.getLong(1);
                            long end = rs.getLong(2);
                            long days = (end - now) / 86400;
                            if (days == 4 || days == 1) {
                                BotSender.send(SendMessage.builder()
                                        .chatId(uid)
                                        .text("Напоминание: подписка истекает через " + days + " дн. Продлите сейчас, чтобы не потерять доступ.")
                                        .build());
                            }
                            if (end <= now) {
                                // истекла — исключить и пометить
                                try {
                                    var ban = BanChatMember.builder().chatId(channelId).userId(uid).untilDate((int) (now + 60)).revokeMessages(false).build();
                                    BotSender.exec(ban);
                                } catch (Exception ignore) {}
                                SubscriptionService.expire(uid);
                                BotSender.send(SendMessage.builder().chatId(uid).text("Срок подписки истёк. Доступ в канал закрыт. Оформите продление, чтобы вернуться.").build());
                            }
                        }
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
        }
    }
}