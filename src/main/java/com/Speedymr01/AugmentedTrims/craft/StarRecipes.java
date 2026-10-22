package com.Speedymr01.AugmentedTrims.craft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.Speedymr01.AugmentedTrims.ability.AbilityManager;
import com.Speedymr01.AugmentedTrims.star.AbilityStarFactory;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.plugin.java.JavaPlugin;

/** Registers the Formless Star crafting recipe and the attunement smithing recipe. */
public final class StarRecipes {

    /** Trim material id -> the item used in the smithing addition slot. */
    private static final Map<String, Material> MATERIAL_ITEMS = new LinkedHashMap<>();

    static {
        MATERIAL_ITEMS.put("copper", Material.COPPER_INGOT);
        MATERIAL_ITEMS.put("iron", Material.IRON_INGOT);
        MATERIAL_ITEMS.put("gold", Material.GOLD_INGOT);
        MATERIAL_ITEMS.put("diamond", Material.DIAMOND);
        MATERIAL_ITEMS.put("emerald", Material.EMERALD);
        MATERIAL_ITEMS.put("lapis", Material.LAPIS_LAZULI);
        MATERIAL_ITEMS.put("netherite", Material.NETHERITE_INGOT);
        MATERIAL_ITEMS.put("amethyst", Material.AMETHYST_SHARD);
        MATERIAL_ITEMS.put("quartz", Material.QUARTZ);
        MATERIAL_ITEMS.put("redstone", Material.REDSTONE);
        MATERIAL_ITEMS.put("resin", Material.RESIN_CLUMP);
    }

    private final JavaPlugin plugin;
    private final AbilityStarFactory starFactory;
    private final AbilityManager abilityManager;

    public StarRecipes(JavaPlugin plugin, AbilityStarFactory starFactory, AbilityManager abilityManager) {
        this.plugin = plugin;
        this.starFactory = starFactory;
        this.abilityManager = abilityManager;
    }

    public static Optional<String> materialFor(Material material) {
        return MATERIAL_ITEMS.entrySet().stream()
                .filter(entry -> entry.getValue() == material)
                .map(Map.Entry::getKey)
                .findFirst();
    }

    public static boolean isMaterialItem(Material material) {
        return MATERIAL_ITEMS.containsValue(material);
    }

    /** Cooldown used for the star's default {@code use_cooldown} overlay (tier 4 value). */
    public int defaultCooldownSeconds(String materialKey) {
        return abilityManager.ability(materialKey)
                .map(ability -> ability.cooldownSeconds(4))
                .orElse(60);
    }

    public void register() {
        registerFormlessRecipe();
        registerSmithingRecipe();
    }

    private void registerFormlessRecipe() {
        NamespacedKey key = new NamespacedKey(plugin, "formless_star");
        ShapedRecipe recipe = new ShapedRecipe(key, starFactory.createFormless());
        recipe.shape("TGT", "GTG", "TGT");
        recipe.setIngredient('T', Material.GHAST_TEAR);
        recipe.setIngredient('G', Material.GLASS);
        Bukkit.addRecipe(recipe);
    }

    private void registerSmithingRecipe() {
        NamespacedKey key = new NamespacedKey(plugin, "attune_star");
        RecipeChoice template = new RecipeChoice.MaterialChoice(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
        RecipeChoice base = new RecipeChoice.MaterialChoice(Material.NETHER_STAR);
        RecipeChoice addition = new RecipeChoice.MaterialChoice(new ArrayList<>(MATERIAL_ITEMS.values()));

        // Result is replaced per-material in PrepareSmithingEvent; this placeholder just makes
        // the recipe match a vanilla nether star base.
        Bukkit.addRecipe(new SmithingTransformRecipe(key, starFactory.createFormless(), template, base, addition));
    }

    /** @return the attuned result for a smithing combination, or empty when it is not ours. */
    public Optional<org.bukkit.inventory.ItemStack> smithingResult(org.bukkit.inventory.ItemStack base,
                                                                  org.bukkit.inventory.ItemStack template,
                                                                  org.bukkit.inventory.ItemStack addition) {
        if (base == null || template == null || addition == null) {
            return Optional.empty();
        }
        if (template.getType() != Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE) {
            return Optional.empty();
        }
        if (!starFactory.isStar(base)) {
            return Optional.empty();
        }
        Optional<String> material = materialFor(addition.getType());
        if (material.isEmpty()) {
            return Optional.empty();
        }
        String key = material.get().toLowerCase(Locale.ROOT);
        return Optional.of(starFactory.createAttuned(key, defaultCooldownSeconds(key)));
    }

    public List<Material> materialItems() {
        return new ArrayList<>(MATERIAL_ITEMS.values());
    }
}
