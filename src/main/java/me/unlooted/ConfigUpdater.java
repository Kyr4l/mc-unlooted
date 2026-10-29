package me.unlooted;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brings an existing config.yml up to date after a plugin update.
 *
 * The file is rebuilt from the config bundled in the new version, so new settings and messages arrive
 * with their comments, and every value the server owner set is carried over. Settings the new version
 * no longer uses are kept at the end as comments. The old file is backed up first. Nothing is written
 * when the config is already current.
 */
final class ConfigUpdater {

    /** Sections whose entries are merged one by one, instead of as a single value. */
    private static final Set<String> SECTIONS = new LinkedHashSet<String>(Arrays.asList("messages"));
    private static final Pattern TOP = Pattern.compile("^([A-Za-z0-9_-]+):(.*)$");
    private static final Pattern NESTED = Pattern.compile("^  ([A-Za-z0-9_-]+):(.*)$");

    private ConfigUpdater() {
    }

    /** Updates the file if needed. Returns true when it was rewritten. */
    static boolean update(File file, InputStream bundled, Logger log) throws IOException {
        if (!file.exists() || bundled == null) {
            return false;
        }
        List<String> template = new ArrayList<String>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(bundled, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                template.add(line);
            }
        }
        YamlConfiguration user = new YamlConfiguration();
        try {
            user.load(file);
        } catch (InvalidConfigurationException ex) {
            log.warning("config.yml has a syntax error, so it was not updated. Fix it, or delete it to get a fresh one.");
            return false;
        }
        List<String> merged = merge(template, user);
        if (merged == null) {
            return false;
        }
        File backup = new File(file.getParentFile(), "config.yml.backup-" + user.getInt("config-version", 0));
        Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        File tmp = new File(file.getParentFile(), "config.yml.tmp");
        Files.write(tmp.toPath(), merged, StandardCharsets.UTF_8);
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        log.info("Updated config.yml to the new version and kept your settings. The old file is saved as " + backup.getName() + ".");
        return true;
    }

    /**
     * The template with the user's values filled in, or null when the user's config is already current:
     * same config-version, nothing missing and nothing obsolete.
     */
    static List<String> merge(List<String> template, YamlConfiguration user) {
        YamlConfiguration defaults = new YamlConfiguration();
        try {
            defaults.loadFromString(String.join("\n", template));
        } catch (InvalidConfigurationException ex) {
            throw new IllegalStateException("Bundled config.yml is invalid", ex);
        }
        if (!needsUpdate(defaults, user)) {
            return null;
        }

        List<String> out = new ArrayList<String>();
        String section = null;
        for (String line : template) {
            Matcher top = TOP.matcher(line);
            Matcher nested = NESTED.matcher(line);
            if (top.matches()) {
                String key = top.group(1);
                section = SECTIONS.contains(key) ? key : null;
                if (section != null || key.equals("config-version") || !user.contains(key) || user.isConfigurationSection(key)) {
                    out.add(line);
                } else {
                    out.addAll(dump(key, user.get(key)));
                }
            } else if (section != null && nested.matches()) {
                String path = section + "." + nested.group(1);
                if (user.contains(path) && !user.isConfigurationSection(path)) {
                    out.addAll(dumpNested(section, nested.group(1), user.get(path)));
                } else {
                    out.add(line);
                }
            } else {
                if (!line.startsWith(" ") && !line.startsWith("#") && !line.trim().isEmpty()) {
                    section = null;
                }
                out.add(line);
            }
        }

        List<String> obsolete = obsolete(defaults, user);
        if (!obsolete.isEmpty()) {
            out.add("");
            out.add("# Settings below are not used by this version of Unlooted. They were kept from your old config.");
            out.addAll(obsolete);
        }
        return out;
    }

    private static boolean needsUpdate(YamlConfiguration defaults, YamlConfiguration user) {
        if (defaults.getInt("config-version") != user.getInt("config-version", 0)) {
            return true;
        }
        for (String key : defaults.getKeys(true)) {
            if (!defaults.isConfigurationSection(key) && !user.contains(key)) {
                return true;
            }
        }
        return !obsolete(defaults, user).isEmpty();
    }

    /** User settings the template doesn't have, as commented-out lines. */
    private static List<String> obsolete(YamlConfiguration defaults, YamlConfiguration user) {
        List<String> out = new ArrayList<String>();
        for (String key : user.getKeys(false)) {
            if (SECTIONS.contains(key) && user.isConfigurationSection(key)) {
                ConfigurationSection userSection = user.getConfigurationSection(key);
                for (String entry : userSection.getKeys(false)) {
                    if (!defaults.contains(key + "." + entry)) {
                        for (String line : dumpNested(key, entry, userSection.get(entry))) {
                            out.add("# " + key + "." + line.trim());
                        }
                    }
                }
            } else if (!defaults.contains(key)) {
                for (String line : dump(key, user.get(key))) {
                    out.add("# " + line);
                }
            }
        }
        return out;
    }

    /** One top-level setting written the way Bukkit writes YAML. */
    private static List<String> dump(String key, Object value) {
        YamlConfiguration one = new YamlConfiguration();
        one.set(key, value instanceof ConfigurationSection ? ((ConfigurationSection) value).getValues(true) : value);
        return lines(one.saveToString());
    }

    /** One entry inside a section, indented like the template. */
    private static List<String> dumpNested(String section, String key, Object value) {
        YamlConfiguration one = new YamlConfiguration();
        one.set(section + "." + key, value);
        List<String> lines = lines(one.saveToString());
        return lines.subList(1, lines.size());
    }

    private static List<String> lines(String text) {
        List<String> out = new ArrayList<String>(Arrays.asList(text.split("\n")));
        while (!out.isEmpty() && out.get(out.size() - 1).trim().isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }
}
