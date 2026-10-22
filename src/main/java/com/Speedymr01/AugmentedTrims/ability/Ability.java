package com.Speedymr01.AugmentedTrims.ability;

import org.bukkit.entity.Player;

/** A trim-material ability triggered from an attuned Ability Star. */
public interface Ability {

    /** Trim material id this ability belongs to, e.g. {@code iron}. */
    String materialKey();

    /** Display name used in messages. */
    default String displayName() {
        String key = materialKey().replace('_', ' ');
        return Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }

    /** Cooldown in seconds for the given piece tier (1-4). */
    int cooldownSeconds(int tier);

    /** Fires the ability. Called on the main thread after all gate checks passed. */
    void cast(Player player, int tier);

    /** Stops any running instance for this player (death, logout, disable). */
    default void cancel(Player player) {
    }

    /** Releases everything (plugin disable). */
    default void shutdown() {
    }
}
