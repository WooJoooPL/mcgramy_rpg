package pl.mcgramy.rpg.dzwiek;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import pl.mcgramy.rpg.dzwiek.api.DzwiekApi;
import pl.mcgramy.rpg.dzwiek.core.AudioBridgeServer;
import pl.mcgramy.rpg.dzwiek.core.TokenStore;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/** McRPGDzwiek: muzyka i dźwięki gry w przeglądarce. */
public final class DzwiekPlugin extends JavaPlugin {

    private static final String SESSIONS_FILE = "sesje.dat";

    private volatile DzwiekSettings settings;
    private TokenStore tokens;
    private volatile AudioBridgeServer bridge;
    private AudioService audio;
    private RegionTracker regions;
    private BukkitTask regionTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = DzwiekSettings.load(getConfig(), getLogger());
        tokens = new TokenStore(settings.linkTtlMs(), settings.resumeTtlMs());
        try {
            int loaded = tokens.loadResume(sessionsFile());
            if (loaded > 0) {
                getLogger().info("Wczytano " + loaded + " sesji kart sprzed restartu");
            }
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Nie udało się wczytać " + SESSIONS_FILE, e);
        }

        audio = new AudioService(this);
        regions = new RegionTracker(this, audio);
        startBridge();

        getServer().getPluginManager().registerEvents(audio, this);
        scheduleRegions();
        DzwiekCommands commands = new DzwiekCommands(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> commands.register(event.registrar()));
        getServer().getServicesManager().register(DzwiekApi.class, audio, this, ServicePriority.Normal);

        // Gracze, którzy już są na serwerze (np. włączenie pluginu bez restartu)
        for (Player player : Bukkit.getOnlinePlayers()) {
            audio.handleJoin(player, false);
        }

        getLogger().info("==================================");
        getLogger().info(" McRPGDzwiek " + getPluginMeta().getVersion());
        getLogger().info(" Strona: " + settings.publicUrl());
        getLogger().info(" Dźwięki: " + settings.sounds().size() + ", regiony: " + settings.regions().size());
        getLogger().info("==================================");
    }

    @Override
    public void onDisable() {
        if (regionTask != null) {
            regionTask.cancel();
        }
        stopBridge();
        if (tokens != null) {
            try {
                tokens.saveResume(sessionsFile());
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Nie udało się zapisać " + SESSIONS_FILE, e);
            }
        }
        getServer().getServicesManager().unregisterAll(this);
    }

    /** /rpgaudio reload. Serwer WebSocket restartuje się tylko, gdy zmienił się adres lub port. */
    boolean reload() {
        DzwiekSettings old = settings;
        DzwiekSettings fresh;
        try {
            reloadConfig();
            fresh = DzwiekSettings.load(getConfig(), getLogger());
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Błąd w config.yml, zostaje poprzednia konfiguracja", e);
            return false;
        }
        settings = fresh;
        tokens.setTtl(fresh.linkTtlMs(), fresh.resumeTtlMs());
        boolean restart = bridge == null
                || !Objects.equals(old.bindHost(), fresh.bindHost()) || old.port() != fresh.port()
                || !Objects.equals(old.server(), fresh.server());
        if (restart) {
            // Karty dostaną kod 1001 i połączą się same tokenem wznowienia.
            stopBridge();
            startBridge();
        } else {
            for (UUID player : bridge.sessionPlayers()) {
                audio.sendHelloAndState(player, fresh.defaultFadeMs());
            }
        }
        regions.forgetAll();
        scheduleRegions();
        return true;
    }

    private void startBridge() {
        try {
            AudioBridgeServer server = new AudioBridgeServer(
                    new InetSocketAddress(settings.bindHost(), settings.port()),
                    settings.server(), tokens, audio, getLogger());
            server.start();
            bridge = server;
            for (Player player : Bukkit.getOnlinePlayers()) {
                server.setOnline(player.getUniqueId(), true);
            }
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Nie udało się uruchomić serwera dźwięku", e);
            bridge = null;
        }
    }

    private void stopBridge() {
        AudioBridgeServer server = bridge;
        bridge = null;
        if (server != null) {
            server.shutdown();
        }
    }

    private void scheduleRegions() {
        if (regionTask != null) {
            regionTask.cancel();
        }
        regionTask = Bukkit.getScheduler().runTaskTimer(this, regions, 20L, settings.regionCheckTicks());
    }

    private Path sessionsFile() {
        getDataFolder().mkdirs();
        return getDataFolder().toPath().resolve(SESSIONS_FILE);
    }

    DzwiekSettings settings() {
        return settings;
    }

    TokenStore tokens() {
        return tokens;
    }

    /** Może być {@code null}, gdy serwer dźwięku nie wystartował. */
    AudioBridgeServer bridge() {
        return bridge;
    }

    AudioService audio() {
        return audio;
    }

    RegionTracker regions() {
        return regions;
    }
}
