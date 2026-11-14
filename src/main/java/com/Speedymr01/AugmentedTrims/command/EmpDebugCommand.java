package com.Speedymr01.AugmentedTrims.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.Speedymr01.AugmentedTrims.AugmentedTrims;
import com.Speedymr01.AugmentedTrims.ability.impl.RedstoneEmp;
import com.Speedymr01.AugmentedTrims.ability.impl.RedstoneEmp.Change;
import com.Speedymr01.AugmentedTrims.ability.impl.RedstoneEmp.SweepResult;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * {@code /trims empdebug [apply] [radius] [ticks]} - explains what the redstone EMP does in the
 * area around the player.
 *
 * <p>Without {@code apply} nothing in the world is touched: the command only reports what the
 * pulse would flip. With {@code apply} the blocks really are flipped and stay that way for
 * {@code ticks} ticks (default 40 = two seconds) before they are restored, which makes it
 * obvious whether the effect is visible at all.
 *
 * <p>The full report goes to the console, the short summary to the player.
 */
public final class EmpDebugCommand {

    private static final String PREFIX = "EMP-DEBUG";

    private final AugmentedTrims plugin;

    public EmpDebugCommand(AugmentedTrims plugin) {
        this.plugin = plugin;
    }

    public boolean run(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command has to be run by a player.",
                    NamedTextColor.RED));
            return true;
        }

        boolean apply = false;
        double radius = plugin.redstoneEmp().radius(1);
        int ticks = 40;
        List<Double> numbers = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.equalsIgnoreCase("apply")) {
                apply = true;
                continue;
            }
            try {
                numbers.add(Double.parseDouble(arg));
            } catch (NumberFormatException ignored) {
                sender.sendMessage(Component.text("Usage: /trims empdebug [apply] [radius] [ticks]",
                        NamedTextColor.RED));
                return true;
            }
        }
        if (!numbers.isEmpty()) {
            radius = numbers.get(0);
        }
        if (numbers.size() > 1) {
            ticks = numbers.get(1).intValue();
        }
        radius = Math.max(1.0, Math.min(32.0, radius));
        ticks = Math.max(1, Math.min(600, ticks));

        World world = player.getWorld();
        Location centre = player.getLocation().add(0, 0.9, 0);
        SweepResult result = plugin.redstoneEmp().sweep(world, centre, radius, apply);

        if (apply) {
            plugin.redstoneEmp().restoreLater(result.previous(), ticks);
        }

        report(player, radius, ticks, apply, result);
        return true;
    }

    private void report(Player player, double radius, int ticks, boolean apply, SweepResult result) {
        String mode = apply ? "APPLY" : "DRY";
        StringBuilder head = new StringBuilder();
        head.append("player=").append(player.getName())
                .append(" world=").append(player.getWorld().getName())
                .append(" at=").append(player.getLocation().getBlockX())
                .append(',').append(player.getLocation().getBlockY())
                .append(',').append(player.getLocation().getBlockZ())
                .append(" radius=").append(radius)
                .append(" mode=").append(mode);
        if (apply) {
            head.append(" holdTicks=").append(ticks);
        }
        head.append(" scanned=").append(result.scanned())
                .append(" air=").append(result.air())
                .append(" liquid=").append(result.liquid())
                .append(" tnt=").append(result.tnt().size())
                .append(" changes=").append(result.changes().size())
                .append(" stuck=").append(result.stuck());
        plugin.getLogger().info(PREFIX + " " + head);

        for (Change change : result.changes()) {
            plugin.getLogger().info(PREFIX + "   " + change.x() + "," + change.y() + "," + change.z()
                    + " " + change.material() + " [" + change.kind() + "] "
                    + change.before() + " -> " + change.after()
                    + (change.stuck() ? "  *** STATE DID NOT STICK ***" : ""));
        }

        List<Map.Entry<String, Integer>> skipped = new ArrayList<>(result.skipped().entrySet());
        skipped.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        if (!skipped.isEmpty()) {
            StringBuilder summary = new StringBuilder();
            int shown = Math.min(12, skipped.size());
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    summary.append(", ");
                }
                summary.append(skipped.get(i).getKey()).append(" x").append(skipped.get(i).getValue());
            }
            if (skipped.size() > shown) {
                summary.append(", ... (").append(skipped.size() - shown).append(" more)");
            }
            plugin.getLogger().info(PREFIX + "   skipped: " + summary);
        }

        player.sendMessage(Component.text("[EMP] ", NamedTextColor.DARK_RED)
                .append(Component.text(mode + " radius " + radius
                        + (apply ? " (held " + ticks + " ticks)" : ""), NamedTextColor.GRAY))
                .append(Component.text(" | scanned " + result.scanned()
                        + " | changes " + result.changes().size()
                        + " | tnt " + result.tnt().size()
                        + " | stuck " + result.stuck(), NamedTextColor.GRAY)));
        player.sendMessage(Component.text("[EMP] full report in the server console.",
                NamedTextColor.DARK_GRAY));
    }
}
