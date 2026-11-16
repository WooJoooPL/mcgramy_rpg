package pl.mcgramy.rpg.dzwiek.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Warstwy muzyki jednego gracza. Każde źródło (świat, region, boss, scenka) ustawia swoją
 * warstwę pod własnym kluczem. Gra warstwa o najwyższym priorytecie, przy remisie najnowsza.
 * Warstwa z utworem {@code null} oznacza ciszę (np. cicha świątynia w głośnym lesie).
 *
 * <p>Nie jest bezpieczna wątkowo: używamy jej tylko z głównego wątku serwera.
 */
public final class MusicStack {

    private record Layer(String trackId, int priority, long seq) { }

    private final Map<String, Layer> layers = new HashMap<>();
    private long seq;

    /** Ustawia warstwę. Zwraca {@code true}, gdy zmienił się utwór, który ma grać. */
    public boolean set(String key, String trackId, int priority) {
        Objects.requireNonNull(key, "key");
        String before = current();
        Layer old = layers.get(key);
        if (old != null && Objects.equals(old.trackId(), trackId) && old.priority() == priority) {
            return false;
        }
        layers.put(key, new Layer(trackId, priority, ++seq));
        return !Objects.equals(before, current());
    }

    /** Usuwa warstwę. Zwraca {@code true}, gdy zmienił się utwór, który ma grać. */
    public boolean remove(String key) {
        String before = current();
        if (layers.remove(key) == null) {
            return false;
        }
        return !Objects.equals(before, current());
    }

    /** Utwór, który ma teraz grać, albo {@code null} (cisza). */
    public String current() {
        Layer best = null;
        for (Layer layer : layers.values()) {
            if (best == null || layer.priority() > best.priority()
                    || (layer.priority() == best.priority() && layer.seq() > best.seq())) {
                best = layer;
            }
        }
        return best == null ? null : best.trackId();
    }

    public boolean has(String key) {
        return layers.containsKey(key);
    }

    public void clear() {
        layers.clear();
    }
}
