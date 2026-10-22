package com.Speedymr01.AugmentedTrims.star;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * PDC keys shared across the star system. Initialised once from {@code onEnable}.
 */
public final class StarKeys {

    public static NamespacedKey STAR;
    public static NamespacedKey ABILITY;
    public static NamespacedKey REFLECTED;
    public static NamespacedKey ABILITY_ARROW;

    private StarKeys() {
    }

    public static void init(JavaPlugin plugin) {
        STAR = new NamespacedKey(plugin, "star");
        ABILITY = new NamespacedKey(plugin, "ability");
        REFLECTED = new NamespacedKey(plugin, "reflected");
        ABILITY_ARROW = new NamespacedKey(plugin, "ability_arrow");
    }
}
