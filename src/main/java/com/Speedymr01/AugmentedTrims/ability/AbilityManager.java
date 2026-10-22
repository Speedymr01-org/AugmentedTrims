package com.Speedymr01.AugmentedTrims.ability;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.bukkit.entity.Player;

/** Holds every registered ability and handles global cancellation. */
public final class AbilityManager {

    private final Map<String, Ability> byMaterial = new LinkedHashMap<>();

    public void register(Ability ability) {
        byMaterial.put(ability.materialKey().toLowerCase(Locale.ROOT), ability);
    }

    public Optional<Ability> ability(String materialKey) {
        return Optional.ofNullable(byMaterial.get(materialKey.toLowerCase(Locale.ROOT)));
    }

    public Collection<Ability> all() {
        return byMaterial.values();
    }

    /** Cancels any active cast for this player across all abilities. */
    public void cancelAll(Player player) {
        for (Ability ability : byMaterial.values()) {
            ability.cancel(player);
        }
    }

    public void shutdown() {
        for (Ability ability : byMaterial.values()) {
            ability.shutdown();
        }
    }
}
