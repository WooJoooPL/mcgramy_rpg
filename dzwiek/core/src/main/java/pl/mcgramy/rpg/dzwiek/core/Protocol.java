package pl.mcgramy.rpg.dzwiek.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Protokół między serwerem a stroną. Każda wiadomość to obiekt JSON z polem {@code t} (typ).
 *
 * <p>Serwer do strony: {@code session}, {@code hello}, {@code music}, {@code sfx}, {@code status}.
 * Strona do serwera: tylko {@code auth} lub {@code resume} jako pierwsza wiadomość.
 * Opis wszystkich pól jest w {@code dzwiek/README.md}.
 */
public final class Protocol {

    /** Zły, zużyty albo przeterminowany token. Strona prosi o nowy link. */
    public static final int CLOSE_TOKEN_INVALID = 4001;
    /** Gracz otworzył dźwięk w innej karcie. Strona nie łączy się ponownie. */
    public static final int CLOSE_REPLACED = 4002;
    /** Brak logowania w wyznaczonym czasie. */
    public static final int CLOSE_AUTH_TIMEOUT = 4003;
    /** Zła wiadomość (nie JSON, nieznany typ przed zalogowaniem). */
    public static final int CLOSE_BAD_MESSAGE = 4004;
    /** Gracz zbyt długo poza grą. */
    public static final int CLOSE_IDLE = 4005;
    /** Gracz rozłączył dźwięk komendą w grze. */
    public static final int CLOSE_LOGGED_OUT = 4006;
    /** Za dużo wiadomości lub nieudanych logowań. */
    public static final int CLOSE_RATE_LIMIT = 4008;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private Protocol() {
    }

    public static String session(String resumeToken) {
        JsonObject o = type("session");
        o.addProperty("resume", resumeToken);
        return GSON.toJson(o);
    }

    /**
     * Powitanie po zalogowaniu.
     *
     * @param catalog obiekt {@code id -> {k, src[], v, title}} przygotowany przez plugin
     */
    public static String hello(String playerName, boolean online, String audioBase, JsonElement catalog) {
        JsonObject o = type("hello");
        o.addProperty("name", playerName);
        o.addProperty("online", online);
        o.addProperty("base", audioBase);
        o.add("catalog", catalog);
        return GSON.toJson(o);
    }

    /** Zmiana muzyki. {@code trackId == null} oznacza ciszę. */
    public static String music(String trackId, int fadeMs) {
        JsonObject o = type("music");
        o.addProperty("id", trackId);
        o.addProperty("fade", Math.max(0, fadeMs));
        return GSON.toJson(o);
    }

    /** Jednorazowy efekt dźwiękowy. */
    public static String sfx(String soundId, double volume) {
        JsonObject o = type("sfx");
        o.addProperty("id", soundId);
        o.addProperty("vol", Math.max(0.0, Math.min(2.0, volume)));
        return GSON.toJson(o);
    }

    /** Gracz wszedł do gry albo z niej wyszedł. */
    public static String status(boolean online) {
        JsonObject o = type("status");
        o.addProperty("online", online);
        return GSON.toJson(o);
    }

    private static JsonObject type(String t) {
        JsonObject o = new JsonObject();
        o.addProperty("t", t);
        return o;
    }
}
