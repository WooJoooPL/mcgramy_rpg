package pl.mcgramy.rpg.dzwiek.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serwer WebSocket, z którym łączy się strona z dźwiękiem. Stoi za nginx (TLS), który
 * przekazuje {@code wss://.../ws} na ten port.
 *
 * <p>Jedna karta na gracza. Pierwsza wiadomość karty musi być logowaniem tokenem
 * ({@code auth} z linku albo {@code resume} po zerwaniu połączenia). Do tego czasu karta
 * nic nie dostaje. Metody {@code send*} są bezpieczne wątkowo i można je wołać z głównego
 * wątku serwera Minecraft.
 */
public final class AudioBridgeServer extends WebSocketServer {

    /** Wywołania zwrotne do gry. Przychodzą z wątków WebSocket, nie z głównego wątku serwera. */
    public interface Listener {
        /** Karta gracza zalogowała się. Wyślij {@code hello} i bieżący stan. */
        void onSessionOpened(UUID player, boolean resumed);

        /** Karta gracza rozłączyła się (zamknięta, zerwana, zastąpiona przez wylogowanie). */
        void onSessionClosed(UUID player);
    }

    /**
     * @param allowedOrigins     dozwolone nagłówki Origin (pusty zbiór = bez sprawdzania, tylko do testów)
     * @param trustedProxies     adresy, od których przyjmujemy nagłówek X-Real-IP (nginx)
     * @param maxFrameBytes      największa przyjmowana ramka od strony
     */
    public record Settings(
            Set<String> allowedOrigins,
            Set<String> trustedProxies,
            int maxConnections,
            int maxConnectionsPerIp,
            long authTimeoutMs,
            long offlineTimeoutMs,
            int maxFailedAuthPerMinute,
            int maxMessagesPer10s,
            int maxFrameBytes) {

        public static Settings defaults() {
            return new Settings(Set.of(), Set.of("127.0.0.1"), 500, 4, 10_000, 15 * 60_000, 10, 20, 4096);
        }
    }

    /** Stan jednego połączenia (załącznik WebSocket). */
    static final class Conn {
        final String ip;
        final long openedAt;
        volatile UUID player;
        volatile boolean authAttempted;
        // Wiadomości jednego połączenia obsługuje zawsze ten sam wątek, ale dla pewności synchronizujemy.
        private long windowStart;
        private int messages;

        Conn(String ip, long openedAt) {
            this.ip = ip;
            this.openedAt = openedAt;
        }

        synchronized boolean countMessage(long now, int limit) {
            if (now - windowStart >= 10_000) {
                windowStart = now;
                messages = 0;
            }
            return ++messages <= limit;
        }
    }

    private final TokenStore tokens;
    private final Listener listener;
    private final Logger logger;
    private final LongSupplier clock;
    private volatile Settings settings;
    private final RateLimiter failedAuth;

    private final Map<UUID, WebSocket> sessions = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> perIp = new ConcurrentHashMap<>();
    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> offlineSince = new ConcurrentHashMap<>();
    private final ScheduledExecutorService housekeeping;
    private volatile boolean started;

    public AudioBridgeServer(InetSocketAddress address, Settings settings, TokenStore tokens,
                             Listener listener, Logger logger) {
        this(address, settings, tokens, listener, logger, System::currentTimeMillis);
    }

    AudioBridgeServer(InetSocketAddress address, Settings settings, TokenStore tokens,
                      Listener listener, Logger logger, LongSupplier clock) {
        super(address, 2, List.<Draft>of(new Draft_6455(Collections.emptyList(), settings.maxFrameBytes())));
        this.tokens = tokens;
        this.listener = listener;
        this.logger = logger;
        this.clock = clock;
        this.settings = settings;
        this.failedAuth = new RateLimiter(settings.maxFailedAuthPerMinute(), 60_000, clock);
        setReuseAddr(true);
        setTcpNoDelay(true);
        setConnectionLostTimeout(30);
        setDaemon(true);
        this.housekeeping = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "McRPGDzwiek-porzadki");
            t.setDaemon(true);
            return t;
        });
    }

    /** Uruchamia serwer i sprzątanie. Błąd bindowania portu trafia do logu przez {@link #onError}. */
    @Override
    public void start() {
        super.start();
        housekeeping.scheduleAtFixedRate(this::housekeep, 1, 1, TimeUnit.SECONDS);
    }

    /** Zatrzymuje serwer. Karty dostają kod 1001 i łączą się ponownie po restarcie. */
    public void shutdown() {
        housekeeping.shutdownNow();
        try {
            stop(1000, "restart");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isStarted() {
        return started;
    }

    public void updateSettings(Settings settings) {
        this.settings = settings;
    }

    // ------------------------------------------------------------------ API dla gry

    /** Czy gracz ma teraz połączoną kartę. */
    public boolean hasSession(UUID player) {
        WebSocket conn = sessions.get(player);
        return conn != null && conn.isOpen();
    }

    public int sessionCount() {
        return sessions.size();
    }

    public Set<UUID> sessionPlayers() {
        return Collections.unmodifiableSet(sessions.keySet());
    }

    /** Wysyła gotową wiadomość do karty gracza. Zwraca {@code false}, gdy gracz nie ma karty. */
    public boolean send(UUID player, String json) {
        WebSocket conn = sessions.get(player);
        if (conn == null || !conn.isOpen()) {
            return false;
        }
        try {
            conn.send(json);
            return true;
        } catch (WebsocketNotConnectedException e) {
            return false;
        }
    }

    /** Gracz wszedł do gry lub z niej wyszedł. Karta dostaje {@code status}. */
    public void setOnline(UUID player, boolean isOnline) {
        if (isOnline) {
            online.add(player);
            offlineSince.remove(player);
        } else {
            online.remove(player);
            offlineSince.put(player, clock.getAsLong());
        }
        send(player, Protocol.status(isOnline));
    }

    public boolean isOnline(UUID player) {
        return online.contains(player);
    }

    /** Wylogowanie: unieważnia wszystkie tokeny gracza i zamyka jego kartę. */
    public void logout(UUID player) {
        tokens.revokeAll(player);
        WebSocket conn = sessions.get(player);
        if (conn != null) {
            conn.close(Protocol.CLOSE_LOGGED_OUT, "logout");
        }
    }

    // ------------------------------------------------------------------ WebSocket

    @Override
    public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(WebSocket conn, Draft draft,
                                                                       ClientHandshake request)
            throws InvalidDataException {
        ServerHandshakeBuilder builder = super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
        Settings s = settings;
        if (!s.allowedOrigins().isEmpty()) {
            String origin = request.getFieldValue("Origin");
            if (!s.allowedOrigins().contains(origin)) {
                throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "origin");
            }
        }
        String ip = clientIp(conn, request, s);
        if (getConnections().size() >= s.maxConnections()) {
            throw new InvalidDataException(CloseFrame.TRY_AGAIN_LATER, "full");
        }
        AtomicInteger count = perIp.get(ip);
        if (count != null && count.get() >= s.maxConnectionsPerIp()) {
            throw new InvalidDataException(CloseFrame.TRY_AGAIN_LATER, "ip");
        }
        if (failedAuth.blocked(ip)) {
            throw new InvalidDataException(CloseFrame.TRY_AGAIN_LATER, "rate");
        }
        conn.setAttachment(new Conn(ip, clock.getAsLong()));
        return builder;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        Conn c = conn.getAttachment();
        if (c == null) {
            conn.close(CloseFrame.POLICY_VALIDATION, "state");
            return;
        }
        perIp.computeIfAbsent(c.ip, k -> new AtomicInteger()).incrementAndGet();
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Conn c = conn.getAttachment();
        if (c == null) {
            return;
        }
        perIp.computeIfPresent(c.ip, (k, v) -> v.decrementAndGet() <= 0 ? null : v);
        UUID player = c.player;
        // Tylko aktualna karta gracza. Karta zastąpiona przez nową nie rusza tokenów nowej.
        if (player != null && sessions.remove(player, conn)) {
            tokens.startResumeCountdown(player);
            safeListener(() -> listener.onSessionClosed(player));
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        Conn c = conn.getAttachment();
        if (c == null) {
            conn.close(CloseFrame.POLICY_VALIDATION, "state");
            return;
        }
        if (!c.countMessage(clock.getAsLong(), settings.maxMessagesPer10s())) {
            conn.close(Protocol.CLOSE_RATE_LIMIT, "rate");
            return;
        }
        JsonObject obj = parse(message);
        if (obj == null) {
            conn.close(Protocol.CLOSE_BAD_MESSAGE, "bad");
            return;
        }
        String type = string(obj, "t");
        if (c.player != null) {
            // Po zalogowaniu strona nic nie musi wysyłać. Inne wiadomości pomijamy.
            return;
        }
        if (c.authAttempted) {
            conn.close(Protocol.CLOSE_BAD_MESSAGE, "bad");
            return;
        }
        if ("auth".equals(type)) {
            authenticate(conn, c, string(obj, "token"), TokenStore.Kind.LINK);
        } else if ("resume".equals(type)) {
            authenticate(conn, c, string(obj, "token"), TokenStore.Kind.RESUME);
        } else {
            conn.close(Protocol.CLOSE_BAD_MESSAGE, "bad");
        }
    }

    /**
     * Pingi od klienta też liczą się do limitu wiadomości: każdy ping wymusza odpowiedź,
     * więc bez limitu klient, który nie czyta, zapychałby pamięć kolejką odpowiedzi.
     * Przeglądarki same nie wysyłają pingów, więc zwykłej karty to nie dotyczy.
     */
    @Override
    public void onWebsocketPing(WebSocket conn, Framedata frame) {
        Conn c = conn.getAttachment();
        if (c == null || !c.countMessage(clock.getAsLong(), settings.maxMessagesPer10s())) {
            conn.close(Protocol.CLOSE_RATE_LIMIT, "rate");
            return;
        }
        super.onWebsocketPing(conn, frame);
    }

    @Override
    public void onMessage(WebSocket conn, java.nio.ByteBuffer message) {
        conn.close(Protocol.CLOSE_BAD_MESSAGE, "binary");
    }

    private void authenticate(WebSocket conn, Conn c, String token, TokenStore.Kind kind) {
        c.authAttempted = true;
        if (failedAuth.blocked(c.ip)) {
            conn.close(Protocol.CLOSE_RATE_LIMIT, "rate");
            return;
        }
        UUID player = tokens.consume(token, kind);
        if (player == null) {
            failedAuth.hit(c.ip);
            conn.close(Protocol.CLOSE_TOKEN_INVALID, "token");
            return;
        }
        c.player = player;
        WebSocket previous = sessions.put(player, conn);
        if (previous != null && previous != conn) {
            previous.close(Protocol.CLOSE_REPLACED, "replaced");
        }
        if (!online.contains(player)) {
            offlineSince.putIfAbsent(player, clock.getAsLong());
        }
        String resume = tokens.issueResume(player);
        try {
            conn.send(Protocol.session(resume));
        } catch (WebsocketNotConnectedException e) {
            return;
        }
        boolean resumed = kind == TokenStore.Kind.RESUME;
        safeListener(() -> listener.onSessionOpened(player, resumed));
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn == null) {
            logger.log(Level.SEVERE, "Serwer dźwięku nie działa (zajęty port albo zły adres?)", ex);
        } else if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, "Błąd połączenia dźwięku", ex);
        }
    }

    @Override
    public void onStart() {
        started = true;
        logger.info("Serwer dźwięku nasłuchuje na " + getAddress().getHostString() + ":" + getPort());
    }

    // ------------------------------------------------------------------ porządki

    private long lastCleanup;

    void housekeep() {
        try {
            long now = clock.getAsLong();
            Settings s = settings;
            Collection<WebSocket> connections = getConnections();
            for (WebSocket conn : connections) {
                Conn c = conn.getAttachment();
                if (c != null && c.player == null && now - c.openedAt > s.authTimeoutMs()) {
                    conn.close(Protocol.CLOSE_AUTH_TIMEOUT, "timeout");
                }
            }
            for (Map.Entry<UUID, WebSocket> e : sessions.entrySet()) {
                UUID player = e.getKey();
                if (online.contains(player)) {
                    continue;
                }
                long since = offlineSince.computeIfAbsent(player, k -> now);
                if (now - since > s.offlineTimeoutMs()) {
                    tokens.revoke(player, TokenStore.Kind.RESUME);
                    e.getValue().close(Protocol.CLOSE_IDLE, "idle");
                }
            }
            if (now - lastCleanup >= 60_000) {
                lastCleanup = now;
                tokens.cleanup();
                failedAuth.cleanup();
                // Nie czyścimy od razu: ponowne połączenie tokenem wznowienia nie może zerować
                // licznika czasu poza grą. Wpisy bez karty usuwamy dopiero po dobie.
                long keep = s.offlineTimeoutMs() + 24 * 3_600_000L;
                offlineSince.entrySet().removeIf(e -> !sessions.containsKey(e.getKey())
                        && !online.contains(e.getKey()) && now - e.getValue() > keep);
            }
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "Błąd porządków serwera dźwięku", ex);
        }
    }

    // ------------------------------------------------------------------ pomocnicze

    static String clientIp(WebSocket conn, ClientHandshake request, Settings s) {
        InetSocketAddress remote = conn.getRemoteSocketAddress();
        String direct = remote == null || remote.getAddress() == null ? "?" : remote.getAddress().getHostAddress();
        if (s.trustedProxies().contains(direct)) {
            String real = request.getFieldValue("X-Real-IP");
            if (real != null && !real.isBlank() && real.length() <= 45) {
                return real.trim();
            }
        }
        return direct;
    }

    private static JsonObject parse(String message) {
        try {
            JsonElement el = JsonParser.parseString(message);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String string(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() ? el.getAsString() : null;
    }

    private void safeListener(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "Błąd obsługi sesji dźwięku", ex);
        }
    }
}
