package com.Speedymr01.AugmentedTrims.config;

import org.bukkit.potion.PotionEffectType;

/**
 * A single potion effect granted by a trim tier.
 *
 * @param type         the effect type
 * @param amplifier    0 = level I
 * @param durationTicks explicit duration, or -1 to use the plugin's refresh interval + buffer
 */
public record EffectDefinition(PotionEffectType type, int amplifier, int durationTicks) {

    public PotionEffectType effectType() {
        return type;
    }

    public boolean hasExplicitDuration() {
        return durationTicks > 0;
    }
}
