package pl.mcgramy.rpg.dzwiek.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Prosty licznik w stałym oknie czasowym, np. nieudane logowania na adres IP. */
public final class RateLimiter {

    private static final class Window {
        long start;
        int count;
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int limit;
    private final long windowMs;
    private final LongSupplier clock;

    public RateLimiter(int limit, long windowMs) {
        this(limit, windowMs, System::currentTimeMillis);
    }

    public RateLimiter(int limit, long windowMs, LongSupplier clock) {
        this.limit = limit;
        this.windowMs = windowMs;
        this.clock = clock;
    }

    /** Dolicza zdarzenie. Zwraca {@code false}, gdy limit w bieżącym oknie jest przekroczony. */
    public boolean hit(String key) {
        long now = clock.getAsLong();
        Window window = windows.computeIfAbsent(key, k -> new Window());
        synchronized (window) {
            if (now - window.start >= windowMs) {
                window.start = now;
                window.count = 0;
            }
            window.count++;
            return window.count <= limit;
        }
    }

    /** Czy klucz jest teraz zablokowany (bez doliczania). */
    public boolean blocked(String key) {
        Window window = windows.get(key);
        if (window == null) {
            return false;
        }
        synchronized (window) {
            return clock.getAsLong() - window.start < windowMs && window.count > limit;
        }
    }

    public void cleanup() {
        long now = clock.getAsLong();
        windows.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return now - e.getValue().start >= windowMs;
            }
        });
    }
}
