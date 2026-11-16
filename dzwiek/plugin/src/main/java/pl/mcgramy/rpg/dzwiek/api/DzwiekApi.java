package pl.mcgramy.rpg.dzwiek.api;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Set;

/**
 * API dźwięku w przeglądarce dla innych pluginów McRPG (bossy, NPC, zadania).
 * Pobranie: {@code DzwiekApi.get()} albo {@code ServicesManager}. Metody wołamy z głównego wątku.
 *
 * <p>Muzyka działa warstwami: każde źródło ustawia własną warstwę pod swoim kluczem
 * (np. {@code "boss:smok"}), a gra warstwa o najwyższym priorytecie. Po zdjęciu warstwy
 * wraca poprzednia muzyka, więc boss nie musi pamiętać, co grało wcześniej.
 */
public interface DzwiekApi {

    /** Muzyka całego świata. */
    int PRIORITY_WORLD = 0;
    /** Muzyka regionu. */
    int PRIORITY_REGION = 10;
    /** Zdarzenia, scenki, rozmowy z NPC. */
    int PRIORITY_EVENT = 50;
    /** Walka z bossem. */
    int PRIORITY_BOSS = 100;

    /** Czy gracz ma otwartą i połączoną stronę z dźwiękiem. */
    boolean isConnected(Player player);

    /** Jednorazowy efekt (id z sekcji {@code sounds}, rodzaj {@code sfx}). Głośność 0.0–2.0. */
    void playSound(Player player, String soundId, double volume);

    /**
     * Ustawia warstwę muzyki gracza.
     *
     * @param key      klucz źródła, np. {@code "boss:smok"} (klucze {@code world} i {@code region} są zajęte)
     * @param trackId  id utworu (rodzaj {@code music}) albo {@code null} dla ciszy
     * @param priority wyższy wygrywa, zob. stałe {@code PRIORITY_*}
     */
    void setMusic(Player player, String key, String trackId, int priority);

    /** Zdejmuje warstwę muzyki. */
    void clearMusic(Player player, String key);

    /** Id wszystkich dźwięków z konfiguracji. */
    Set<String> soundIds();

    static DzwiekApi get() {
        DzwiekApi api = Bukkit.getServicesManager().load(DzwiekApi.class);
        if (api == null) {
            throw new IllegalStateException("McRPGDzwiek nie jest włączony");
        }
        return api;
    }
}
