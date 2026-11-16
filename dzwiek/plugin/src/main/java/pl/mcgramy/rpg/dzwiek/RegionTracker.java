package pl.mcgramy.rpg.dzwiek;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import pl.mcgramy.rpg.dzwiek.api.DzwiekApi;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Co kilka ticków sprawdza, w którym regionie jest gracz, i ustawia warstwy muzyki
 * {@code world} i {@code region}. Liczy tylko, gdy gracz zmienił blok lub świat.
 *
 * <p>Prototyp: liniowe przeszukiwanie regionów (wystarczy na kilkadziesiąt regionów).
 * Przy setkach regionów dodamy podział na chunki.
 */
final class RegionTracker implements Runnable {

    private record Position(String world, int x, int y, int z) { }

    private final DzwiekPlugin plugin;
    private final AudioService audio;
    private final Map<UUID, Position> lastPosition = new HashMap<>();
    private final Map<UUID, String> lastRegion = new HashMap<>();

    RegionTracker(DzwiekPlugin plugin, AudioService audio) {
        this.plugin = plugin;
        this.audio = audio;
    }

    /** Wymusza ponowne przeliczenie gracza w następnym przebiegu. */
    void forget(UUID player) {
        lastPosition.remove(player);
        lastRegion.remove(player);
    }

    void forgetAll() {
        lastPosition.clear();
        lastRegion.clear();
    }

    /** Region o najwyższym priorytecie w miejscu gracza (do /rpgaudio where). */
    String regionOf(UUID player) {
        return lastRegion.get(player);
    }

    @Override
    public void run() {
        DzwiekSettings s = plugin.settings();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location loc = player.getLocation();
            if (loc.getWorld() == null) {
                continue;
            }
            Position pos = new Position(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            UUID id = player.getUniqueId();
            if (pos.equals(lastPosition.get(id))) {
                continue;
            }
            boolean worldChanged = lastPosition.get(id) == null || !lastPosition.get(id).world().equals(pos.world());
            lastPosition.put(id, pos);
            update(player, id, pos, worldChanged, s);
        }
    }

    private void update(Player player, UUID id, Position pos, boolean worldChanged, DzwiekSettings s) {
        int fade = s.defaultFadeMs();
        if (worldChanged) {
            if (s.worldMusic().containsKey(pos.world())) {
                audio.setLayer(id, AudioService.KEY_WORLD, s.worldMusic().get(pos.world()),
                        DzwiekApi.PRIORITY_WORLD, fade);
            } else {
                audio.clearLayer(id, AudioService.KEY_WORLD, fade);
            }
        }

        DzwiekSettings.Region top = null;
        DzwiekSettings.Region musicRegion = null;
        for (DzwiekSettings.Region r : s.regions()) {
            if (!r.contains(pos.world(), pos.x(), pos.y(), pos.z())) {
                continue;
            }
            if (top == null || r.priority() > top.priority()) {
                top = r;
            }
            if (r.hasMusic() && (musicRegion == null || r.priority() > musicRegion.priority())) {
                musicRegion = r;
            }
        }

        if (musicRegion != null) {
            audio.setLayer(id, AudioService.KEY_REGION, musicRegion.music(), DzwiekApi.PRIORITY_REGION, fade);
        } else {
            audio.clearLayer(id, AudioService.KEY_REGION, fade);
        }

        String topId = top == null ? null : top.id();
        String previous = lastRegion.get(id);
        if (!Objects.equals(previous, topId)) {
            if (topId == null) {
                lastRegion.remove(id);
            } else {
                lastRegion.put(id, topId);
            }
            if (top != null && top.enterSound() != null) {
                audio.playSound(player, top.enterSound(), 1.0);
            }
        }
    }
}
