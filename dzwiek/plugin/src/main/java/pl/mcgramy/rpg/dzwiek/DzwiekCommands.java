package pl.mcgramy.rpg.dzwiek;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.mcgramy.rpg.dzwiek.api.DzwiekApi;
import pl.mcgramy.rpg.dzwiek.core.AudioBridgeServer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Komendy. Dla graczy {@code /muzyka} (ang. {@code /audio}), dla administracji {@code /rpgaudio}.
 *
 * <p>Z MythicMobs (mechanika {@code command}, z konsoli):
 * {@code rpgaudio play <target.name> demo_ryk} albo
 * {@code rpgaudio music <target.name> boss:smok demo_boss 100} i {@code rpgaudio music <target.name> boss:smok stop}.
 */
final class DzwiekCommands {

    static final String PERM_USE = "mcrpg.dzwiek.use";
    static final String PERM_ADMIN = "mcrpg.dzwiek.admin";

    private final DzwiekPlugin plugin;
    private final Map<UUID, Long> lastLink = new HashMap<>();

    DzwiekCommands(DzwiekPlugin plugin) {
        this.plugin = plugin;
    }

    void register(Commands registrar) {
        registrar.register(playerCommand(), "Muzyka i dźwięki gry w przeglądarce", List.of("audio"));
        registrar.register(adminCommand(), "Dźwięk w przeglądarce: administracja", List.of());
    }

    // ------------------------------------------------------------------ /muzyka

    private LiteralCommandNode<CommandSourceStack> playerCommand() {
        return Commands.literal("muzyka")
                .requires(src -> src.getSender().hasPermission(PERM_USE))
                .executes(ctx -> {
                    Player player = player(ctx);
                    if (player != null) {
                        link(player);
                    }
                    return 1;
                })
                .then(Commands.literal("rozlacz").executes(ctx -> {
                    Player player = player(ctx);
                    AudioBridgeServer bridge = plugin.bridge();
                    if (player != null && bridge != null) {
                        bridge.logout(player.getUniqueId());
                        plugin.audio().send(player, "logged-out");
                    }
                    return 1;
                }))
                .build();
    }

