package com.Speedymr01.AugmentedTrims.star;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Authoritative, in-memory ability cooldowns.
 *
 * <p>Timers live here and never on the item, so removing armour, dropping or replacing the
 * star, or relogging cannot reset them. A server restart clears everything by design.
 *
 * <p>The vanilla item cooldown is only a <em>view</em> of this state: it is written when the
 * state class changes (ready / cooling / locked) so the icon greys out for the right material
 * without restarting the sweep animation every second.
 */
public final class StarCooldownService {

    /** Effectively "locked": refreshed every second, so it is really the hasArmour state that matters. */
    private static final int LOCK_TICKS = 20 * 60 * 60 * 24;

    private final JavaPlugin plugin;

    /** player -> material -> epoch millis when the cooldown ends. */
    private final Map<UUID, Map<String, Long>> expiry = new HashMap<>();

    /** player -> material -> last written visual state, to avoid re-writing every tick. */
    private final Map<UUID, Map<String, Integer>> applied = new HashMap<>();

    public StarCooldownService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public int remainingTicks(Player player, String material) {
        Map<String, Long> perPlayer = expiry.get(player.getUniqueId());
        if (perPlayer == null) {
            return 0;
        }
        Long ends = perPlayer.get(material);
        if (ends == null) {
            return 0;
        }
        long remainingMs = ends - System.currentTimeMillis();
        if (remainingMs <= 0) {
            perPlayer.remove(material);
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, (remainingMs + 49) / 50);
    }

    public boolean isOnCooldown(Player player, String material) {
        return remainingTicks(player, material) > 0;
    }

    public void start(Player player, String material, int seconds) {
        expiry.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .put(material, System.currentTimeMillis() + seconds * 1000L);
        applied.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>()).remove(material);
    }

    /**
     * Writes the visual cooldown overlay for one material.
     *
     * @param hasArmour whether the player currently wears at least one piece of that material
     */
    public void reconcile(Player player, String material, boolean hasArmour) {
        int remaining = remainingTicks(player, material);
        int target;
        int state;
        if (remaining > 0) {
            target = remaining;
            state = 1;
        } else if (!hasArmour) {
            target = LOCK_TICKS;
            state = 2;
        } else {
            target = 0;
            state = 0;
        }

        Map<String, Integer> perPlayer = applied.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        Integer previous = perPlayer.get(material);
        boolean changed = previous == null || previous != state;
        if (changed) {
            player.setCooldown(AbilityStarFactory.cooldownGroup(material), target);
            perPlayer.put(material, state);
        }
    }

    /** Drops all state for a player (quit / death screen). */
    public void forget(Player player) {
        expiry.remove(player.getUniqueId());
        applied.remove(player.getUniqueId());
    }

    /** Clears only the visual memo so the next reconcile re-writes it (used on respawn). */
    public void resetVisuals(Player player) {
        applied.remove(player.getUniqueId());
    }

    /** Wipes every cooldown for a player (admin command / testing). */
    public void clearAll(Player player) {
        expiry.remove(player.getUniqueId());
        applied.remove(player.getUniqueId());
    }
}
