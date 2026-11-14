package com.Speedymr01.AugmentedTrims.ability.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.Speedymr01.AugmentedTrims.ability.Ability;
import com.Speedymr01.AugmentedTrims.trim.TrimScanner;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/**
 * Netherite - Dread Aura.
 *
 * <p>A toggle rather than a one-shot: while it is on, every player inside the tier radius is
 * blinded and nauseated. Both effects are always applied for the same fixed duration (five
 * seconds by default) instead of one refresh interval, so walking out of the radius does not
 * strip them immediately - they simply stop being refreshed and run out.
 *
 * <p>Radius per tier: 1, 2.5, 4 and 5.5 blocks. The wearer is not affected unless
 * {@code abilities.netherite.affect-self} is enabled. Toggling costs a flat one second
 * cooldown in both directions so the star cannot be spammed.
 */
public final class NetheriteAura implements Ability {

    private static final NamespacedKey NETHERITE = NamespacedKey.minecraft("netherite");

    private final JavaPlugin plugin;
    private final BukkitTask task;
    private final BukkitTask visualTask;

    /** Explicit on/off choices; absent means "whatever default-enabled says". */
    private final Map<UUID, Boolean> state = new HashMap<>();

    public NetheriteAura(JavaPlugin plugin) {
        this.plugin = plugin;
        int interval = Math.max(5, plugin.getConfig().getInt("abilities.netherite.interval-ticks", 20));
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::pulse, interval, interval);
        // The ring is drawn in batches: a new batch appears exactly when the last one has faded,
        // so the aura is always shown without a gap.
        int visualTicks = Math.max(1, plugin.getConfig().getInt("abilities.netherite.visual-ticks", 8));
        this.visualTask = Bukkit.getScheduler()
                .runTaskTimer(plugin, this::drawRings, visualTicks, visualTicks);
    }

    @Override
    public String materialKey() {
        return "netherite";
    }

    /** Flat cooldown - toggling is cheap in both directions. */
    @Override
    public int cooldownSeconds(int tier) {
        return Math.max(1, plugin.getConfig().getInt("abilities.netherite.cooldown", 1));
    }

    @Override
    public boolean cast(Player player, int tier) {
        boolean enable = !isOn(player);
        state.put(player.getUniqueId(), enable);
        if (enable) {
            // Apply once immediately so the toggle is visible without waiting for the next pulse.
            apply(player);
        }
        return true;
    }

    @Override
    public String successMessagePath(Player player, int tier) {
        return isOn(player) ? "messages.ability.aura-on" : "messages.ability.aura-off";
    }

    // --------------------------------------------------------------- aura loop

    private void pulse() {
        for (Player wearer : Bukkit.getOnlinePlayers()) {
            if (!isOn(wearer)) {
                continue;
            }
            if (!apply(wearer)) {
                state.put(wearer.getUniqueId(), false);
                message(wearer, "messages.ability.aura-ended",
                        "&7Dread aura ended - no netherite trim equipped.");
            }
        }
    }

    /**
     * Draws one batch of the ring for everybody whose aura is on.
     */
    private void drawRings() {
        for (Player wearer : Bukkit.getOnlinePlayers()) {
            if (!isOn(wearer)) {
                continue;
            }
            int pieces = TrimScanner.countEquipped(wearer).getOrDefault(NETHERITE, 0);
            if (pieces <= 0) {
                continue;
            }
            showRing(wearer, radius(Math.min(4, pieces)));
        }
    }

    /**
     * Shows how far the aura reaches: a ring of dark sparks around the wearer.
     *
     * <p>The ring is horizontal, sparse and every spark sinks a little before it fades. The
     * spacing and the distance they sink are configurable.
     */
    private void showRing(Player wearer, double radius) {
        var config = plugin.getConfig();
        if (!config.getBoolean("abilities.netherite.visual", true)) {
            return;
        }
        double spacing = Math.max(0.25, config.getDouble("abilities.netherite.visual-spacing", 1.5));
        double fall = config.getDouble("abilities.netherite.visual-fall", 0.2);
        int ticks = Math.max(1, config.getInt("abilities.netherite.visual-ticks", 8));

        World world = wearer.getWorld();
        Location centre = wearer.getLocation();
        int points = Math.max(4, (int) Math.round(2.0 * Math.PI * radius / spacing));
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(18, 18, 22), 1.0f);

        new BukkitRunnable() {
            private int tick;

            @Override
            public void run() {
                double drop = fall * (tick / (double) ticks);
                for (int i = 0; i < points; i++) {
                    double angle = 2.0 * Math.PI * i / points;
                    Location at = centre.clone().add(Math.cos(angle) * radius, 0.1,
                            Math.sin(angle) * radius);
                    at.add(0.0, -drop, 0.0);
                    world.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, dust);
                }
                tick++;
                if (tick >= ticks) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Applies one second of aura from this wearer. @return false when they no longer qualify */
    private boolean apply(Player wearer) {
        int pieces = TrimScanner.countEquipped(wearer).getOrDefault(NETHERITE, 0);
        if (pieces <= 0) {
            return false;
        }

        var config = plugin.getConfig();
        int tier = Math.min(4, pieces);
        double radius = radius(tier);
        double radiusSquared = radius * radius;
        int duration = Math.max(20, config.getInt("abilities.netherite.effect-duration-ticks", 100));
        int amplifier = Math.max(0, config.getInt("abilities.netherite.amplifier", 0));
        boolean affectsSelf = config.getBoolean("abilities.netherite.affect-self", false);

        for (Player target : wearer.getWorld().getPlayers()) {
            if (target.equals(wearer) && !affectsSelf) {
                continue;
            }
            if (target.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            if (target.getLocation().distanceSquared(wearer.getLocation()) > radiusSquared) {
                continue;
            }
            target.addPotionEffect(new PotionEffect(
                    PotionEffectType.BLINDNESS, duration, amplifier, false, true, true));
            target.addPotionEffect(new PotionEffect(
                    PotionEffectType.NAUSEA, duration, amplifier, false, true, true));
        }
        return true;
    }

    private boolean isOn(Player player) {
        return state.getOrDefault(player.getUniqueId(),
                plugin.getConfig().getBoolean("abilities.netherite.default-enabled", false));
    }

    /** Tier radius: 1 block at tier 1, +1.5 per tier (config overrides per tier). */
    private double radius(int tier) {
        double fallback = 1.0 + 1.5 * (tier - 1);
        double configured = plugin.getConfig()
                .getDouble("abilities.netherite.radius." + tier, fallback);
        return Math.max(0.5, configured);
    }

    // --------------------------------------------------------------- lifecycle

    @Override
    public void cancel(Player player) {
        state.remove(player.getUniqueId());
    }

    @Override
    public void shutdown() {
        task.cancel();
        visualTask.cancel();
        state.clear();
    }

    private void message(Player player, String path, String fallback) {
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        String text = plugin.getConfig().getString(path, fallback);
        player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(prefix + text));
    }
}
