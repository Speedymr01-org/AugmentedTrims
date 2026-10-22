package com.Speedymr01.AugmentedTrims.star;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.Speedymr01.AugmentedTrims.config.TrimPowerConfig;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.UseCooldown;

/**
 * Builds and inspects Ability Stars.
 *
 * <p>Every star carries {@code augmentedtrims:star}; attuned stars additionally carry
 * {@code augmentedtrims:ability} (the trim material key) and a distinct
 * {@code minecraft:use_cooldown} group so each material greys out independently.
 */
public final class AbilityStarFactory {

    private static final String NAMESPACE = "augmentedtrims";

    /** Pretty names for the vanilla trim materials; unknown/future materials fall back to title case. */
    private static final Map<String, String> PRETTY_NAMES = Map.ofEntries(
            Map.entry("copper", "Copper"),
            Map.entry("iron", "Iron"),
            Map.entry("gold", "Gold"),
            Map.entry("diamond", "Diamond"),
            Map.entry("emerald", "Emerald"),
            Map.entry("lapis", "Lapis"),
            Map.entry("netherite", "Netherite"),
            Map.entry("amethyst", "Amethyst"),
            Map.entry("quartz", "Quartz"),
            Map.entry("redstone", "Redstone"),
            Map.entry("resin", "Resin"));

    private final JavaPlugin plugin;
    private final TrimPowerConfig trimConfig;

    public AbilityStarFactory(JavaPlugin plugin, TrimPowerConfig trimConfig) {
        this.plugin = plugin;
        this.trimConfig = trimConfig;
    }

    /** @return the cooldown group key used for a material's star. */
    public static Key cooldownGroup(String materialKey) {
        return Key.key(NAMESPACE, "ability_" + materialKey.toLowerCase(Locale.ROOT));
    }

    public ItemStack createFormless() {
        ItemStack stack = new ItemStack(Material.NETHER_STAR);
        stack.editMeta(meta -> {
            meta.displayName(styled(Component.text("Formless Star", NamedTextColor.WHITE)));
            meta.lore(List.of(
                    styled(Component.text("Not attuned to any material.", NamedTextColor.GRAY)),
                    styled(Component.text("Attune at a smithing table.", NamedTextColor.DARK_GRAY))));
            meta.setMaxStackSize(1);
            meta.getPersistentDataContainer().set(StarKeys.STAR, PersistentDataType.BYTE, (byte) 1);
        });
        return stack;
    }

    /**
     * @param materialKey       trim material id, e.g. {@code copper}
     * @param cooldownSeconds   default overlay length (the plugin drives real timers)
     */
    public ItemStack createAttuned(String materialKey, int cooldownSeconds) {
        String material = materialKey.toLowerCase(Locale.ROOT);
        String display = displayName(material);

        ItemStack stack = new ItemStack(Material.NETHER_STAR);
        stack.editMeta(meta -> {
            meta.displayName(styled(Component.text("Attuned star: " + display, NamedTextColor.AQUA)));
            meta.lore(List.of(
                    styled(Component.text("Right-click to activate.", NamedTextColor.DARK_GRAY))));
            meta.setEnchantmentGlintOverride(true);
            meta.setMaxStackSize(1);
            var pdc = meta.getPersistentDataContainer();
            pdc.set(StarKeys.STAR, PersistentDataType.BYTE, (byte) 1);
            pdc.set(StarKeys.ABILITY, PersistentDataType.STRING, material);
        });

        stack.setData(DataComponentTypes.USE_COOLDOWN,
                UseCooldown.useCooldown(Math.max(1, cooldownSeconds))
                        .cooldownGroup(cooldownGroup(material))
                        .build());
        return stack;
    }

    public String displayName(String materialKey) {
        String key = materialKey.toLowerCase(Locale.ROOT);

        Optional<String> configured = trimConfig.powers(NamespacedKey.minecraft(key))
                .map(p -> stripColours(p.displayOrId()))
                .filter(display -> !display.isBlank() && !display.equalsIgnoreCase(key));
        if (configured.isPresent()) {
            return configured.get();
        }
        String pretty = PRETTY_NAMES.get(key);
        return pretty != null ? pretty : titleCase(key);
    }

    /** {@code lapis_lazuli} -> {@code Lapis Lazuli}. */
    private static String titleCase(String key) {
        StringBuilder builder = new StringBuilder();
        for (String part : key.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.isEmpty() ? key : builder.toString();
    }

    /** Removes legacy colour codes so configured displays can be used inside Components. */
    private static String stripColours(String value) {
        return value.replaceAll("(?i)[&\u00a7][0-9a-fk-orx]", "").trim();
    }

    public boolean isStar(ItemStack stack) {
        if (stack == null || stack.getType() != Material.NETHER_STAR || !stack.hasItemMeta()) {
            return false;
        }
        return stack.getItemMeta().getPersistentDataContainer()
                .has(StarKeys.STAR, PersistentDataType.BYTE);
    }

    /** @return the attuned material key, or empty for a formless star. */
    public Optional<String> abilityOf(ItemStack stack) {
        if (!isStar(stack)) {
            return Optional.empty();
        }
        String value = stack.getItemMeta().getPersistentDataContainer()
                .get(StarKeys.ABILITY, PersistentDataType.STRING);
        return Optional.ofNullable(value);
    }

    /** Attunes an existing star stack in place (used by the smithing result). */
    public ItemStack attuneCopy(String materialKey, int cooldownSeconds) {
        return createAttuned(materialKey, cooldownSeconds);
    }

    public boolean hasStarIdentity(ItemMeta meta) {
        return meta != null && meta.getPersistentDataContainer().has(StarKeys.STAR, PersistentDataType.BYTE);
    }

    public JavaPlugin plugin() {
        return plugin;
    }

    private static Component styled(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }
}