    private static Player player(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getExecutor() instanceof Player player) {
            return player;
        }
        ctx.getSource().getSender().sendRichMessage("<#FF5C5C>Tę komendę wpisuje gracz w grze");
        return null;
    }

    private void link(Player player) {
        AudioBridgeServer bridge = plugin.bridge();
        if (bridge == null || !bridge.isStarted()) {
            plugin.audio().send(player, "unavailable");
            return;
        }
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastLink.get(id);
        if (last != null && now - last < plugin.settings().linkCooldownMs()) {
            plugin.audio().send(player, "cooldown");
            return;
        }
        lastLink.put(id, now);
        lastLink.values().removeIf(t -> now - t > 60_000);

        String url = plugin.settings().publicUrl() + "#t=" + plugin.tokens().issueLink(id);
        Component label = plugin.audio().component(plugin.settings().message("link-label"))
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(plugin.audio().component(plugin.settings().message("link-hover")));
        plugin.audio().send(player, "link", Placeholder.component("link", label));
    }

    // ------------------------------------------------------------------ /rpgaudio

    private LiteralCommandNode<CommandSourceStack> adminCommand() {
        SuggestionProvider<CommandSourceStack> sfx = (ctx, b) -> {
            plugin.settings().sounds().values().stream().filter(d -> !d.music()).map(DzwiekSettings.SoundDef::id)
                    .filter(id -> id.startsWith(b.getRemainingLowerCase())).forEach(b::suggest);
            return b.buildFuture();
        };
        SuggestionProvider<CommandSourceStack> music = (ctx, b) -> {
            plugin.settings().sounds().values().stream().filter(DzwiekSettings.SoundDef::music)
                    .map(DzwiekSettings.SoundDef::id)
                    .filter(id -> id.startsWith(b.getRemainingLowerCase())).forEach(b::suggest);
            if (DzwiekSettings.NONE.startsWith(b.getRemainingLowerCase())) {
                b.suggest(DzwiekSettings.NONE);
            }
            return b.buildFuture();
        };

        return Commands.literal("rpgaudio")
                .requires(src -> src.getSender().hasPermission(PERM_ADMIN))
                .then(Commands.literal("reload").executes(ctx -> {
                    boolean ok = plugin.reload();
                    reply(ctx.getSource().getSender(), ok ? "<#5CE65C>✔</#5CE65C> <#D6D6D6>Przeładowano"
                            : "<#FF5C5C>✖</#FF5C5C> <#D6D6D6>Błąd przeładowania, szczegóły w konsoli");
                    return 1;
                }))
                .then(Commands.literal("status").executes(ctx -> {
                    status(ctx.getSource().getSender());
                    return 1;
                }))
                .then(Commands.literal("where").executes(ctx -> {
                    Player player = player(ctx);
                    if (player != null) {
                        UUID id = player.getUniqueId();
                        String region = plugin.regions().regionOf(id);
                        String track = plugin.audio().currentMusic(id);
                        reply(player, "<#D6D6D6>Region: <#FFC93C>" + safe(region == null ? "brak" : region)
                                + "</#FFC93C> <#4D4D4D>|</#4D4D4D> muzyka: <#FFC93C>" + safe(track == null ? "cisza" : track)
                                + "</#FFC93C> <#4D4D4D>|</#4D4D4D> karta: "
                                + (plugin.audio().isConnected(player) ? "<#5CE65C>połączona" : "<#FF5C5C>brak"));
                    }
                    return 1;
                }))
                .then(Commands.literal("play")
                        .then(Commands.argument("targets", ArgumentTypes.players())
                                .then(Commands.argument("sound", StringArgumentType.word()).suggests(sfx)
                                        .executes(ctx -> play(ctx, 1.0))
                                        .then(Commands.argument("volume", DoubleArgumentType.doubleArg(0.0, 2.0))
                                                .executes(ctx -> play(ctx, DoubleArgumentType.getDouble(ctx, "volume")))))))
                .then(Commands.literal("music")
                        .then(Commands.argument("targets", ArgumentTypes.players())
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .then(Commands.literal("stop").executes(this::musicStop))
                                        .then(Commands.argument("track", StringArgumentType.word()).suggests(music)
                                                .executes(ctx -> music(ctx, DzwiekApi.PRIORITY_EVENT))
                                                .then(Commands.argument("priority", IntegerArgumentType.integer(0, 1000))
                                                        .executes(ctx -> music(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "priority"))))))))
                .build();
    }

    private int play(CommandContext<CommandSourceStack> ctx, double volume) throws CommandSyntaxException {
        String sound = StringArgumentType.getString(ctx, "sound");
        DzwiekSettings.SoundDef def = plugin.settings().sounds().get(sound);
        if (def == null || def.music()) {
            reply(ctx.getSource().getSender(), "<#FF5C5C>✖</#FF5C5C> <#D6D6D6>Nie ma efektu " + safe(sound));
            return 0;
        }
        List<Player> targets = targets(ctx);
        targets.forEach(p -> plugin.audio().playSound(p, sound, volume));
        return targets.size();
    }

    private int music(CommandContext<CommandSourceStack> ctx, int priority) throws CommandSyntaxException {
        String key = StringArgumentType.getString(ctx, "key");
        String track = StringArgumentType.getString(ctx, "track");
        if (AudioService.KEY_WORLD.equals(key) || AudioService.KEY_REGION.equals(key)) {
            reply(ctx.getSource().getSender(), "<#FF5C5C>✖</#FF5C5C> <#D6D6D6>Klucze world i region są zajęte");
            return 0;
        }
        String trackId = DzwiekSettings.NONE.equalsIgnoreCase(track) ? null : track;
        if (trackId != null) {
            DzwiekSettings.SoundDef def = plugin.settings().sounds().get(trackId);
            if (def == null || !def.music()) {
                reply(ctx.getSource().getSender(), "<#FF5C5C>✖</#FF5C5C> <#D6D6D6>Nie ma utworu " + safe(track));
                return 0;
            }
        }
        List<Player> targets = targets(ctx);
        targets.forEach(p -> plugin.audio().setMusic(p, key, trackId, priority));
        return targets.size();
    }

    private int musicStop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String key = StringArgumentType.getString(ctx, "key");
        if (AudioService.KEY_WORLD.equals(key) || AudioService.KEY_REGION.equals(key)) {
            return 0;
        }
        List<Player> targets = targets(ctx);
        targets.forEach(p -> plugin.audio().clearMusic(p, key));
        return targets.size();
    }

    private static List<Player> targets(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return ctx.getArgument("targets", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
    }

    private void status(CommandSender sender) {
        AudioBridgeServer bridge = plugin.bridge();
        DzwiekSettings s = plugin.settings();
        String state = bridge != null && bridge.isStarted()
                ? "<#5CE65C>działa</#5CE65C> <#8C8C8C>(port " + bridge.getPort() + ")"
                : "<#FF5C5C>nie działa";
        reply(sender, "<#D6D6D6>Serwer: " + state);
        reply(sender, "<#D6D6D6>Karty: <#FFC93C>" + (bridge == null ? 0 : bridge.sessionCount())
                + "</#FFC93C> <#4D4D4D>|</#4D4D4D> tokeny: <#FFC93C>" + plugin.tokens().size()
                + "</#FFC93C> <#4D4D4D>|</#4D4D4D> dźwięki: <#FFC93C>" + s.sounds().size()
                + "</#FFC93C> <#4D4D4D>|</#4D4D4D> regiony: <#FFC93C>" + s.regions().size());
        reply(sender, "<#D6D6D6>Strona: <#5CE1E6>" + safe(s.publicUrl()));
    }

    private void reply(CommandSender sender, String miniMessage) {
        sender.sendMessage(plugin.audio().component(plugin.settings().message("prefix") + miniMessage));
    }

    /** Usuwa znaczniki MiniMessage z tekstu wstawianego do wiadomości. */
    private static String safe(String text) {
        return text.replace("\\", "\\\\").replace("<", "\\<");
    }
}
