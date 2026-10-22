package com.Speedymr01.AugmentedTrims.power;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.Speedymr01.AugmentedTrims.AugmentedTrims;
import com.Speedymr01.AugmentedTrims.config.EffectDefinition;
import com.Speedymr01.AugmentedTrims.config.MaterialPowers;
import com.Speedymr01.AugmentedTrims.config.TrimPowerConfig;
import com.Speedymr01.AugmentedTrims.config.TrimTier;
import com.Speedymr01.AugmentedTrims.trim.TrimScanner;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Applies the configured powers for whatever trims a player is wearing.
 *
 * <p>Runs on the main thread only. Tracked per player so effects can be removed again the
 * moment the matching piece is taken off (see {@code settings.remove-on-unequip}).
 */
public final class TrimPowerApplier {

    private final AugmentedTrims plugin;
    private final TrimPowerConfig trimConfig;

    private final Map<UUID, Set<PotionEffectType>> granted = new HashMap<>();

    private int intervalTicks;
    private int bufferTicks;
    private boolean ambient;
    private boolean particles;
    private boolean icon;
    private boolean removeOnUnequip;
    private boolean debug;

    public TrimPowerApplier(AugmentedTrims plugin, TrimPowerConfig trimConfig) {
        this.plugin = plugin;
        this.trimConfig = trimConfig;
        reloadSettings();
    }

    /** Re-reads {@code settings} from config.yml. */
    public void reloadSettings() {
        var config = plugin.getConfig();
        this.intervalTicks = Math.max(5, config.getInt("settings.refresh-interval-ticks", 20));
        this.bufferTicks = Math.max(0, config.getInt("settings.duration-buffer-ticks", 40));
        this.ambient = config.getBoolean("settings.ambient", true);
        this.particles = config.getBoolean("settings.particles", false);
        this.icon = config.getBoolean("settings.icon", true);
        this.removeOnUnequip = config.getBoolean("settings.remove-on-unequip", true);
        this.debug = config.getBoolean("settings.debug", false);
    }

    public int intervalTicks() {
        return intervalTicks;
    }

    public void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refresh(player);
        }
    }

    /** Recomputes and applies the powers for a single player. Safe to call every tick. */
    public void refresh(Player player) {
        Map<NamespacedKey, Integer> counts = TrimScanner.countEquipped(player);
        Set<PotionEffectType> now = new HashSet<>();
        int duration = intervalTicks + bufferTicks;

        counts.forEach((material, pieces) -> {
            MaterialPowers powers = trimConfig.powers(material).orElse(null);
            if (powers == null) {
                if (debug) {
                    plugin.getLogger().info("No powers configured for trim material " + material.getKey());
                }
                return;
            }

            TrimTier tier = powers.tierFor(pieces).orElse(null);
            if (tier == null) {
                if (debug) {
                    plugin.getLogger().info("No tier at or below " + pieces + " piece(s) for " + material.getKey());
                }
                return;
            }

            if (debug) {
                plugin.getLogger().info(player.getName() + ": " + material.getKey() + " x" + pieces
                        + " -> tier " + tier.pieces() + " (" + tier.effects().size() + " effect(s))");
            }

            for (EffectDefinition definition : tier.effects()) {
                now.add(definition.effectType());
                int effectDuration = definition.hasExplicitDuration() ? definition.durationTicks() : duration;
                player.addPotionEffect(new PotionEffect(
                        definition.effectType(), effectDuration, definition.amplifier(), ambient, particles, icon));
            }
        });

        Set<PotionEffectType> previous = granted.getOrDefault(player.getUniqueId(), Set.of());
        if (removeOnUnequip) {
            for (PotionEffectType type : previous) {
                if (!now.contains(type)) {
                    player.removePotionEffect(type);
                }
            }
        }

        if (now.isEmpty()) {
            granted.remove(player.getUniqueId());
        } else {
            granted.put(player.getUniqueId(), now);
        }
    }

    /** Drops tracked state without touching the player (quits, respawns). */
    public void forget(Player player) {
        granted.remove(player.getUniqueId());
    }

    /** @return the effect types currently granted by trims, for display. */
    public Set<PotionEffectType> activeEffects(Player player) {
        return Set.copyOf(granted.getOrDefault(player.getUniqueId(), Set.of()));
    }
}
