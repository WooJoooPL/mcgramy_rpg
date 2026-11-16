package pl.mcgramy.rpg.dzwiek.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Serwer do testów strony bez Minecrafta (tylko na 127.0.0.1).
 * Udaje grę: wydaje linki, zmienia muzykę, wysyła efekty, restartuje serwer dźwięku.
 *
 * <pre>
 * java -cp ... pl.mcgramy.rpg.dzwiek.core.DevServer ../web
 * http://127.0.0.1:8786/__dev/link            nowy link (token)
 * http://127.0.0.1:8786/__dev/online?v=true   gracz wszedł do gry
 * http://127.0.0.1:8786/__dev/music?id=demo_boss&amp;fade=2000
 * http://127.0.0.1:8786/__dev/sfx?id=demo_ryk
 * http://127.0.0.1:8786/__dev/restart | logout | state
 * </pre>
 */
public final class DevServer implements AudioBridgeServer.Listener {

    static final int HTTP_PORT = 8786;
    static final int WS_PORT = 8787;
    static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private final Logger log = Logger.getLogger("dev");
    private final TokenStore tokens = new TokenStore(300_000, 1_800_000);
    private final Path web;
    private volatile AudioBridgeServer bridge;
    private volatile String music;
    private volatile boolean online;

    DevServer(Path web) {
        this.web = web.toAbsolutePath().normalize();
    }

    public static void main(String[] args) throws Exception {
        Path web = Path.of(args.length > 0 ? args[0] : "../web");
        new DevServer(web).run();
    }

    private void run() throws IOException {
        startBridge();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", HTTP_PORT), 0);
        http.createContext("/__dev/", this::control);
        http.createContext("/", this::file);
        http.start();
        System.out.println("DEV http://127.0.0.1:" + HTTP_PORT + "/  ws://127.0.0.1:" + WS_PORT);
    }

    private void startBridge() {
        AudioBridgeServer.Settings settings = new AudioBridgeServer.Settings(
                Set.of("http://127.0.0.1:" + HTTP_PORT, "http://localhost:" + HTTP_PORT),
                Set.of(), 50, 8, 10_000, 15 * 60_000, 20, 20, 4096);
        AudioBridgeServer server = new AudioBridgeServer(new InetSocketAddress("127.0.0.1", WS_PORT),
                settings, tokens, this, log);
        server.start();
        bridge = server;
        if (online) {
            server.setOnline(PLAYER, true);
        }
    }

    @Override
    public void onSessionOpened(UUID player, boolean resumed) {
        bridge.send(player, Protocol.hello("TestowyGracz", bridge.isOnline(player), "audio/", catalog()));
        bridge.send(player, Protocol.music(music, 1500));
    }

    @Override
    public void onSessionClosed(UUID player) {
    }

    static JsonObject catalog() {
        JsonObject c = new JsonObject();
        c.add("demo_miasto", entry("music", "Miasto o świcie", "muzyka/demo_miasto", 0.8));
        c.add("demo_dzicz", entry("music", "Dzika kraina", "muzyka/demo_dzicz", 0.8));
        c.add("demo_boss", entry("music", "Walka z bossem", "muzyka/demo_boss", 0.9));
        c.add("demo_dzwon", entry("sfx", null, "efekty/demo_dzwon", 1.0));
        c.add("demo_ryk", entry("sfx", null, "efekty/demo_ryk", 1.0));
        return c;
    }

    private static JsonObject entry(String kind, String title, String path, double volume) {
        JsonObject e = new JsonObject();
        e.addProperty("k", kind);
        JsonArray src = new JsonArray();
        src.add(path + ".ogg");
        src.add(path + ".m4a");
        e.add("src", src);
        e.addProperty("v", volume);
        if (title != null) {
            e.addProperty("title", title);
        }
        return e;
    }

    private void control(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        String action = ex.getRequestURI().getPath().substring("/__dev/".length());
        String body;
        switch (action) {
            case "link" -> body = tokens.issueLink(PLAYER);
            case "online" -> {
                online = Boolean.parseBoolean(q.getOrDefault("v", "true"));
                bridge.setOnline(PLAYER, online);
                body = "online=" + online;
            }
            case "music" -> {
                String id = q.get("id");
                music = id == null || id.equals("none") ? null : id;
                bridge.send(PLAYER, Protocol.music(music, Integer.parseInt(q.getOrDefault("fade", "2000"))));
                body = "music=" + music;
            }
            case "sfx" -> body = "sent=" + bridge.send(PLAYER, Protocol.sfx(q.get("id"), 1.0));
            case "restart" -> {
                bridge.shutdown();
                startBridge();
                body = "restarted";
            }
            case "logout" -> {
                bridge.logout(PLAYER);
                body = "logout";
            }
            case "state" -> body = "sessions=" + bridge.sessionCount() + " tokens=" + tokens.size()
                    + " started=" + bridge.isStarted();
            default -> body = "?";
        }
        reply(ex, 200, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private void file(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/")) {
            path += "index.html";
        }
        Path file = web.resolve(path.substring(1)).normalize();
        if (!file.startsWith(web) || !Files.isRegularFile(file)) {
            reply(ex, 404, "text/plain", "404".getBytes(StandardCharsets.UTF_8));
            return;
        }
        reply(ex, 200, contentType(file.getFileName().toString()), Files.readAllBytes(file));
    }

    private static String contentType(String name) {
        Map<String, String> types = new HashMap<>();
        types.put("html", "text/html; charset=utf-8");
        types.put("js", "text/javascript; charset=utf-8");
        types.put("css", "text/css; charset=utf-8");
        types.put("ogg", "audio/ogg");
        types.put("m4a", "audio/mp4");
        String ext = name.substring(name.lastIndexOf('.') + 1);
        return types.getOrDefault(ext, "application/octet-stream");
    }

    private static void reply(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null) {
            return map;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                map.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }
}
