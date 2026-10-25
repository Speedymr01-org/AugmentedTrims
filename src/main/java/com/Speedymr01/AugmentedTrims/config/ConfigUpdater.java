package com.Speedymr01.AugmentedTrims.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Keeps the on-disk {@code config.yml} in step with the one bundled in the jar.
 *
 * <p>Only the shape of the file is synced: every value the server owner has edited is left
 * exactly as it is. Keys the plugin no longer knows about are dropped, keys that appeared in a
 * newer version are added with their bundled value and comments. The file is not touched at all
 * when there is nothing to do, and a file that fails to parse is never overwritten.
 */
public final class ConfigUpdater {

    /** What a merge did, for logging. */
    public record SyncResult(List<String> added, List<String> removed) {
        public boolean changed() {
            return !added.isEmpty() || !removed.isEmpty();
        }
    }

    private ConfigUpdater() {
    }

    /**
     * Syncs a data file with its bundled version, creating it when absent.
     *
     * @param resource bundled resource path inside the jar
     * @param fileName file name inside the plugin data folder
     */
    public static void sync(JavaPlugin plugin, String resource, String fileName) {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            plugin.saveResource(fileName, false);
            return;
        }

        YamlConfiguration defaults = bundled(plugin, resource);
        if (defaults == null) {
            return;
        }

        YamlConfiguration current;
        try {
            current = YamlConfiguration.loadConfiguration(file);
        } catch (RuntimeException ex) {
            // A broken hand edit must never be overwritten by the updater.
            plugin.getLogger().warning(fileName + " could not be parsed (" + ex.getMessage()
                    + ") - left untouched, fix the YAML and restart.");
            return;
        }

        String before = read(file);
        SyncResult result = merge(defaults, current);
        if (!result.changed()) {
            return;
        }

        try {
            current.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save " + fileName + ": " + ex.getMessage());
            return;
        }
        restoreHeader(file, before);

        plugin.getLogger().info(fileName + " synced with the bundled defaults: "
                + result.added().size() + " added, " + result.removed().size() + " removed");
        if (!result.added().isEmpty()) {
            plugin.getLogger().info("  added: " + String.join(", ", result.added()));
        }
        if (!result.removed().isEmpty()) {
            plugin.getLogger().info("  removed: " + String.join(", ", result.removed()));
        }
    }

    /**
     * Adds everything present in {@code defaults} but missing from {@code current}, using the
     * bundled value and comments, then removes keys the defaults no longer contain.
     * Existing values are never modified.
     *
     * <p>Both arguments must be the same section level (normally the two file roots): the
     * recursion walks subsections but always resolves paths against the roots.
     */
    public static SyncResult merge(ConfigurationSection defaults, ConfigurationSection current) {
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        addMissing(defaults, current, "", added);
        removeObsolete(defaults, current, "", removed);
        return new SyncResult(List.copyOf(added), List.copyOf(removed));
    }

    // ------------------------------------------------------------------ adding

    private static void addMissing(ConfigurationSection defaults, ConfigurationSection current,
                                   String parent, List<String> added) {
        ConfigurationSection from = parent.isEmpty() ? defaults : defaults.getConfigurationSection(parent);
        if (from == null) {
            return;
        }
        for (String key : from.getKeys(false)) {
            String path = parent.isEmpty() ? key : parent + "." + key;
            if (current.isSet(path)) {
                if (current.isConfigurationSection(path) && defaults.isConfigurationSection(path)) {
                    addMissing(defaults, current, path, added);
                }
                // Present already: keep the owner's value, whatever it is.
                continue;
            }
            copy(defaults, current, path, added);
        }
    }

    /** Copies one node (and its subtree) including comments. Children first, so sections exist. */
    private static void copy(ConfigurationSection defaults, ConfigurationSection current,
                             String path, List<String> added) {
        if (defaults.isConfigurationSection(path)) {
            ConfigurationSection section = defaults.getConfigurationSection(path);
            for (String key : section.getKeys(false)) {
                copy(defaults, current, path + "." + key, added);
            }
        } else {
            current.set(path, defaults.get(path));
            added.add(path);
        }

        List<String> comments = defaults.getComments(path);
        if (!comments.isEmpty()) {
            current.setComments(path, comments);
        }
        List<String> inline = defaults.getInlineComments(path);
        if (!inline.isEmpty()) {
            current.setInlineComments(path, inline);
        }
    }

    // ---------------------------------------------------------------- removing

    private static void removeObsolete(ConfigurationSection defaults, ConfigurationSection current,
                                       String parent, List<String> removed) {
        ConfigurationSection from = parent.isEmpty() ? current : current.getConfigurationSection(parent);
        if (from == null) {
            return;
        }
        for (String key : new ArrayList<>(from.getKeys(false))) {
            String path = parent.isEmpty() ? key : parent + "." + key;
            if (!defaults.isSet(path)) {
                current.set(path, null);
                removed.add(path);
                continue;
            }
            if (current.isConfigurationSection(path) && defaults.isConfigurationSection(path)) {
                removeObsolete(defaults, current, path, removed);
            }
        }
    }

    // ----------------------------------------------------------------- helpers

    private static YamlConfiguration bundled(JavaPlugin plugin, String resource) {
        try (InputStream stream = plugin.getResource(resource)) {
            if (stream == null) {
                plugin.getLogger().warning("Bundled " + resource + " is missing from the jar.");
                return null;
            }
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            return config;
        } catch (IOException | InvalidConfigurationException ex) {
            plugin.getLogger().warning("Could not read bundled " + resource + ": " + ex.getMessage());
            return null;
        }
    }

    /**
     * Bukkit does not always carry the leading comment block of a file through a save, so it is
     * re-prepended when the rewritten file lost it. Whitespace-only lines are also cleared of
     * indentation, which the comment writer leaves behind.
     */
    private static void restoreHeader(File file, String original) {
        String header = leadingComments(original);
        String saved = read(file).replace("\r\n", "\n");
        StringBuilder fixed = new StringBuilder();
        for (String line : saved.split("\n", -1)) {
            fixed.append(line.stripTrailing()).append('\n');
        }
        String result = fixed.toString();
        if (!header.isEmpty() && !result.startsWith(header)) {
            result = header + "\n" + result;
        }
        if (result.equals(saved)) {
            return;
        }
        try {
            Files.writeString(file.toPath(), result, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Cosmetic only: the config itself is already correct.
        }
    }

    private static String leadingComments(String text) {
        StringBuilder builder = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String stripped = line.strip();
            if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                break;
            }
            builder.append(line.stripTrailing()).append('\n');
        }
        return builder.toString().stripTrailing();
    }

    private static String read(File file) {
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }
}
