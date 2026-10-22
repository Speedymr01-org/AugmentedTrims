package com.Speedymr01.AugmentedTrims.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.Speedymr01.AugmentedTrims.AugmentedTrims;
import com.Speedymr01.AugmentedTrims.config.MaterialPowers;
import com.Speedymr01.AugmentedTrims.config.TrimTier;
import com.Speedymr01.AugmentedTrims.config.TrimPowerConfig;
import com.Speedymr01.AugmentedTrims.power.TrimPowerApplier;
import com.Speedymr01.AugmentedTrims.trim.TrimScanner;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** {@code /trims [info [player] | reload]} */
public final class TrimsCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION_ADMIN = "augmentedtrims.admin";

    private final AugmentedTrims plugin;
    private final TrimPowerConfig trimConfig;
    private final TrimPowerApplier applier;

    public TrimsCommand(AugmentedTrims plugin, TrimPowerConfig trimConfig, TrimPowerApplier applier) {
        this.plugin = plugin;
        this.trimConfig = trimConfig;
        this.applier = applier;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase();

        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission(PERMISSION_ADMIN)) {
                    sender.sendMessage(message("messages.no-permission"));
                    return true;
                }
                plugin.reloadTrimPlugin();
                sender.sendMessage(message("messages.reloaded"));
                return true;
            }
            case "info" -> {
                return showInfo(sender, args);
            }
            default -> {
                sender.sendMessage(message("messages.no-permission"));
                return true;
            }
        }
    }

    private boolean showInfo(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            if (!sender.hasPermission(PERMISSION_ADMIN)) {
                sender.sendMessage(message("messages.no-permission"));
                return true;
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(message("messages.player-not-found", "%player%", args[1]));
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(message("messages.no-permission"));
            return true;
        }

        sender.sendMessage(message("messages.header", "%player%", target.getName()));

        Map<NamespacedKey, Integer> counts = TrimScanner.countEquipped(target);
        if (counts.isEmpty()) {
            sender.sendMessage(message("messages.no-trims"));
            return true;
        }

        boolean any = false;
        for (Map.Entry<NamespacedKey, Integer> entry : counts.entrySet()) {
            Optional<MaterialPowers> powers = trimConfig.powers(entry.getKey());
            Optional<TrimTier> tier = powers.flatMap(p -> p.tierFor(entry.getValue()));
            if (tier.isPresent() && powers.isPresent()) {
                any = true;
                sender.sendMessage(message("messages.line",
                        "%material%", powers.get().displayOrId(),
                        "%pieces%", String.valueOf(entry.getValue()),
                        "%tier%", String.valueOf(tier.get().pieces())));
            } else {
                sender.sendMessage(message("messages.line-unknown",
                        "%material%", entry.getKey().getKey(),
                        "%pieces%", String.valueOf(entry.getValue())));
            }
        }

        if (!any) {
            sender.sendMessage(message("messages.no-trims"));
        }
        return true;
    }

    private Component message(String path, String... replacements) {
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        String raw = plugin.getConfig().getString(path, path);
        String text = prefix + raw;
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("info", "reload"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("info") && sender.hasPermission(PERMISSION_ADMIN)) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()), args[1]);
        }
        return Collections.emptyList();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
