package pl.mcgramy.rpg.dzwiek.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class AudioBridgeServerTest {

    private static final String ORIGIN = "https://mcgramy.pl";

    private final AtomicLong now = new AtomicLong(System.currentTimeMillis());
    private final TokenStore tokens = new TokenStore(300_000, 1_800_000, now::get);
    private final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
    private final UUID player = UUID.randomUUID();
    private AudioBridgeServer server;
    private int port;

    @BeforeEach
    void start() throws Exception {
        startWith(new AudioBridgeServer.Settings(Set.of(ORIGIN), Set.of("127.0.0.1"),
                50, 4, 10_000, 60_000, 3, 20, 4096));
    }

    private void startWith(AudioBridgeServer.Settings settings) throws Exception {
        if (server != null) {
            server.shutdown();
        }
        AudioBridgeServer.Listener listener = new AudioBridgeServer.Listener() {
            @Override
            public void onSessionOpened(UUID p, boolean resumed) {
                events.add((resumed ? "resume:" : "open:") + p);
            }

            @Override
            public void onSessionClosed(UUID p) {
                events.add("close:" + p);
            }
        };
        server = new AudioBridgeServer(new InetSocketAddress("127.0.0.1", 0), settings, tokens, listener,
                Logger.getLogger("test"), now::get);
        server.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (!server.isStarted() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(server.isStarted());
        port = server.getPort();
        assertTrue(port > 0);
    }

    @AfterEach
    void stop() {
        server.shutdown();
    }

    /** Klient testowy zbierający wiadomości i kod zamknięcia. */
    final class Tab extends WebSocketClient {
        final BlockingQueue<JsonObject> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();

        Tab(String origin) {
            super(URI.create("ws://127.0.0.1:" + port + "/"), origin == null ? Map.of() : Map.of("Origin", origin));
        }

        @Override
        public void onOpen(ServerHandshake h) {
        }

        @Override
        public void onMessage(String message) {
            messages.add(JsonParser.parseString(message).getAsJsonObject());
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            closed.complete(code);
        }

        @Override
        public void onError(Exception ex) {
        }

        JsonObject next() throws InterruptedException {
            JsonObject o = messages.poll(3, TimeUnit.SECONDS);
            assertNotNull(o, "brak wiadomości od serwera");
            return o;
        }

        int closeCode() throws Exception {
            return closed.get(5, TimeUnit.SECONDS);
        }
    }

    private Tab open() throws Exception {
        Tab tab = new Tab(ORIGIN);
        assertTrue(tab.connectBlocking(3, TimeUnit.SECONDS));
        return tab;
    }

    private String login(Tab tab, String type, String token) throws Exception {
        tab.send("{\"t\":\"" + type + "\",\"token\":\"" + token + "\"}");
        JsonObject session = tab.next();
        assertEquals("session", session.get("t").getAsString());
        return session.get("resume").getAsString();
    }

    private void awaitEvent(String event) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (!events.contains(event) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(events.contains(event), "brak zdarzenia " + event + ", są: " + events);
    }

    @Test
    void logowanieLinkiemIWiadomosci() throws Exception {
        Tab tab = open();
        login(tab, "auth", tokens.issueLink(player));
        awaitEvent("open:" + player);
        assertTrue(server.hasSession(player));
        assertTrue(server.send(player, Protocol.music("miasto", 2000)));
        JsonObject music = tab.next();
        assertEquals("music", music.get("t").getAsString());
        assertEquals("miasto", music.get("id").getAsString());
        server.setOnline(player, true);
        assertTrue(tab.next().get("online").getAsBoolean());
        tab.closeBlocking();
        awaitEvent("close:" + player);
        assertFalse(server.hasSession(player));
    }

    @Test
    void linkDzialaTylkoRaz() throws Exception {
        String link = tokens.issueLink(player);
        Tab first = open();
        login(first, "auth", link);
        Tab second = open();
        second.send("{\"t\":\"auth\",\"token\":\"" + link + "\"}");
        assertEquals(Protocol.CLOSE_TOKEN_INVALID, second.closeCode());
        assertTrue(first.isOpen(), "nieudana próba nie może wyrzucić prawdziwej karty");
    }

    @Test
    void wznowieniePoZerwaniuIRotacja() throws Exception {
        Tab tab = open();
        String resume = login(tab, "auth", tokens.issueLink(player));
        tab.closeBlocking();
        awaitEvent("close:" + player);

        Tab again = open();
        String rotated = login(again, "resume", resume);
        assertNotEquals(resume, rotated);
        awaitEvent("resume:" + player);

        again.closeBlocking();
        Tab replay = open();
        replay.send("{\"t\":\"resume\",\"token\":\"" + resume + "\"}");
        assertEquals(Protocol.CLOSE_TOKEN_INVALID, replay.closeCode(), "stary token wznowienia nie może działać");
    }

    @Test
    void nowaKartaZastepujeStara() throws Exception {
        Tab old = open();
        String oldResume = login(old, "auth", tokens.issueLink(player));
        Tab fresh = open();
        login(fresh, "auth", tokens.issueLink(player));
        assertEquals(Protocol.CLOSE_REPLACED, old.closeCode());
        assertTrue(fresh.isOpen());
        assertTrue(server.hasSession(player));

        Tab zombie = open();
        zombie.send("{\"t\":\"resume\",\"token\":\"" + oldResume + "\"}");
        assertEquals(Protocol.CLOSE_TOKEN_INVALID, zombie.closeCode(), "stara karta nie może odbić sesji");
        assertTrue(fresh.isOpen());
    }

    @Test
    void obcyOriginOdrzucony() throws Exception {
        Tab evil = new Tab("https://zla-strona.example");
        evil.connectBlocking(3, TimeUnit.SECONDS);
        evil.closeCode();
        assertFalse(evil.isOpen());
        Tab none = new Tab(null);
        none.connectBlocking(3, TimeUnit.SECONDS);
        none.closeCode();
        assertFalse(none.isOpen());
    }

    @Test
    void brakLogowaniaWCzasie() throws Exception {
        Tab tab = open();
        now.addAndGet(10_001);
        server.housekeep();
        assertEquals(Protocol.CLOSE_AUTH_TIMEOUT, tab.closeCode());
    }

    @Test
    void zlaWiadomosc() throws Exception {
        Tab tab = open();
        tab.send("to nie json");
        assertEquals(Protocol.CLOSE_BAD_MESSAGE, tab.closeCode());
        Tab other = open();
        other.send("{\"t\":\"music\",\"id\":\"x\"}");
        assertEquals(Protocol.CLOSE_BAD_MESSAGE, other.closeCode());
    }

    @Test
    void zaDuzaRamka() throws Exception {
        Tab tab = open();
        tab.send("{\"t\":\"auth\",\"token\":\"" + "a".repeat(10_000) + "\"}");
        int code = tab.closeCode();
        assertTrue(code == 1009 || code == 1002 || code == 1006, "kod: " + code);
        assertFalse(server.hasSession(player));
    }

    @Test
    void blokadaPoNieudanychProbach() throws Exception {
        for (int i = 0; i < 4; i++) {
            Tab tab = open();
            tab.send("{\"t\":\"auth\",\"token\":\"" + "x".repeat(43) + "\"}");
            assertEquals(Protocol.CLOSE_TOKEN_INVALID, tab.closeCode());
        }
        // limit 3 na minutę: kolejne połączenie z tego IP jest odrzucane już przy otwarciu
        Tab blocked = new Tab(ORIGIN);
        blocked.connectBlocking(3, TimeUnit.SECONDS);
        blocked.closeCode();
        assertFalse(blocked.isOpen());
        // nawet z poprawnym tokenem, dopóki trwa blokada
        assertFalse(server.hasSession(player));
    }

    @Test
    void wylogowanieKomenda() throws Exception {
        Tab tab = open();
        String resume = login(tab, "auth", tokens.issueLink(player));
        server.logout(player);
        assertEquals(Protocol.CLOSE_LOGGED_OUT, tab.closeCode());
        Tab again = open();
        again.send("{\"t\":\"resume\",\"token\":\"" + resume + "\"}");
        assertEquals(Protocol.CLOSE_TOKEN_INVALID, again.closeCode());
    }

    @Test
    void dlugoPozaGra() throws Exception {
        Tab tab = open();
        String resume = login(tab, "auth", tokens.issueLink(player));
        server.setOnline(player, false);
        now.addAndGet(60_001);
        server.housekeep();
        assertEquals(Protocol.CLOSE_IDLE, tab.closeCode());
        Tab again = open();
        again.send("{\"t\":\"resume\",\"token\":\"" + resume + "\"}");
        assertEquals(Protocol.CLOSE_TOKEN_INVALID, again.closeCode());
    }

    @Test
    void wGrzeNieMaLimituCzasu() throws Exception {
        Tab tab = open();
        login(tab, "auth", tokens.issueLink(player));
        server.setOnline(player, true);
        tab.next(); // status
        now.addAndGet(5 * 3_600_000L);
        server.housekeep();
        Thread.sleep(200);
        assertTrue(tab.isOpen());
    }

    @Test
    void restartSerweraIWznowienie() throws Exception {
        Tab tab = open();
        String resume = login(tab, "auth", tokens.issueLink(player));
        startWith(new AudioBridgeServer.Settings(Set.of(ORIGIN), Set.of("127.0.0.1"),
                50, 4, 10_000, 60_000, 3, 20, 4096));
        assertEquals(1001, tab.closeCode());
        Tab again = open();
        login(again, "resume", resume);
        assertTrue(server.hasSession(player));
    }

    @Test
    void limitPolaczenZJednegoIp() throws Exception {
        for (int i = 0; i < 4; i++) {
            open();
        }
        Tab fifth = new Tab(ORIGIN);
        fifth.connectBlocking(3, TimeUnit.SECONDS);
        fifth.closeCode();
        assertFalse(fifth.isOpen());
    }
}
