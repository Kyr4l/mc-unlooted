package me.unlooted;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ConfigUpdaterTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static List<String> template() throws Exception {
        List<String> lines = new ArrayList<String>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                ConfigUpdaterTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration yml = new YamlConfiguration();
        yml.loadFromString(text);
        return yml;
    }

    private static YamlConfiguration yaml(List<String> lines) throws Exception {
        return yaml(String.join("\n", lines));
    }

    /** A config as written by 0.1.0: two settings, one of them gone since. */
    private static final String OLD = "# my notes\nloot-delay-seconds: 120\nrows: 3\n";

    @Test
    public void oldConfigGetsNewSettingsAndKeepsValues() throws Exception {
        List<String> merged = ConfigUpdater.merge(template(), yaml(OLD));
        YamlConfiguration result = yaml(merged);
        assertEquals(120, result.getInt("loot-delay-seconds"));
        assertEquals(600, result.getInt("owner-priority-seconds"));
        assertEquals(2, result.getInt("config-version"));
        assertEquals(Messages.DEFAULTS.get("available"), result.getString("messages.available"));
        assertFalse("obsolete setting must not stay active", result.contains("rows"));
        assertTrue("obsolete setting is kept as a comment", merged.contains("# rows: 3"));
        assertTrue("comments of new settings arrive too", merged.contains("# Worlds where dropped items are never looted, for example a creative world."));
    }

    @Test
    public void customMessagesListsAndTextAreKept() throws Exception {
        String user = String.join("\n", template())
                .replace("excluded-worlds: []", "excluded-worlds:\n- creative\n- 'mini games'")
                .replace("broadcast-message: \"", "broadcast-message: \"&a'quoted' \\\"double\\\" # not a comment ")
                + "\n";
        YamlConfiguration userYaml = yaml(user);
        userYaml.set("messages.available", "&aStock: {amount}");
        userYaml.set("messages.old-message", "&7gone");
        userYaml.set("config-version", 1);
        List<String> merged = ConfigUpdater.merge(template(), userYaml);
        YamlConfiguration result = yaml(merged);
        assertEquals(Arrays.asList("creative", "mini games"), result.getStringList("excluded-worlds"));
        assertEquals(userYaml.getString("broadcast-message"), result.getString("broadcast-message"));
        assertEquals("&aStock: {amount}", result.getString("messages.available"));
        assertEquals(Messages.DEFAULTS.get("hint-half"), result.getString("messages.hint-half"));
        assertFalse(result.contains("messages.old-message"));
        assertTrue(merged.contains("# messages.old-message: '&7gone'"));
    }

    @Test
    public void currentConfigIsLeftAlone() throws Exception {
        assertNull(ConfigUpdater.merge(template(), yaml(template())));
    }

    @Test
    public void updatingTwiceChangesNothingTheSecondTime() throws Exception {
        List<String> once = ConfigUpdater.merge(template(), yaml(OLD));
        assertNull(ConfigUpdater.merge(template(), yaml(once)));
    }

    @Test
    public void fileUpdateWritesBackup() throws Exception {
        File config = folder.newFile("config.yml");
        Files.write(config.toPath(), OLD.getBytes(StandardCharsets.UTF_8));
        Logger quiet = Logger.getLogger("updater-test");
        quiet.setLevel(Level.OFF);
        assertTrue(ConfigUpdater.update(config, ConfigUpdaterTest.class.getResourceAsStream("/config.yml"), quiet));
        File backup = new File(folder.getRoot(), "config.yml.backup-0");
        assertTrue(backup.exists());
        assertEquals(OLD, new String(Files.readAllBytes(backup.toPath()), StandardCharsets.UTF_8));
        assertEquals(120, YamlConfiguration.loadConfiguration(config).getInt("loot-delay-seconds"));
        assertFalse(ConfigUpdater.update(config, ConfigUpdaterTest.class.getResourceAsStream("/config.yml"), quiet));
    }
}
