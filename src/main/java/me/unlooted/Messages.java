package me.unlooted;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every text players see, editable under "messages" in config.yml. Missing keys use the defaults
 * below. Values take & color codes and {placeholders}.
 */
final class Messages {

    static final Map<String, String> DEFAULTS = new LinkedHashMap<String, String>();

    static {
        // Chat
        DEFAULTS.put("no-permission", "&cYou don't have permission.");
        DEFAULTS.put("players-only", "Only players can open the storage.");
        DEFAULTS.put("no-items", "&eThere are no auto-looted items right now.");
        DEFAULTS.put("storage-empty-now", "&eThe storage is empty now.");
        DEFAULTS.put("usage-page", "&cUsage: /{label} [page]");
        DEFAULTS.put("page-range", "&cPage must be between 1 and {pages}.");
        DEFAULTS.put("inventory-full", "&cYour inventory is full.");
        DEFAULTS.put("reserved-stack", "&cThat stack is reserved for its owner.");
        DEFAULTS.put("reserved-items", "&cThose items are reserved for their owner.");
        DEFAULTS.put("owner-looted", "&e[Unlooted] &f{count} &eof your items were looted. They're reserved for you for &f{time}&e. Type &f/ul &eto claim them.");
        DEFAULTS.put("owner-join", "&e[Unlooted] You have &f{count} &ereserved items waiting. Type &f/ul &eto claim them.");
        DEFAULTS.put("search-none", "&eNo stored items match &f{query}&e.");
        DEFAULTS.put("in-combat", "&cYou can't open the storage while in combat.");
        // Menu
        DEFAULTS.put("title", "Unlooted Items");
        DEFAULTS.put("unknown-item", "Unknown item");
        DEFAULTS.put("available", "&eAvailable: &f{amount}");
        DEFAULTS.put("reserved-owners", "&cReserved for owners: &f{amount}");
        DEFAULTS.put("hint-choose", "&7Left: choose a stack");
        DEFAULTS.put("hint-take-stack", "&7Shift-left: take a stack");
        DEFAULTS.put("hint-take", "&7Left: take");
        DEFAULTS.put("hint-half", "&7Right: take half");
        DEFAULTS.put("hint-fill", "&7Shift-right: fill inventory");
        DEFAULTS.put("reserved-you", "&aReserved for you");
        DEFAULTS.put("reserved-for", "&cReserved for {owner}, {time} left");
        DEFAULTS.put("found-at", "&8Found in {world} at {x}, {y}, {z}");
        DEFAULTS.put("bar-page", "&7Page {page}/{pages}");
        DEFAULTS.put("bar-lore", "&8Navigation bar, not lootable");
        DEFAULTS.put("prev", "&c« Previous page");
        DEFAULTS.put("next", "&aNext page »");
        DEFAULTS.put("goto-lore", "&7Go to page {page}/{pages}");
        DEFAULTS.put("back", "&eBack to all items");
        DEFAULTS.put("back-lore", "&7Return to the item list");
        DEFAULTS.put("sort", "&bSort: {mode}");
        DEFAULTS.put("sort-lore", "&7Click to change");
        DEFAULTS.put("sort-recent", "Recent");
        DEFAULTS.put("sort-name", "Name");
        DEFAULTS.put("sort-amount", "Amount");
        DEFAULTS.put("search", "&bSearch");
        DEFAULTS.put("search-lore", "&7Type /ul search <text>");
        DEFAULTS.put("search-active", "&bSearch: {query}");
        DEFAULTS.put("search-active-lore", "&7Click to clear");
    }

    private final Map<String, String> values = new HashMap<String, String>();

    void load(ConfigurationSection section) {
        values.clear();
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) {
            String raw = section == null ? entry.getValue() : section.getString(entry.getKey(), entry.getValue());
            values.put(entry.getKey(), ChatColor.translateAlternateColorCodes('&', raw));
        }
    }

    /** The message for key with placeholders filled in, given as name, value, name, value... */
    String get(String key, Object... placeholders) {
        String text = values.containsKey(key) ? values.get(key) : DEFAULTS.get(key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            text = text.replace("{" + placeholders[i] + "}", String.valueOf(placeholders[i + 1]));
        }
        return text;
    }
}
