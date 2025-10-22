package com.metamorphia;

import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.io.Serializable;

public class BotSender {
    private static Bot INSTANCE;
    static void set(Bot bot){ INSTANCE = bot; }
    public static <T extends Serializable> T exec(BotApiMethod<T> m) throws Exception { return (T) INSTANCE.execute(m); }
    public static void send(SendMessage m) throws Exception { INSTANCE.execute(m); }
}