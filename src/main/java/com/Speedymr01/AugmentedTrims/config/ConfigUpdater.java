package com.Speedymr01.AugmentedTrims.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;

/**
 * Keeps the on-disk {@code config.yml} in step with the one bundled in the jar.
 *
 * <p>The file is rebuilt from the bundled template: the values of the current file are taken over
 * into the new template, so the result is always the up to date file - new keys with their
 * bundled values, the current comments, nothing that the plugin no longer knows about - while
 * every value the server owner set is kept exactly as it was.
 *
 * <p>The merge happens on the text level, so the layout, the comments and the blank lines of the
 * bundled file survive. The rewritten file is parsed and checked before it replaces the old one,
 * and a file that cannot be parsed is never touched.
 */
public final class ConfigUpdater {

    /** What a merge did, for logging. */
    public record SyncResult(List<String> added, List<String> removed, List<String> kept,
                              String text, boolean changed) {
    }

    /** Where a value sits in the template text. */
    private record Slot(int start, int end, int startColumn, int endColumn, boolean block) {
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

        String template = readResource(plugin, resource);
        if (template == null) {
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

        String currentText = read(file);
        SyncResult result = merge(template, current, currentText);
        if (!result.changed()) {
            return;
        }

        File temporary = new File(file.getParentFile(), fileName + ".tmp");
        try {
            // Keep the line endings the file already used.
            String text = currentText.contains("\r\n")
                    ? result.text().replace("\n", "\r\n")
                    : result.text();
            Files.writeString(temporary.toPath(), text, StandardCharsets.UTF_8);
            // Safety net: the rewritten file has to parse and has to hold the owner's values.
            YamlConfiguration check = YamlConfiguration.loadConfiguration(temporary);
            if (!check.isSet("config-version") && current.isSet("config-version")) {
                throw new IOException("the rewritten file lost the config version");
            }
            for (String path : result.kept()) {
                if (!check.isSet(path)) {
                    throw new IOException("the rewritten file lost " + path);
                }
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().warning("Could not rewrite " + fileName + " (" + ex.getMessage()
                    + ") - the old file was kept.");
            try {
                Files.deleteIfExists(temporary.toPath());
            } catch (IOException ignored) {
                // Nothing to do about it.
            }
            return;
        }

        plugin.getLogger().info(fileName + " updated to the bundled version: "
                + result.kept().size() + " value(s) kept, "
                + result.added().size() + " added, "
                + result.removed().size() + " removed");
        if (!result.added().isEmpty()) {
            plugin.getLogger().info("  added: " + String.join(", ", result.added()));
        }
        if (!result.removed().isEmpty()) {
            plugin.getLogger().info("  removed: " + String.join(", ", result.removed()));
        }
    }

    /**
     * Builds the new file: the bundled template with the owner's values taken over.
     *
     * @param template the bundled file
     * @param current the owner's file
     * @param currentText the owner's file as it is on disk, may be null
     */
    public static SyncResult merge(String template, ConfigurationSection current, String currentText) {
        // The file is worked on with plain line feeds, so a Windows file and a Linux file end up
        // the same and the merge converges.
        String normalised = template.replace("\r\n", "\n");
        Map<String, Slot> slots = slots(normalised);
        List<String> added = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        List<String> removed = new ArrayList<>();

        // The owner's paths that the template does not know any more.
        for (String path : paths(current)) {
            if (!slots.containsKey(path)) {
                removed.add(path);
            }
        }

        String[] lines = normalised.split("\n", -1);
        Map<Integer, String> replacements = new LinkedHashMap<>();
        Set<Integer> dropped = new LinkedHashSet<>();
        Map<Integer, String> blocks = new LinkedHashMap<>();

        for (Map.Entry<String, Slot> entry : slots.entrySet()) {
            String path = entry.getKey();
            Slot slot = entry.getValue();
            if (!current.isSet(path)) {
                added.add(path);
                continue;
            }
            if (current.isConfigurationSection(path)) {
                // The owner put a section where the template has a value: keep the template.
                continue;
            }
            Object value = current.get(path);
            boolean block = slot.block();
            String replacement;
            if (block) {
                replacement = dumpBlock(value, slot.startColumn());
            } else {
                String original = lines[slot.start()];
                String prefix = original.substring(0, Math.min(slot.startColumn(), original.length()));
                String style = styleOf(original, slot.startColumn());
                String formatted = formatValue(value, style);
                replacement = formatted == null ? null : prefix + formatted;
            }
            if (replacement == null) {
                continue;
            }
            kept.add(path);

            if (block) {
                // The key sits on the line above the first item of the list: the whole block is
                // replaced by the dumped list.
                for (int line = slot.start(); line <= slot.end(); line++) {
                    dropped.add(line);
                }
                blocks.put(slot.start(), replacement);
            } else {
                replacements.put(slot.start(), replacement);
            }
        }

        String text = rebuild(lines, replacements, dropped, blocks);
        boolean changed = currentText == null || !text.equals(currentText.replace("\r\n", "\n"));
        return new SyncResult(List.copyOf(added), List.copyOf(removed), List.copyOf(kept),
                text, changed);
    }

    // ------------------------------------------------------------------- text

    /** Maps every value of the template to the place it occupies. */
    private static Map<String, Slot> slots(String template) {
        Map<String, Slot> slots = new LinkedHashMap<>();
        Node root = new Yaml().compose(new StringReader(template));
        collect(root, "", slots);
        return slots;
    }

    private static void collect(Node node, String prefix, Map<String, Slot> slots) {
        if (!(node instanceof MappingNode mapping)) {
            return;
        }
        for (NodeTuple tuple : mapping.getValue()) {
            Node keyNode = tuple.getKeyNode();
            String key = keyNode instanceof org.yaml.snakeyaml.nodes.ScalarNode scalar
                    ? String.valueOf(scalar.getValue())
                    : keyNode.toString();
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Node value = tuple.getValueNode();
            if (value instanceof MappingNode) {
                collect(value, path, slots);
                continue;
            }
            int start = value.getStartMark().getLine();
            int startColumn = value.getStartMark().getColumn();
            int end = value.getEndMark().getLine();
            if (value instanceof org.yaml.snakeyaml.nodes.SequenceNode sequence
                    && !sequence.getValue().isEmpty()) {
                // The end mark of a list points at the next key, skipping the comments in
                // between. The last item knows where the list really ends.
                Node last = sequence.getValue().get(sequence.getValue().size() - 1);
                end = last.getEndMark().getLine();
            }
            // A value that spans more than one line is a block (a list or a nested map).
            boolean block = end > start;
            slots.put(path, new Slot(start, end, startColumn, value.getEndMark().getColumn(), block));
        }
    }

    /**
     * Dumps a value that sits behind a key on the same line.
     *
     * @return the value as one line of YAML, or null when it does not fit on one line
     */
    private static String dumpScalar(Object value) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.FLOW);
        options.setWidth(4096);
        String dumped;
        try {
            dumped = new Yaml(options).dump(value);
        } catch (RuntimeException ex) {
            return null;
        }
        String[] lines = dumped.split("\n", -1);
        for (String line : lines) {
            if (!line.isEmpty() && !line.equals("...")) {
                if (line != lines[0]) {
                    // More than one line: the value cannot go behind the key.
                    return null;
                }
                return line;
            }
        }
        return null;
    }

