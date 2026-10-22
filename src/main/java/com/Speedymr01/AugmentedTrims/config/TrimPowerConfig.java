package com.Speedymr01.AugmentedTrims.config;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads and holds the material -&gt; tier -&gt; effects power table from {@code trims.yml}.
 *
 * <p>Everything is resolved through the registry by {@link NamespacedKey}, never by
 * compile-time constants, so trim materials added in future Minecraft drops work without
 * a plugin rebuild - operators just add a {@code trims.yml} entry.
 */
public final class TrimPowerConfig {

    public static final String FILE_NAME = "trims.yml";
    public static final int MAX_PIECES = 4;

    private final JavaPlugin plugin;
    private final Map<NamespacedKey, MaterialPowers> materials = new LinkedHashMap<>();
    private YamlConfiguration yaml = new YamlConfiguration();

    private TrimPowerConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public static TrimPowerConfig load(JavaPlugin plugin) {
        TrimPowerConfig config = new TrimPowerConfig(plugin);
        config.reload();
        return config;
    }

    /** Re-reads {@code trims.yml} from disk, creating it from the jar if missing. */
    public void reload() {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }

        yaml = YamlConfiguration.loadConfiguration(file);
        if (plugin.getResource(FILE_NAME) != null) {
            try (InputStreamReader reader = new InputStreamReader(
                    plugin.getResource(FILE_NAME), StandardCharsets.UTF_8)) {
                yaml.setDefaults(YamlConfiguration.loadConfiguration(reader));
            } catch (Exception e) {
                plugin.getLogger().warning("Could not read bundled " + FILE_NAME + ": " + e.getMessage());
            }
        }

        materials.clear();
        parseMaterials();
    }

    private void parseMaterials() {
        ConfigurationSection root = yaml.getConfigurationSection("materials");
        if (root == null) {
            plugin.getLogger().warning(FILE_NAME + " has no 'materials' section - no trim powers will apply.");
            return;
        }

        for (String rawKey : root.getKeys(false)) {
            NamespacedKey materialKey = resolveMaterialKey(rawKey);
            ConfigurationSection materialSection = root.getConfigurationSection(rawKey);
            if (materialKey == null || materialSection == null) {
                plugin.getLogger().warning("Skipping invalid trim material entry '" + rawKey + "' in " + FILE_NAME);
                continue;
            }

            NavigableMap<Integer, TrimTier> tiers = parseTiers(materialKey, materialSection);
            if (tiers.isEmpty()) {
                plugin.getLogger().warning("Trim material '" + rawKey + "' has no valid tiers - skipped.");
                continue;
            }

            String display = materialSection.getString("display");
            materials.put(materialKey, new MaterialPowers(materialKey, display, tiers));
        }

        int effects = materials.values().stream()
                .flatMap(m -> m.tiers().values().stream())
                .mapToInt(t -> t.effects().size())
                .sum();
        plugin.getLogger().info("Loaded " + materials.size() + " trim material(s), " + effects + " tiered effect(s).");
    }

    private NavigableMap<Integer, TrimTier> parseTiers(NamespacedKey materialKey, ConfigurationSection materialSection) {
        NavigableMap<Integer, TrimTier> tiers = new TreeMap<>();
        ConfigurationSection tierSection = materialSection.getConfigurationSection("tiers");
        if (tierSection == null) {
            return tiers;
        }

        for (String rawTier : tierSection.getKeys(false)) {
            int pieces;
            try {
                pieces = Integer.parseInt(rawTier.trim());
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("Ignoring non-numeric tier '" + rawTier + "' for " + materialKey.getKey());
                continue;
            }

            if (pieces < 1 || pieces > MAX_PIECES) {
                plugin.getLogger().warning("Ignoring out-of-range tier " + pieces + " for " + materialKey.getKey()
                        + " (valid: 1-" + MAX_PIECES + ")");
                continue;
            }

            List<Map<?, ?>> rawEffects = tierSection.getMapList(rawTier + ".effects");
            List<EffectDefinition> effects = new ArrayList<>();
            for (Map<?, ?> raw : rawEffects) {
                EffectDefinition definition = parseEffect(materialKey, pieces, raw);
                if (definition != null) {
                    effects.add(definition);
                }
            }

            if (effects.isEmpty()) {
                plugin.getLogger().warning("Tier " + pieces + " of " + materialKey.getKey() + " has no valid effects - skipped.");
                continue;
            }

            tiers.put(pieces, new TrimTier(pieces, effects));
        }

        return tiers;
    }

    private EffectDefinition parseEffect(NamespacedKey materialKey, int pieces, Map<?, ?> raw) {
        Object rawType = raw.get("type");
        if (rawType == null) {
            plugin.getLogger().warning("Effect without 'type' in " + materialKey.getKey() + " tier " + pieces + " - skipped.");
            return null;
        }

        NamespacedKey effectKey = rawType.toString().contains(":")
                ? NamespacedKey.fromString(rawType.toString().toLowerCase())
                : NamespacedKey.minecraft(rawType.toString().toLowerCase());
        if (effectKey == null) {
            plugin.getLogger().warning("Invalid effect id '" + rawType + "' - skipped.");
            return null;
        }

        PotionEffectType type = Registry.EFFECT.get(effectKey);
        if (type == null) {
            plugin.getLogger().warning("Unknown potion effect '" + rawType + "' - skipped.");
            return null;
        }

        int amplifier = Math.max(0, Math.min(255, asInt(raw.get("amplifier"), 0)));
        int duration = asInt(raw.get("duration-ticks"), -1);
        return new EffectDefinition(type, amplifier, duration > 0 ? duration : -1);
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    private static NamespacedKey resolveMaterialKey(String raw) {
        String trimmed = raw.trim().toLowerCase();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.contains(":") ? NamespacedKey.fromString(trimmed) : NamespacedKey.minecraft(trimmed);
    }

    public Optional<MaterialPowers> powers(NamespacedKey material) {
        return Optional.ofNullable(materials.get(material));
    }

    public Map<NamespacedKey, MaterialPowers> materials() {
        return Map.copyOf(materials);
    }
}
