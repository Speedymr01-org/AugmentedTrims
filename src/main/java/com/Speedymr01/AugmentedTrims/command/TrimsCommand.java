package com.Speedymr01.AugmentedTrims.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.Speedymr01.AugmentedTrims.AugmentedTrims;
import com.Speedymr01.AugmentedTrims.ability.Ability;
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
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * {@code /trims [info [player] | reload | give <formless|material> [player] | cooldown [player]]}
 */
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
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "reload" -> {
                if (!admin(sender)) {
                    return true;
                }
                plugin.reloadTrimPlugin();
                sender.sendMessage(message("messages.reloaded"));
                return true;
            }
            case "give" -> {
                return give(sender, args);
            }
            case "cooldown" -> {
                return clearCooldown(sender, args);
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

    // ------------------------------------------------------------------ give

    private boolean give(CommandSender sender, String[] args) {
        if (!admin(sender)) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(message("messages.give-usage"));
            return true;
        }

        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(message("messages.player-not-found", "%player%", args[2]));
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(message("messages.give-usage"));
            return true;
        }

        String requested = args[1].toLowerCase(Locale.ROOT);
        ItemStack star;
        if (requested.equals("formless") || requested.equals("none")) {
            star = plugin.starFactory().createFormless();
        } else {
            Optional<Ability> ability = plugin.abilityManager().ability(requested);
            if (ability.isEmpty()) {
                sender.sendMessage(message("messages.give-usage"));
                return true;
            }
            star = plugin.starFactory().createAttuned(
                    requested, plugin.recipes().defaultCooldownSeconds(requested));
        }

        Map<Integer, ItemStack> leftover = target.getInventory().addItem(star);
        leftover.values().forEach(rest -> target.getWorld().dropItemNaturally(target.getLocation(), rest));

        String itemName = (requested.equals("formless") || requested.equals("none"))
                ? "Formless Star"
                : plugin.starFactory().displayName(requested) + " Ability Star";
        sender.sendMessage(message("messages.give",
                "%amount%", String.valueOf(star.getAmount()),
                "%item%", itemName,
                "%player%", target.getName()));
        return true;
    }

    // -------------------------------------------------------------- cooldown

    private boolean clearCooldown(CommandSender sender, String[] args) {
        if (!admin(sender)) {
            return true;
        }

        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(message("messages.player-not-found", "%player%", args[1]));
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(message("messages.player-not-found", "%player%", "?"));
            return true;
        }

        plugin.cooldowns().clearAll(target);
        sender.sendMessage(message("messages.cooldown-cleared", "%player%", target.getName()));
        return true;
    }

    // ------------------------------------------------------------------ info

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

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission(PERMISSION_ADMIN)) {
            return true;
        }
        sender.sendMessage(message("messages.no-permission"));
        return false;
    }

    private net.kyori.adventure.text.Component message(String path, String... replacements) {
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
        if (!sender.hasPermission(PERMISSION_ADMIN)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filter(List.of("info", "reload", "give", "cooldown"), args[0]);
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("give")) {
                List<String> options = new ArrayList<>();
                options.add("formless");
                plugin.abilityManager().all().stream()
                        .map(Ability::materialKey)
                        .sorted()
                        .forEach(options::add);
                return filter(options, args[1]);
            }
            if (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("cooldown")) {
                return filter(onlineNames(), args[1]);
            }
            return Collections.emptyList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return filter(onlineNames(), args[2]);
        }
        return Collections.emptyList();
    }

    private static List<String> onlineNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