    /** The quoting the template uses for that value. */
    private static String styleOf(String line, int column) {
        if (column >= line.length()) {
            return "PLAIN";
        }
        char first = line.charAt(column);
        if (first == '"') {
            return "DOUBLE";
        }
        if (first == '\'') {
            return "SINGLE";
        }
        return "PLAIN";
    }

    /**
     * Renders a value the way the template writes it, so the file keeps its style and the merge
     * stays stable when it runs again.
     */
    private static String formatValue(Object value, String style) {
        if (value instanceof String text) {
            return switch (style) {
                case "DOUBLE" -> "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
                case "SINGLE" -> "'" + text.replace("'", "''") + "'";
                default -> dumpScalar(value);
            };
        }
        return dumpScalar(value);
    }

    /** Dumps a value as a block, indented to the column it has to sit in. */
    private static String dumpBlock(Object value, int indent) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setWidth(4096);
        options.setIndent(2);
        // SnakeYAML writes a list that stands on its own in the compact form, so it is dumped
        // behind a key and that key is cut off again.
        boolean wrapped = value instanceof List<?>;
        Object toDump = wrapped ? Map.of("v", value) : value;
        String dumped;
        try {
            dumped = new Yaml(options).dump(toDump);
        } catch (RuntimeException ex) {
            return null;
        }
        String[] lines = dumped.split("\n", -1);
        String pad = " ".repeat(Math.max(0, indent));
        StringBuilder builder = new StringBuilder();
        for (int i = wrapped ? 1 : 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            builder.append(pad).append(line).append('\n');
        }
        return builder.toString();
    }

    /** Puts the replacements back into the template text. */
    private static String rebuild(String[] lines, Map<Integer, String> replacements,
                                  Set<Integer> dropped, Map<Integer, String> blocks) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String block = blocks.get(i);
            if (block != null) {
                builder.append(block);
                continue;
            }
            if (dropped.contains(i)) {
                continue;
            }
            String replacement = replacements.get(i);
            builder.append(replacement != null ? replacement : lines[i]).append('\n');
        }
        String result = builder.toString();
        // The split above adds one newline too many at the end.
        if (result.endsWith("\n")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    // ------------------------------------------------------------------ paths

    /** All leaf paths of a configuration section. */
    private static Set<String> paths(ConfigurationSection section) {
        Set<String> paths = new LinkedHashSet<>();
        collectPaths(section, "", paths);
        return paths;
    }

    private static void collectPaths(ConfigurationSection section, String prefix, Set<String> paths) {
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            // The lookup is relative to the section we are in, the collected path is absolute.
            if (section.isConfigurationSection(key)) {
                collectPaths(section.getConfigurationSection(key), path, paths);
            } else {
                paths.add(path);
            }
        }
    }

    // ------------------------------------------------------------------- i/o

    private static String readResource(JavaPlugin plugin, String resource) {
        try (InputStream stream = plugin.getResource(resource)) {
            if (stream == null) {
                plugin.getLogger().warning("Bundled " + resource + " is missing from the jar.");
                return null;
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not read bundled " + resource + ": " + ex.getMessage());
            return null;
        }
    }

    private static String read(File file) {
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }
}
