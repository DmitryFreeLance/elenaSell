package com.metamorphia;

import com.metamorphia.Models.Subscription;

import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class SubscriptionService {
    public static Subscription get(long userId) throws Exception {
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT user_id,start_ts,end_ts,status,last_payment_id FROM subscriptions WHERE user_id=?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Subscription s = new Subscription();
                s.userId = rs.getLong(1);
                s.startTs = rs.getLong(2);
                s.endTs = rs.getLong(3);
                s.status = rs.getString(4);
                s.lastPaymentId = rs.getString(5);
                return s;
            }
        }
    }

    public static boolean hasActiveSubscription(long userId) throws Exception {
        var s = get(userId);
        long now = Instant.now().getEpochSecond();
        return s != null && "active".equals(s.status) && s.endTs > now;
    }

    public static void activate(long userId, String paymentId) throws Exception {
        long now = Instant.now().getEpochSecond();
        long end = Instant.now().plus(30, ChronoUnit.DAYS).getEpochSecond();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "INSERT INTO subscriptions(user_id,start_ts,end_ts,status,last_payment_id) VALUES(?,?,?,?,?) " +
                        "ON CONFLICT(user_id) DO UPDATE SET start_ts=excluded.start_ts, end_ts=excluded.end_ts, status='active', last_payment_id=?")) {
            ps.setLong(1, userId); ps.setLong(2, now); ps.setLong(3, end); ps.setString(4, "active"); ps.setString(5, paymentId);
            ps.setString(6, paymentId);
            ps.executeUpdate();
        }
    }

    public static void expire(long userId) throws Exception {
        try (PreparedStatement ps = DB.get().prepareStatement(
                "UPDATE subscriptions SET status='expired' WHERE user_id=?")) {
            ps.setLong(1, userId); ps.executeUpdate();
        }
    }
}