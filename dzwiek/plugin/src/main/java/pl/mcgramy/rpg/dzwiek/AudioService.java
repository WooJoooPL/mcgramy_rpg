package pl.mcgramy.rpg.dzwiek;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import pl.mcgramy.rpg.dzwiek.api.DzwiekApi;
import pl.mcgramy.rpg.dzwiek.core.AudioBridgeServer;
import pl.mcgramy.rpg.dzwiek.core.MusicStack;
import pl.mcgramy.rpg.dzwiek.core.Protocol;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Stan dźwięku graczy po stronie gry: warstwy muzyki, wysyłanie zmian do kart,
 * obsługa wejścia i wyjścia z gry. Wszystko na głównym wątku serwera.
 */
final class AudioService implements DzwiekApi, AudioBridgeServer.Listener, Listener {

    static final String KEY_WORLD = "world";
    static final String KEY_REGION = "region";
    /** Wartość „cisza” w mapie ostatnio wysłanych utworów (mapa nie trzyma null). */
    private static final String SILENCE = "\0";

    private final DzwiekPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<UUID, MusicStack> stacks = new HashMap<>();
    private final Map<UUID, String> lastSent = new HashMap<>();

    AudioService(DzwiekPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ wywołania z WebSocket

    @Override
    public void onSessionOpened(UUID player, boolean resumed) {
        runSync(() -> {
            sendHelloAndState(player, 1500);
            Player online = Bukkit.getPlayer(player);
            if (online != null && !resumed) {
                send(online, "connected");
            }
        });
    }

    @Override
    public void onSessionClosed(UUID player) {
        runSync(() -> lastSent.remove(player));
    }

    private void runSync(Runnable task) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /** Powitanie (katalog dźwięków) i bieżąca muzyka. Także po przeładowaniu konfiguracji. */
    void sendHelloAndState(UUID player, int fadeMs) {
        AudioBridgeServer bridge = plugin.bridge();
        if (bridge == null) {
            return;
        }
        DzwiekSettings s = plugin.settings();
        Player online = Bukkit.getPlayer(player);
        String name = online != null ? online.getName() : offlineName(player);
        bridge.send(player, Protocol.hello(name, online != null, s.audioBase(), s.catalogJson()));
        lastSent.remove(player);
        push(player, fadeMs);
    }

    private static String offlineName(UUID player) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(player);
        return offline.getName() == null ? "Gracz" : offline.getName();
    }

    // ------------------------------------------------------------------ wejście i wyjście z gry

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        handleJoin(event.getPlayer(), true);
    }

    void handleJoin(Player player, boolean hint) {
        UUID id = player.getUniqueId();
        stacks.computeIfAbsent(id, k -> new MusicStack());
        lastSent.remove(id);
        AudioBridgeServer bridge = plugin.bridge();
        if (bridge != null) {
            bridge.setOnline(id, true);
        }
        plugin.regions().forget(id);
        if (hint && plugin.settings().joinHint()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && !isConnected(player)) {
                    send(player, "join-hint");
                }
            }, 60L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        stacks.remove(id);
        lastSent.remove(id);
        plugin.regions().forget(id);
        AudioBridgeServer bridge = plugin.bridge();
        if (bridge != null) {
            bridge.setOnline(id, false);
        }
    }

    // ------------------------------------------------------------------ warstwy muzyki

    MusicStack stack(UUID player) {
        return stacks.computeIfAbsent(player, k -> new MusicStack());
    }

    /** Ustawia warstwę i wysyła zmianę, jeśli zmienił się grany utwór. */
    void setLayer(UUID player, String key, String trackId, int priority, int fadeMs) {
        stack(player).set(key, trackId, priority);
        push(player, fadeMs);
    }

    void clearLayer(UUID player, String key, int fadeMs) {
        MusicStack stack = stacks.get(player);
        if (stack != null && stack.has(key)) {
            stack.remove(key);
            push(player, fadeMs);
        }
    }

    String currentMusic(UUID player) {
        MusicStack stack = stacks.get(player);
        return stack == null ? null : stack.current();
    }

    /** Wysyła utwór do karty, jeśli różni się od ostatnio wysłanego. */
    private void push(UUID player, int fadeMs) {
        AudioBridgeServer bridge = plugin.bridge();
        if (bridge == null) {
            return;
        }
        String current = currentMusic(player);
        String marker = current == null ? SILENCE : current;
        if (Objects.equals(lastSent.get(player), marker)) {
            return;
        }
        if (bridge.send(player, Protocol.music(current, fadeMs))) {
            lastSent.put(player, marker);
        }
    }

    // ------------------------------------------------------------------ DzwiekApi

    @Override
    public boolean isConnected(Player player) {
        AudioBridgeServer bridge = plugin.bridge();
        return bridge != null && bridge.hasSession(player.getUniqueId());
    }

    @Override
    public void playSound(Player player, String soundId, double volume) {
        DzwiekSettings.SoundDef def = plugin.settings().sounds().get(soundId);
        AudioBridgeServer bridge = plugin.bridge();
        if (def == null || def.music() || bridge == null) {
            return;
        }
        bridge.send(player.getUniqueId(), Protocol.sfx(soundId, volume));
    }

    @Override
    public void setMusic(Player player, String key, String trackId, int priority) {
        checkKey(key);
        if (trackId != null) {
            DzwiekSettings.SoundDef def = plugin.settings().sounds().get(trackId);
            if (def == null || !def.music()) {
                throw new IllegalArgumentException("Nieznany utwór: " + trackId);
            }
        }
        setLayer(player.getUniqueId(), key, trackId, priority, plugin.settings().defaultFadeMs());
    }

    @Override
    public void clearMusic(Player player, String key) {
        checkKey(key);
        clearLayer(player.getUniqueId(), key, plugin.settings().defaultFadeMs());
    }

    @Override
    public Set<String> soundIds() {
        return plugin.settings().sounds().keySet();
    }

    private static void checkKey(String key) {
        if (key == null || key.isBlank() || KEY_WORLD.equals(key) || KEY_REGION.equals(key)) {
            throw new IllegalArgumentException("Zły klucz warstwy muzyki: " + key);
        }
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("DzwiekApi wołamy z głównego wątku serwera");
        }
    }

    // ------------------------------------------------------------------ wiadomości

    void send(Player player, String key, TagResolver... resolvers) {
        String text = plugin.settings().message(key);
        if (text.isEmpty()) {
            return;
        }
        player.sendMessage(component(plugin.settings().message("prefix") + text, resolvers));
    }

    Component component(String miniMessage, TagResolver... resolvers) {
        return mm.deserialize(miniMessage, resolvers);
    }
}
