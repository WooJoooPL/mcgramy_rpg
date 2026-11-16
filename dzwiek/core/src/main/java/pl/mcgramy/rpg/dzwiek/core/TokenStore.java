package pl.mcgramy.rpg.dzwiek.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Jednorazowe tokeny powiązane z kontem gracza.
 *
 * <p>Trzymamy tylko skrót SHA-256 tokenu, nigdy sam token. Każde użycie usuwa wpis,
 * więc token działa dokładnie raz. Rodzaje:
 * <ul>
 *   <li>{@link Kind#LINK}: z linku na czacie, krótki czas życia (domyślnie 5 min).</li>
 *   <li>{@link Kind#RESUME}: do ponownego połączenia tej samej karty. Ważny, dopóki karta
 *       jest połączona, a po rozłączeniu jeszcze przez {@code resumeTtlMs}. Wymieniany przy
 *       każdym użyciu.</li>
 * </ul>
 */
public final class TokenStore {

    public enum Kind { LINK, RESUME }

    /** 32 losowe bajty w base64url bez dopełnienia = 43 znaki. */
    public static final int TOKEN_LENGTH = 43;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{" + TOKEN_LENGTH + "}$");
    /** Znacznik „ważny bez limitu, dopóki karta jest połączona”. */
    static final long WHILE_CONNECTED = Long.MAX_VALUE;

    private record Entry(UUID player, Kind kind, long expiresAt) { }

    private final Map<String, Entry> byHash = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final LongSupplier clock;
    private volatile long linkTtlMs;
    private volatile long resumeTtlMs;

    public TokenStore(long linkTtlMs, long resumeTtlMs) {
        this(linkTtlMs, resumeTtlMs, System::currentTimeMillis);
    }

    public TokenStore(long linkTtlMs, long resumeTtlMs, LongSupplier clock) {
        this.clock = clock;
        setTtl(linkTtlMs, resumeTtlMs);
    }

    public void setTtl(long linkTtlMs, long resumeTtlMs) {
        if (linkTtlMs <= 0 || resumeTtlMs <= 0) {
            throw new IllegalArgumentException("Czas życia tokenu musi być dodatni");
        }
        this.linkTtlMs = linkTtlMs;
        this.resumeTtlMs = resumeTtlMs;
    }

    /** Nowy token linku. Unieważnia poprzedni nieużyty link tego gracza. */
    public String issueLink(UUID player) {
        return issue(player, Kind.LINK, clock.getAsLong() + linkTtlMs);
    }

    /** Nowy token wznowienia, ważny tak długo, jak karta jest połączona. Unieważnia poprzedni. */
    public String issueResume(UUID player) {
        return issue(player, Kind.RESUME, WHILE_CONNECTED);
    }

    private String issue(UUID player, Kind kind, long expiresAt) {
        revoke(player, kind);
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        byHash.put(hash(token), new Entry(player, kind, expiresAt));
        return token;
    }

    /**
     * Zużywa token. Zwraca gracza albo {@code null}, gdy token jest nieznany, przeterminowany
     * lub innego rodzaju. Wpis jest usuwany zawsze, także przy odmowie.
     */
    public UUID consume(String token, Kind kind) {
        if (token == null || !TOKEN_PATTERN.matcher(token).matches()) {
            return null;
        }
        Entry entry = byHash.remove(hash(token));
        if (entry == null || entry.kind() != kind || entry.expiresAt() < clock.getAsLong()) {
            return null;
        }
        return entry.player();
    }

    /** Po rozłączeniu karty token wznowienia zaczyna wygasać. */
    public void startResumeCountdown(UUID player) {
        long expiresAt = clock.getAsLong() + resumeTtlMs;
        byHash.replaceAll((hash, entry) -> entry.player().equals(player) && entry.kind() == Kind.RESUME
                && entry.expiresAt() == WHILE_CONNECTED
                ? new Entry(player, Kind.RESUME, expiresAt) : entry);
    }

    public void revoke(UUID player, Kind kind) {
        byHash.values().removeIf(entry -> entry.player().equals(player) && entry.kind() == kind);
    }

    public void revokeAll(UUID player) {
        byHash.values().removeIf(entry -> entry.player().equals(player));
    }

    /** Usuwa przeterminowane wpisy. Wołane okresowo. */
    public int cleanup() {
        long now = clock.getAsLong();
        int before = byHash.size();
        byHash.values().removeIf(entry -> entry.expiresAt() < now);
        return before - byHash.size();
    }

    public int size() {
        return byHash.size();
    }

    /**
     * Zapisuje tokeny wznowienia (same skróty) przed wyłączeniem serwera, żeby karty
     * graczy połączyły się same po restarcie. Tokeny linków nie są zapisywane.
     */
    public void saveResume(Path file) throws IOException {
        long now = clock.getAsLong();
        List<String> lines = new ArrayList<>();
        byHash.forEach((hash, entry) -> {
            if (entry.kind() != Kind.RESUME || entry.expiresAt() < now) {
                return;
            }
            long expiresAt = entry.expiresAt() == WHILE_CONNECTED ? now + resumeTtlMs : entry.expiresAt();
            lines.add(hash + ";" + entry.player() + ";" + expiresAt);
        });
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Wczytuje zapisane tokeny wznowienia i usuwa plik (jednorazowo). Zwraca liczbę wczytanych. */
    public int loadResume(Path file) throws IOException {
        if (!Files.exists(file)) {
            return 0;
        }
        long now = clock.getAsLong();
        int loaded = 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.split(";");
                if (parts.length != 3 || parts[0].length() != 64) {
                    continue;
                }
                try {
                    UUID player = UUID.fromString(parts[1]);
                    long expiresAt = Long.parseLong(parts[2]);
                    if (expiresAt >= now && expiresAt <= now + resumeTtlMs) {
                        byHash.put(parts[0], new Entry(player, Kind.RESUME, expiresAt));
                        loaded++;
                    }
                } catch (IllegalArgumentException ignored) {
                    // uszkodzona linia: pomijamy
                }
            }
        } finally {
            Files.deleteIfExists(file);
        }
        return loaded;
    }

    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Brak SHA-256 w JVM", e);
        }
    }
}
