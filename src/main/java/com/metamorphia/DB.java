package com.metamorphia;

import java.sql.*;
import java.nio.file.Files;
import java.nio.file.Path;

public class DB {
    private static Connection conn;

    public static void init(String path) throws Exception {
        Path p = Path.of(path).toAbsolutePath();
        Files.createDirectories(p.getParent());
        conn = DriverManager.getConnection("jdbc:sqlite:" + p);
    }

    public static void applySchema() throws Exception {
        try (var is = DB.class.getResourceAsStream("/schema.sql")) {
            String sql = new String(is.readAllBytes());
            try (Statement st = conn.createStatement()) { st.executeUpdate(sql); }
        }
    }

    public static void putIfAbsent(String key, String value) throws Exception {
        if (get(key) == null) put(key, value);
    }
    public static void put(String key, String value) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key); ps.setString(2, value); ps.executeUpdate();
        }
    }
    public static String get(String key) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM settings WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) { return rs.next()? rs.getString(1): null; }
        }
    }

    public static void upsertUser(long id, String username, String first, String last) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users(user_id,username,first_name,last_name,created_at) VALUES(?,?,?,?,strftime('%s','now')) " +
                        "ON CONFLICT(user_id) DO UPDATE SET username=excluded.username, first_name=excluded.first_name, last_name=excluded.last_name")) {
            ps.setLong(1, id); ps.setString(2, username); ps.setString(3, first); ps.setString(4, last);
            ps.executeUpdate();
        }
    }

    public static Connection get() { return conn; }
}