package me.unlooted;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Everything shown in the storage menu is a tagged display copy: item icons and navigation panes.
 * Anything tagged is removed wherever it turns up. Two tags are used:
 *
 * - A hidden data tag (persistent data container, 1.14+). Servers never show or rewrite it.
 * - An invisible run of color codes at the end of the last lore line, for 1.12. Modern servers store
 *   lore as rich text and drop trailing codes, which is why the data tag is needed there.
 */
final class Tags {

    private static NamespacedKey key;
    private static Method getContainer;
    private static Method containerSet;
    private static Method containerHas;
    private static Object byteType;

    static final String TAG = "" + ChatColor.RESET + ChatColor.BLACK + ChatColor.DARK_BLUE
            + ChatColor.DARK_GREEN + ChatColor.DARK_AQUA + ChatColor.RESET;

    private static final String OLD_BAR_LORE = ChatColor.DARK_GRAY + "Navigation bar, not lootable";
    private static final Pattern OLD_BUTTON_LORE = Pattern.compile(Pattern.quote(ChatColor.GRAY + "Go to page ") + "\\d+/\\d+");

    private Tags() {
    }

    /** Looks up the persistent data API by reflection, since the plugin compiles against 1.12. */
    static void init(Plugin plugin) {
        key = new NamespacedKey(plugin, "menu");
        try {
            Class<?> typeClass = Class.forName("org.bukkit.persistence.PersistentDataType");
            Class<?> containerClass = Class.forName("org.bukkit.persistence.PersistentDataContainer");
            byteType = typeClass.getField("BYTE").get(null);
            getContainer = ItemMeta.class.getMethod("getPersistentDataContainer");
            containerSet = containerClass.getMethod("set", NamespacedKey.class, typeClass, Object.class);
            containerHas = containerClass.getMethod("has", NamespacedKey.class, typeClass);
        } catch (Throwable t) {
            getContainer = null;
        }
    }

    private static void mark(ItemMeta meta) {
        if (getContainer != null) {
            try {
                containerSet.invoke(getContainer.invoke(meta), key, byteType, (byte) 1);
            } catch (Throwable ignored) {
                // The lore tag still applies.
            }
        }
    }

    static boolean isTagged(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0 || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        if (getContainer != null) {
            try {
                if ((Boolean) containerHas.invoke(getContainer.invoke(meta), key, byteType)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // Fall through to the lore tag.
            }
        }
        if (!meta.hasLore()) {
            return false;
        }
        List<String> lore = meta.getLore();
        for (String line : lore) {
            if (line.endsWith(TAG)) {
                return true;
            }
        }
        // Untagged panes handed out by 0.2.0, recognised by their exact colored lore.
        return lore.size() == 1 && (lore.get(0).equals(OLD_BAR_LORE) || OLD_BUTTON_LORE.matcher(lore.get(0)).matches());
    }

    /**
     * A tagged display copy of an item with extra lore lines below its own. Items whose meta can't be
     * edited (unknown modded types) are shown as a named chest instead, so every icon carries the tag.
     */
    static ItemStack icon(ItemStack item, int amount, List<String> lines, String fallbackName) {
        ItemStack icon = item.clone();
        icon.setAmount(Math.max(1, amount));
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) {
            icon = new ItemStack(Material.CHEST, Math.max(1, amount));
            meta = icon.getItemMeta();
            meta.setDisplayName(ChatColor.WHITE + fallbackName);
        }
        List<String> lore = meta.hasLore() ? new ArrayList<String>(meta.getLore()) : new ArrayList<String>();
        if (!lore.isEmpty()) {
            lore.add("");
        }
        lore.addAll(lines);
        lore.set(lore.size() - 1, lore.get(lore.size() - 1) + TAG);
        meta.setLore(lore);
        mark(meta);
        icon.setItemMeta(meta);
        return icon;
    }

    /** A tagged stained glass pane, using the modern material when it exists and 1.12 color data otherwise. */
    static ItemStack pane(String color, int legacyData, String name, String lore) {
        Material type = Material.getMaterial(color + "_STAINED_GLASS_PANE");
        short data = 0;
        if (type == null) {
            type = Material.getMaterial("STAINED_GLASS_PANE");
            data = (short) legacyData;
        }
        ItemStack pane = new ItemStack(type, 1, data);
        ItemMeta meta = pane.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(Collections.singletonList(lore + TAG));
        mark(meta);
        pane.setItemMeta(meta);
        return pane;
    }

    /** Removes tagged items from a player's inventory and cursor. */
    static void purge(HumanEntity player) {
        if (isTagged(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isTagged(contents[i])) {
                inventory.setItem(i, null);
            }
        }
    }

    /** A readable name: the custom name if there is one, else the type name in words. */
    static String displayName(ItemStack item, String unknown) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return ChatColor.stripColor(meta.getDisplayName());
        }
        String type = item.getType().name();
        if (type.equals("AIR")) {
            return unknown;
        }
        StringBuilder out = new StringBuilder();
        for (String word : type.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /** Removes tagged items from a container, skipping huge modded inventories. */
    static void purge(Inventory inventory, int maxSize) {
        if (inventory == null || inventory.getSize() > maxSize) {
            return;
        }
        for (int i = 0; i < inventory.getSize(); i++) {
            if (isTagged(inventory.getItem(i))) {
                inventory.setItem(i, null);
            }
        }
    }
}
