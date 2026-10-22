package com.Speedymr01.AugmentedTrims.config;

import java.util.List;

/**
 * The powers granted when a given number of armour pieces carry one trim material.
 *
 * @param pieces  the tier key (1-4 pieces of the same material)
 * @param effects effects granted at this tier
 */
public record TrimTier(int pieces, List<EffectDefinition> effects) {

    public TrimTier {
        effects = List.copyOf(effects);
    }
}
