package com.Speedymr01.AugmentedTrims.config;

import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

import org.bukkit.NamespacedKey;

/**
 * All configured tiers for one trim material.
 *
 * <p>Lookup uses {@link NavigableMap#floorEntry(Object)}: the exact piece count wins when
 * configured, otherwise the highest tier at or below the piece count is used. That lets
 * operators author only tiers 1 and 4 and still get sane behaviour for 2-3 pieces.
 */
public record MaterialPowers(NamespacedKey material, String display, NavigableMap<Integer, TrimTier> tiers) {

    public MaterialPowers {
        tiers = new TreeMap<>(tiers);
    }

    public Optional<TrimTier> tierFor(int pieces) {
        if (pieces <= 0 || tiers.isEmpty()) {
            return Optional.empty();
        }
        var entry = tiers.floorEntry(pieces);
        return entry == null ? Optional.empty() : Optional.of(entry.getValue());
    }

    public String displayOrId() {
        return display != null && !display.isBlank() ? display : material.getKey();
    }
}
