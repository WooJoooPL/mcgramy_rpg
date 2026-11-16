package pl.mcgramy.rpg.dzwiek;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import pl.mcgramy.rpg.dzwiek.core.AudioBridgeServer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Odczytany i sprawdzony {@code config.yml}. Niezmienny: przy przeładowaniu powstaje nowy obiekt. */
record DzwiekSettings(
        String publicUrl,
        String audioBase,
        String bindHost,
        int port,
        AudioBridgeServer.Settings server,
        long linkTtlMs,
        long resumeTtlMs,
        long linkCooldownMs,
        int regionCheckTicks,
        int defaultFadeMs,
        boolean joinHint,
        Map<String, SoundDef> sounds,
        JsonObject catalogJson,
        Map<String, String> worldMusic,
        List<Region> regions,
        Map<String, String> messages) {

    /** Dźwięk z katalogu. */
    record SoundDef(String id, boolean music, String title, List<String> src, double volume) { }

    /** Region prostopadłościenny. {@code music}: id utworu, {@code null} = cisza; {@code hasMusic=false} = bez wpływu. */
    record Region(String id, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                  int priority, boolean hasMusic, String music, String enterSound) {

        boolean contains(String worldName, int x, int y, int z) {
            return world.equals(worldName) && x >= minX && x <= maxX && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
        }
    }

    static final Pattern ID = Pattern.compile("^[a-z0-9_]{1,32}$");
    /** Ścieżki plików audio: tylko względne, bez wychodzenia z katalogu. */
    static final Pattern SRC = Pattern.compile("^[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*\\.(ogg|m4a|mp3|webm|opus)$");
    static final String NONE = "none";
    static final List<String> MESSAGE_KEYS = List.of("prefix", "link", "link-label", "link-hover", "cooldown",
            "unavailable", "connected", "logged-out", "join-hint");

    static DzwiekSettings load(FileConfiguration cfg, Logger log) {
        String publicUrl = cfg.getString("public-url", "https://mcgramy.pl/muzyka/");
        if (!publicUrl.endsWith("/")) {
            publicUrl += "/";
        }
        if (!publicUrl.startsWith("https://")) {
            log.warning("public-url nie zaczyna się od https:// (przeglądarka zablokuje wss i dźwięk na telefonach)");
        }
        String audioBase = cfg.getString("audio-base", "audio/");
        if (!audioBase.isEmpty() && !audioBase.endsWith("/")) {
            audioBase += "/";
        }

        Set<String> origins = new HashSet<>(cfg.getStringList("server.allowed-origins"));
        if (origins.isEmpty()) {
            log.warning("server.allowed-origins jest puste: sprawdzanie Origin wyłączone (tylko do testów)");
        }
        AudioBridgeServer.Settings server = new AudioBridgeServer.Settings(
                Set.copyOf(origins),
                Set.copyOf(cfg.getStringList("server.trusted-proxies")),
                Math.max(1, cfg.getInt("server.max-connections", 500)),
                Math.max(1, cfg.getInt("server.max-connections-per-ip", 4)),
                10_000,
                Math.max(60, cfg.getLong("tokens.offline-timeout-seconds", 900)) * 1000,
                Math.max(1, cfg.getInt("server.max-failed-auth-per-minute", 10)),
                20,
                4096);

        Map<String, SoundDef> sounds = new LinkedHashMap<>();
        JsonObject catalog = new JsonObject();
        ConfigurationSection soundSec = cfg.getConfigurationSection("sounds");
        if (soundSec != null) {
            for (String id : soundSec.getKeys(false)) {
                ConfigurationSection s = soundSec.getConfigurationSection(id);
                if (s == null || !ID.matcher(id).matches()) {
                    log.warning("Pomijam dźwięk o złym id: " + id + " (dozwolone a-z, 0-9, _)");
                    continue;
                }
                boolean music = "music".equalsIgnoreCase(s.getString("kind", "sfx"));
                List<String> src = new ArrayList<>();
                for (String path : s.getStringList("src")) {
                    if (SRC.matcher(path).matches()) {
                        src.add(path);
                    } else {
                        log.warning("Dźwięk " + id + ": pomijam złą ścieżkę " + path);
                    }
                }
                if (src.isEmpty()) {
                    log.warning("Dźwięk " + id + " nie ma żadnego poprawnego pliku, pomijam");
                    continue;
                }
                double volume = Math.max(0.0, Math.min(1.0, s.getDouble("volume", 1.0)));
                String title = s.getString("title", id);
                SoundDef def = new SoundDef(id, music, title, List.copyOf(src), volume);
                sounds.put(id, def);

                JsonObject entry = new JsonObject();
                entry.addProperty("k", music ? "music" : "sfx");
                JsonArray arr = new JsonArray();
                src.forEach(arr::add);
                entry.add("src", arr);
                entry.addProperty("v", volume);
                if (music) {
                    entry.addProperty("title", title);
                }
                catalog.add(id, entry);
            }
        }

        Map<String, String> worldMusic = new LinkedHashMap<>();
        ConfigurationSection worldSec = cfg.getConfigurationSection("worlds");
        if (worldSec != null) {
            for (String world : worldSec.getKeys(false)) {
                String music = worldSec.getString(world + ".music");
                if (music == null) {
                    continue;
                }
                if (NONE.equalsIgnoreCase(music)) {
                    worldMusic.put(world, null);
                } else if (isMusic(sounds, music, "świat " + world, log)) {
                    worldMusic.put(world, music);
                }
            }
        }

        List<Region> regions = new ArrayList<>();
        ConfigurationSection regionSec = cfg.getConfigurationSection("regions");
        if (regionSec != null) {
            for (String id : regionSec.getKeys(false)) {
                ConfigurationSection r = regionSec.getConfigurationSection(id);
                if (r == null) {
                    continue;
                }
                List<Integer> min = r.getIntegerList("min");
                List<Integer> max = r.getIntegerList("max");
                String world = r.getString("world");
                if (world == null || min.size() != 3 || max.size() != 3) {
                    log.warning("Region " + id + ": brak world albo min/max w formacie [x, y, z], pomijam");
                    continue;
                }
                boolean hasMusic = r.contains("music");
                String music = r.getString("music");
                if (hasMusic && NONE.equalsIgnoreCase(music)) {
                    music = null;
                } else if (hasMusic && !isMusic(sounds, music, "region " + id, log)) {
                    hasMusic = false;
                    music = null;
                }
                String enter = r.getString("enter-sound");
                if (enter != null && (!sounds.containsKey(enter) || sounds.get(enter).music())) {
                    log.warning("Region " + id + ": enter-sound " + enter + " nie jest efektem (sfx), pomijam");
                    enter = null;
                }
                regions.add(new Region(id, world,
                        Math.min(min.get(0), max.get(0)), Math.min(min.get(1), max.get(1)), Math.min(min.get(2), max.get(2)),
                        Math.max(min.get(0), max.get(0)), Math.max(min.get(1), max.get(1)), Math.max(min.get(2), max.get(2)),
                        r.getInt("priority", 0), hasMusic, music, enter));
            }
        }

        // getString(path) bez wartości domyślnej bierze brakujące teksty z config.yml w jarze
        Map<String, String> messages = new LinkedHashMap<>();
        for (String key : MESSAGE_KEYS) {
            String text = cfg.getString("messages." + key);
            messages.put(key, text == null ? "" : text);
        }

        return new DzwiekSettings(publicUrl, audioBase,
                cfg.getString("server.bind-host", "0.0.0.0"),
                cfg.getInt("server.port", 8790),
                server,
                Math.max(30, cfg.getLong("tokens.link-ttl-seconds", 300)) * 1000,
                Math.max(60, cfg.getLong("tokens.resume-ttl-seconds", 1800)) * 1000,
                Math.max(0, cfg.getLong("link-cooldown-seconds", 5)) * 1000,
                Math.max(1, cfg.getInt("region-check-ticks", 10)),
                Math.max(0, cfg.getInt("default-fade-ms", 2500)),
                cfg.getBoolean("join-hint", true),
                Map.copyOf(sounds), catalog, java.util.Collections.unmodifiableMap(worldMusic),
                List.copyOf(regions), Map.copyOf(messages));
    }

    private static boolean isMusic(Map<String, SoundDef> sounds, String id, String where, Logger log) {
        SoundDef def = id == null ? null : sounds.get(id);
        if (def == null || !def.music()) {
            log.warning(where + ": " + id + " nie jest utworem (kind: music) z sekcji sounds, pomijam");
            return false;
        }
        return true;
    }

    String message(String key) {
        return messages.getOrDefault(key, "");
    }
}
