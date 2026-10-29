package me.unlooted;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** The shipped config.yml must parse and list every message with its built-in default. */
public class ConfigTest {

    @Test
    public void shippedConfigMatchesDefaults() throws Exception {
        YamlConfiguration yml = new YamlConfiguration();
        yml.load(new InputStreamReader(ConfigTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        for (String key : new String[] {"loot-delay-seconds", "loot-interval-seconds", "owner-priority-seconds",
                "expire-after-days", "combat-block-seconds", "max-pages", "excluded-worlds", "excluded-items",
                "ignore-unpickupable-items", "broadcast-message"}) {
            assertTrue("config.yml lacks " + key, yml.contains(key));
        }
        assertNotNull(yml.getConfigurationSection("messages"));
        for (Map.Entry<String, String> entry : Messages.DEFAULTS.entrySet()) {
            assertEquals("message " + entry.getKey(), entry.getValue(), yml.getString("messages." + entry.getKey()));
        }
        assertEquals(Messages.DEFAULTS.size(), yml.getConfigurationSection("messages").getKeys(false).size());
    }
}
