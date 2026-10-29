package me.unlooted;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Auto-loots dropped items that nobody touched for a while.
 *
 * Every dropped item has its own timer that only runs while its chunk is loaded.
 * A separate global sweep runs on a fixed interval and loots, in one go, every item
 * whose own timer has reached the loot delay. The shared inventory grows and shrinks
 * with the number of stored stacks, split into pages of at most 54 slots.
 */
public class Unlooted extends JavaPlugin implements Listener {

    /** Items per page: five rows. The sixth row is the navigation bar. */
    private static final int PAGE_SLOTS = 45;

    /** Marks an inventory as one page of the shared storage and remembers which one. */
    private static final class PageHolder implements InventoryHolder {
        final int index;
        Inventory inventory;

        PageHolder(int index) {
            this.index = index;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        /** Slots above the navigation bar. */
        int itemSlots() {
            return inventory.getSize() - 9;
        }
    }
    /** Vanilla uses 32767 as "can never be picked up" (shop displays, decorations...). */
    private static final int INFINITE_PICKUP_DELAY = 32767;
    /** Forget timers of items that stayed in unloaded chunks for this long. */
    private static final long FORGET_UNLOADED_MS = 7L * 24 * 60 * 60 * 1000;

    /** Per-item timer. Age only grows while the item's chunk is loaded. */
    private static final class Timer {
        long ageMs;
        long lastUpdate;
        boolean paused;

        Timer(long ageMs, long now, boolean paused) {
            this.ageMs = ageMs;
            this.lastUpdate = now;
            this.paused = paused;
        }

        long current(long now) {
            return paused ? ageMs : ageMs + (now - lastUpdate);
        }

        void advance(long now) {
            ageMs = current(now);
            lastUpdate = now;
        }
    }

    private final Map<UUID, Timer> timers = new HashMap<UUID, Timer>();
    private final List<Inventory> pages = new ArrayList<Inventory>();

    /** Config keys that can be changed with /unlooted config, and the kind of value each one takes. */
    private static final Map<String, String> SETTINGS = new LinkedHashMap<String, String>();

    static {
        SETTINGS.put("loot-delay-seconds", "seconds, at least 1");
        SETTINGS.put("loot-interval-seconds", "seconds, at least 1");
        SETTINGS.put("max-pages", "whole number, 0 for unlimited");
        SETTINGS.put("ignore-unpickupable-items", "true or false");
        SETTINGS.put("broadcast-message", "text, & color codes, {count} and {stacks}");
    }

    private long delayMs;
    private long intervalTicks;
    private BukkitTask sweepTask;
    private int maxSlots;
    private boolean ignoreUnpickupable;
    private String broadcastMessage;
    private File dataFile;
    private ItemCodec codec;
    /** Stored entries that could not be turned back into items (missing mod...). Kept so they are never lost. */
    private final List<Object> unreadable = new ArrayList<Object>();
    private boolean rebuilding;
    private boolean compactQueued;
    /** Who asked to clear the storage, and when. The clear only happens on a confirm shortly after. */
    private final Map<String, Long> pendingClears = new HashMap<String, Long>();
    private static final long CLEAR_CONFIRM_MS = 30_000L;
    /** Set when data.yml exists but cannot be read or moved aside: nothing may be saved over it. */
    private boolean storageBroken;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        codec = new ItemCodec(getLogger());

        load();
        if (storageBroken) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);

        applyConfig();
        getServer().getScheduler().runTaskTimer(this, this::save, 20L * 300, 20L * 300);
    }

    /** Reads config.yml values into fields and (re)starts the global loot timer if its interval changed. */
    private void applyConfig() {
        delayMs = Math.max(1, getConfig().getLong("loot-delay-seconds", 300)) * 1000L;
        int maxPages = Math.max(0, getConfig().getInt("max-pages", 0));
        maxSlots = maxPages == 0 ? Integer.MAX_VALUE : (int) Math.min(Integer.MAX_VALUE, (long) maxPages * PAGE_SLOTS);
        ignoreUnpickupable = getConfig().getBoolean("ignore-unpickupable-items", true);
        broadcastMessage = getConfig().getString("broadcast-message",
                "&e[Unlooted] &f{count} &eitem(s) were auto-looted. Type &f/unlooted &eto claim them.");

        long newInterval = Math.max(1, getConfig().getLong("loot-interval-seconds", 60)) * 20L;
        if (sweepTask == null || newInterval != intervalTicks) {
            if (sweepTask != null) {
                sweepTask.cancel();
            }
            intervalTicks = newInterval;
            sweepTask = getServer().getScheduler().runTaskTimer(this, () -> sweep(false), intervalTicks, intervalTicks);
        }
    }

    @Override
    public void onDisable() {
        rebuilding = true;
        for (Inventory page : pages) {
            for (HumanEntity viewer : new ArrayList<HumanEntity>(page.getViewers())) {
                viewer.closeInventory();
            }
        }
        long now = System.currentTimeMillis();
        for (Timer t : timers.values()) {
            t.advance(now);
            t.paused = true;
        }
        save();
    }

    // ------------------------------------------------------------------ per-item timers

    private boolean ignored(Item item) {
        return ignoreUnpickupable && item.getPickupDelay() >= INFINITE_PICKUP_DELAY;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        timers.put(e.getEntity().getUniqueId(), new Timer(0, System.currentTimeMillis(), false));
    }

    /** A pickup (even a partial one) counts as interaction: restart the item's timer. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent e) {
        restart(e.getItem());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent e) {
        restart(e.getItem());
    }

    private void restart(Item item) {
        timers.put(item.getUniqueId(), new Timer(0, System.currentTimeMillis(), false));
    }

    /** Like vanilla, a merged stack keeps the younger of the two ages. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMerge(ItemMergeEvent e) {
        long now = System.currentTimeMillis();
        Timer source = timers.remove(e.getEntity().getUniqueId());
        Timer target = timers.get(e.getTarget().getUniqueId());
        long age = Math.min(source == null ? 0 : source.current(now), target == null ? 0 : target.current(now));
        timers.put(e.getTarget().getUniqueId(), new Timer(age, now, false));
    }

    /** Items wait for the plugin instead of vanilla's own 5 minute despawn. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDespawn(ItemDespawnEvent e) {
        Item item = e.getEntity();
        if (ignored(item)) {
            return;
        }
        if (!timers.containsKey(item.getUniqueId())) {
            timers.put(item.getUniqueId(), new Timer(0, System.currentTimeMillis(), false));
        }
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent e) {
        long now = System.currentTimeMillis();
        for (Entity entity : e.getChunk().getEntities()) {
            if (entity instanceof Item) {
                Timer t = timers.get(entity.getUniqueId());
                if (t != null && !t.paused) {
                    t.advance(now);
                    t.paused = true;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) {
        long now = System.currentTimeMillis();
        for (Entity entity : e.getChunk().getEntities()) {
            if (entity instanceof Item) {
                resume(entity.getUniqueId(), now);
            }
        }
    }

    /** Starts or resumes the timer of an item seen in a loaded chunk. */
    private Timer resume(UUID id, long now) {
        Timer t = timers.get(id);
        if (t == null) {
            t = new Timer(0, now, false);
            timers.put(id, t);
        } else if (t.paused) {
            t.paused = false;
            t.lastUpdate = now;
        }
        return t;
    }

    // ------------------------------------------------------------------ global loot sweep

    /**
     * Global loot timer. Scans every loaded item entity (this also catches items that
     * mods spawn without firing ItemSpawnEvent) and loots all items whose own timer
     * reached the delay at once. With force, every item is looted regardless of its timer.
     *
     * @return the number of items looted
     */
    private int sweep(boolean force) {
        long now = System.currentTimeMillis();
        Set<UUID> seen = new HashSet<UUID>();
        List<Item> due = new ArrayList<Item>();

        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (!item.isValid() || ignored(item)) {
                    continue;
                }
                UUID id = item.getUniqueId();
                seen.add(id);
                boolean known = timers.containsKey(id);
                Timer t = resume(id, now);
                if (known) {
                    t.advance(now);
                }
                if (force || t.ageMs >= delayMs) {
                    due.add(item);
                }
            }
        }

        // Drop timers of items that are gone (picked up, burned, merged, killed...).
        for (Iterator<Map.Entry<UUID, Timer>> it = timers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Timer> entry = it.next();
            Timer t = entry.getValue();
            if (seen.contains(entry.getKey())) {
                continue;
            }
            if (!t.paused || now - t.lastUpdate > FORGET_UNLOADED_MS) {
                it.remove();
            }
        }

        return due.isEmpty() ? 0 : loot(due);
    }

    private int loot(List<Item> due) {
        List<ItemStack> contents = drainPages();
        Map<ItemStack, String> keys = new IdentityHashMap<ItemStack, String>();
        int count = 0;
        int stacks = 0;

        for (Item item : due) {
            ItemStack stack = item.getItemStack();
            if (isEmpty(stack)) {
                timers.remove(item.getUniqueId());
                item.remove();
                continue;
            }
            ItemStack left = store(contents, stack.clone(), keys);
            int moved = stack.getAmount() - (left == null ? 0 : left.getAmount());
            if (moved > 0) {
                count += moved;
                stacks++;
            }
            if (left == null) {
                timers.remove(item.getUniqueId());
                item.remove();
            } else {
                // Shared inventory reached max-pages: the rest stays on the ground for the next sweep.
                item.setItemStack(left);
            }
        }

        rebuildPages(contents);

        if (count > 0) {
            save();
            Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', broadcastMessage
                    .replace("{count}", String.valueOf(count))
                    .replace("{stacks}", String.valueOf(stacks))));
        }
        return count;
    }

    /**
     * Emptiness is judged by amount only. getType() is unreliable here: legacy-mode plugins on
     * modern servers, and some modded servers, report items without a known Material as AIR.
     */
    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getAmount() <= 0;
    }

    /** Adds a stack to the list, merging into similar stacks first. Returns what did not fit, or null. */
    private ItemStack store(List<ItemStack> contents, ItemStack stack, Map<ItemStack, String> keys) {
        int max = Math.max(1, stack.getMaxStackSize());
        for (ItemStack existing : contents) {
            if (stack.getAmount() <= 0) {
                break;
            }
            if (existing.getAmount() < max && sameItem(existing, stack, keys)) {
                int move = Math.min(max - existing.getAmount(), stack.getAmount());
                existing.setAmount(existing.getAmount() + move);
                stack.setAmount(stack.getAmount() - move);
            }
        }
        while (stack.getAmount() > 0 && contents.size() < maxSlots) {
            ItemStack part = stack.clone();
            part.setAmount(Math.min(max, stack.getAmount()));
            contents.add(part);
            stack.setAmount(stack.getAmount() - part.getAmount());
        }
        return stack.getAmount() > 0 ? stack : null;
    }

    /**
     * Bukkit's isSimilar ignores data it does not know about, such as Forge capabilities.
     * Stacks are only merged when their full stored data matches too.
     */
    private boolean sameItem(ItemStack a, ItemStack b, Map<ItemStack, String> keys) {
        if (!a.isSimilar(b)) {
            return false;
        }
        if (!codec.isLossless()) {
            return true;
        }
        String ka = key(a, keys);
        String kb = key(b, keys);
        return ka != null && ka.equals(kb);
    }

    private String key(ItemStack stack, Map<ItemStack, String> keys) {
        if (!keys.containsKey(stack)) {
            keys.put(stack, codec.key(stack));
        }
        return keys.get(stack);
    }

    // ------------------------------------------------------------------ dynamic shared inventory

    private List<ItemStack> drainPages() {
        List<ItemStack> contents = new ArrayList<ItemStack>();
        for (Inventory page : pages) {
            int itemSlots = ((PageHolder) page.getHolder()).itemSlots();
            for (int i = 0; i < itemSlots; i++) {
                ItemStack stack = page.getItem(i);
                if (!isEmpty(stack)) {
                    contents.add(stack);
                }
            }
        }
        return contents;
    }

    /** Rebuilds the pages so each one is just big enough for its stacks, reopening them for viewers. */
    private void rebuildPages(List<ItemStack> contents) {
        Map<HumanEntity, Integer> viewers = new HashMap<HumanEntity, Integer>();
        rebuilding = true;
        try {
            for (int i = 0; i < pages.size(); i++) {
                for (HumanEntity viewer : new ArrayList<HumanEntity>(pages.get(i).getViewers())) {
                    viewers.put(viewer, i);
                    viewer.closeInventory();
                }
            }
        } finally {
            rebuilding = false;
        }

        pages.clear();
        int total = (contents.size() + PAGE_SLOTS - 1) / PAGE_SLOTS;
        for (int p = 0; p < total; p++) {
            int from = p * PAGE_SLOTS;
            int to = Math.min(contents.size(), from + PAGE_SLOTS);
            int itemRows = (to - from + 8) / 9;
            String title = total == 1 ? "Unlooted Items" : "Unlooted Items " + (p + 1) + "/" + total;
            PageHolder holder = new PageHolder(p);
            Inventory page = Bukkit.createInventory(holder, (itemRows + 1) * 9, title);
            holder.inventory = page;
            for (int i = from; i < to; i++) {
                page.setItem(i - from, contents.get(i));
            }
            fillNavigationBar(page, p, total);
            pages.add(page);
        }

        for (Map.Entry<HumanEntity, Integer> entry : viewers.entrySet()) {
            HumanEntity viewer = entry.getKey();
            if (!pages.isEmpty() && viewer instanceof Player && ((Player) viewer).isOnline()) {
                viewer.openInventory(pages.get(Math.min(entry.getValue(), pages.size() - 1)));
            }
        }
    }

    // ------------------------------------------------------------------ navigation bar

    private void fillNavigationBar(Inventory page, int index, int total) {
        String pageText = "Page " + (index + 1) + "/" + total;
        ItemStack filler = glassPane("BLACK", 15, ChatColor.GRAY + pageText,
                ChatColor.DARK_GRAY + "Navigation bar, not lootable");
        int bar = page.getSize() - 9;
        for (int i = bar; i < page.getSize(); i++) {
            page.setItem(i, filler);
        }
        if (index > 0) {
            page.setItem(bar, glassPane("RED", 14, ChatColor.RED + "\u00AB Previous page",
                    ChatColor.GRAY + "Go to page " + index + "/" + total));
        }
        if (index < total - 1) {
            page.setItem(bar + 8, glassPane("GREEN", 13, ChatColor.GREEN + "Next page \u00BB",
                    ChatColor.GRAY + "Go to page " + (index + 2) + "/" + total));
        }
    }

    /** A stained glass pane, using the modern material name when it exists and 1.12 color data otherwise. */
    private static ItemStack glassPane(String color, int legacyData, String name, String lore) {
        Material type = Material.getMaterial(color + "_STAINED_GLASS_PANE");
        short data = 0;
        if (type == null) {
            type = Material.getMaterial("STAINED_GLASS_PANE");
            data = (short) legacyData;
        }
        ItemStack pane = new ItemStack(type, 1, data);
        ItemMeta meta = pane.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(Collections.singletonList(lore));
        pane.setItemMeta(meta);
        return pane;
    }

    private static PageHolder pageOf(Inventory inventory) {
        InventoryHolder holder = inventory == null ? null : inventory.getHolder();
        return holder instanceof PageHolder ? (PageHolder) holder : null;
    }

    /** The navigation bar can never be taken or changed. Its red and green panes switch pages. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        PageHolder page = pageOf(top);
        if (page == null) {
            return;
        }
        int raw = e.getRawSlot();
        if (raw < page.itemSlots() || raw >= top.getSize()) {
            return;
        }
        e.setCancelled(true);
        int target = raw == page.itemSlots() ? page.index - 1 : raw == top.getSize() - 1 ? page.index + 1 : -1;
        if (target >= 0 && target < pages.size() && e.getWhoClicked() instanceof Player) {
            final Player player = (Player) e.getWhoClicked();
            final int index = target;
            // Opening another inventory inside a click event is unsafe, so do it on the next tick.
            getServer().getScheduler().runTask(this, () -> {
                if (player.isOnline() && index < pages.size()) {
                    player.openInventory(pages.get(index));
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        PageHolder page = pageOf(top);
        if (page == null) {
            return;
        }
        for (int raw : e.getRawSlots()) {
            if (raw >= page.itemSlots() && raw < top.getSize()) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /** Once nobody looks at the shared inventory any more, shrink it to what is left. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (rebuilding || !isEnabled() || pageOf(e.getInventory()) == null || compactQueued) {
            return;
        }
        compactQueued = true;
        getServer().getScheduler().runTask(this, () -> {
            compactQueued = false;
            for (Inventory page : pages) {
                if (!page.getViewers().isEmpty()) {
                    return;
                }
            }
            rebuildPages(drainPages());
            save();
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("loot")) {
            return forceLoot(sender, label, args);
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("clear")) {
            return clear(sender, label, args);
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            return reload(sender);
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("config")) {
            return configCommand(sender, label, args);
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can open the shared inventory.");
            return true;
        }
        Player p = (Player) sender;
        if (!p.hasPermission("unlooted.use")) {
            p.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }
        if (pages.isEmpty()) {
            p.sendMessage(ChatColor.YELLOW + "There are no auto-looted items right now.");
            return true;
        }
        int page = 1;
        if (args.length > 0) {
            try {
                page = Integer.parseInt(args[0]);
            } catch (NumberFormatException ex) {
                p.sendMessage(ChatColor.RED + "Usage: /" + label + " [page]");
                return true;
            }
        }
        if (page < 1 || page > pages.size()) {
            p.sendMessage(ChatColor.RED + "Page must be between 1 and " + pages.size() + ".");
            return true;
        }
        p.openInventory(pages.get(page - 1));
        return true;
    }

    /** /unlooted loot [all]: runs the global loot sweep now. "all" also ignores the per-item timers. */
    private boolean forceLoot(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("unlooted.loot")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }
        boolean all = args.length > 1 && args[1].equalsIgnoreCase("all");
        if (args.length > 2 || (args.length == 2 && !all)) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " loot [all]");
            return true;
        }
        int count = sweep(all);
        if (count == 0) {
            sender.sendMessage(ChatColor.YELLOW + (all
                    ? "There were no items on the ground to loot."
                    : "No item has been on the ground long enough. Use /" + label + " loot all to loot everything."));
        }
        return true;
    }

    /** /unlooted clear [confirm]: permanently deletes everything in the shared storage. */
    private boolean clear(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("unlooted.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }
        long now = System.currentTimeMillis();
        List<ItemStack> contents = drainPages();
        int items = 0;
        for (ItemStack stack : contents) {
            items += stack.getAmount();
        }
        int stacks = contents.size() + unreadable.size();
        String summary = items + " item(s) in " + stacks + " stack(s)"
                + (unreadable.isEmpty() ? "" : ", including " + unreadable.size() + " stack(s) from missing mods");

        boolean confirming = args.length > 1 && args[1].equalsIgnoreCase("confirm");
        Long asked = pendingClears.get(sender.getName());
        if (!confirming || asked == null || now - asked > CLEAR_CONFIRM_MS) {
            if (stacks == 0) {
                sender.sendMessage(ChatColor.YELLOW + "The storage is already empty.");
                pendingClears.remove(sender.getName());
                return true;
            }
            pendingClears.put(sender.getName(), now);
            sender.sendMessage(ChatColor.RED + "This permanently deletes " + summary + ".");
            sender.sendMessage(ChatColor.RED + "Type /" + label + " clear confirm within 30 seconds to do it.");
            return true;
        }

        pendingClears.remove(sender.getName());
        rebuildPages(new ArrayList<ItemStack>());
        unreadable.clear();
        save();
        getLogger().info(sender.getName() + " cleared the storage, deleting " + summary + ".");
        sender.sendMessage(ChatColor.GREEN + "Storage cleared. Deleted " + summary + ".");
        return true;
    }

    /** /unlooted reload: re-reads config.yml from disk. */
    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("unlooted.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }
        reloadConfig();
        applyConfig();
        sender.sendMessage(ChatColor.GREEN + "Unlooted config reloaded.");
        return true;
    }

    /** /unlooted config [key] [value]: lists, shows or changes a setting and saves it to config.yml. */
    private boolean configCommand(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("unlooted.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }
        if (args.length == 1) {
            sender.sendMessage(ChatColor.YELLOW + "Unlooted settings. Change one with /" + label + " config <key> <value>");
            for (String key : SETTINGS.keySet()) {
                sender.sendMessage(ChatColor.GRAY + " " + key + ": " + ChatColor.WHITE + getConfig().get(key));
            }
            return true;
        }
        String key = args[1].toLowerCase();
        if (!SETTINGS.containsKey(key)) {
            sender.sendMessage(ChatColor.RED + "Unknown setting " + args[1] + ". Settings: " + String.join(", ", SETTINGS.keySet()));
            return true;
        }
        if (args.length == 2) {
            sender.sendMessage(ChatColor.GRAY + key + ": " + ChatColor.WHITE + getConfig().get(key)
                    + ChatColor.GRAY + " (" + SETTINGS.get(key) + ")");
            return true;
        }

        String raw = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        Object value;
        String yaml;
        try {
            switch (key) {
                case "loot-delay-seconds":
                case "loot-interval-seconds":
                case "max-pages":
                    long number = Long.parseLong(raw);
                    long min = key.equals("max-pages") ? 0 : 1;
                    if (number < min || number > Integer.MAX_VALUE) {
                        throw new NumberFormatException();
                    }
                    value = key.equals("max-pages") ? (Object) (int) number : (Object) number;
                    yaml = String.valueOf(number);
                    break;
                case "ignore-unpickupable-items":
                    if (!raw.equalsIgnoreCase("true") && !raw.equalsIgnoreCase("false")) {
                        throw new NumberFormatException();
                    }
                    value = Boolean.parseBoolean(raw);
                    yaml = String.valueOf(value);
                    break;
                default:
                    value = raw;
                    yaml = "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            }
        } catch (NumberFormatException ex) {
            sender.sendMessage(ChatColor.RED + "Invalid value for " + key + ": expected " + SETTINGS.get(key) + ".");
            return true;
        }

        try {
            writeSetting(key, yaml);
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Could not write config.yml", ex);
            sender.sendMessage(ChatColor.RED + "Could not write config.yml, see the server log.");
            return true;
        }
        getConfig().set(key, value);
        applyConfig();
        sender.sendMessage(ChatColor.GREEN + "Set " + key + " to " + ChatColor.WHITE + raw + ChatColor.GREEN + " and saved config.yml.");
        return true;
    }

    /**
     * Replaces one top-level key in config.yml, keeping every comment and other line as it is.
     * Bukkit's saveConfig() would strip all comments from the file.
     */
    private void writeSetting(String key, String yaml) throws IOException {
        File file = new File(getDataFolder(), "config.yml");
        List<String> lines = file.exists()
                ? new ArrayList<String>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8))
                : new ArrayList<String>();
        Pattern line = Pattern.compile("^" + Pattern.quote(key) + "\\s*:.*$");
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            if (line.matcher(lines.get(i)).matches()) {
                lines.set(i, key + ": " + yaml);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            lines.add(key + ": " + yaml);
        }
        getDataFolder().mkdirs();
        File tmp = new File(getDataFolder(), "config.yml.tmp");
        Files.write(tmp.toPath(), lines, StandardCharsets.UTF_8);
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<String>();
        if (args.length == 1) {
            if ("loot".startsWith(args[0].toLowerCase()) && sender.hasPermission("unlooted.loot")) {
                out.add("loot");
            }
            for (String sub : new String[] {"clear", "reload", "config"}) {
                if (sub.startsWith(args[0].toLowerCase()) && sender.hasPermission("unlooted.admin")) {
                    out.add(sub);
                }
            }
            for (int i = 1; i <= pages.size(); i++) {
                if (String.valueOf(i).startsWith(args[0])) {
                    out.add(String.valueOf(i));
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("loot") && "all".startsWith(args[1].toLowerCase())) {
            out.add("all");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("clear") && "confirm".startsWith(args[1].toLowerCase())
                && sender.hasPermission("unlooted.admin")) {
            out.add("confirm");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("config") && sender.hasPermission("unlooted.admin")) {
            for (String key : SETTINGS.keySet()) {
                if (key.startsWith(args[1].toLowerCase())) {
                    out.add(key);
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("config")
                && args[1].equalsIgnoreCase("ignore-unpickupable-items") && sender.hasPermission("unlooted.admin")) {
            for (String b : new String[] {"true", "false"}) {
                if (b.startsWith(args[2].toLowerCase())) {
                    out.add(b);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ persistence

    private void save() {
        if (storageBroken) {
            return;
        }
        YamlConfiguration yml = new YamlConfiguration();
        List<Object> items = new ArrayList<Object>();
        for (ItemStack stack : drainPages()) {
            items.add(codec.encode(stack));
        }
        items.addAll(unreadable);
        yml.set("items", items);
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Timer> entry : timers.entrySet()) {
            yml.set("timers." + entry.getKey(), entry.getValue().current(now));
        }
        // Write to a temporary file first, so a crash mid-save never leaves a half-written data.yml.
        File tmp = new File(getDataFolder(), "data.yml.tmp");
        try {
            getDataFolder().mkdirs();
            yml.save(tmp);
            try {
                Files.move(tmp.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Could not save data.yml", ex);
        }
    }

    private void load() {
        List<ItemStack> contents = new ArrayList<ItemStack>();
        if (dataFile.exists()) {
            YamlConfiguration yml = new YamlConfiguration();
            try {
                yml.load(dataFile);
            } catch (IOException | InvalidConfigurationException ex) {
                // Never overwrite stored items we failed to read: move the file aside and start fresh.
                File broken = new File(getDataFolder(), "data.yml.broken-" + System.currentTimeMillis());
                if (dataFile.renameTo(broken)) {
                    getLogger().log(Level.SEVERE, "data.yml could not be read and was moved to " + broken.getName()
                            + ". Its items are not lost, fix or restore the file and restart.", ex);
                } else {
                    getLogger().log(Level.SEVERE, "data.yml could not be read. Disabling Unlooted so it is not overwritten.", ex);
                    storageBroken = true;
                    return;
                }
                yml = new YamlConfiguration();
            }
            List<?> list = yml.getList("items");
            if (list != null) {
                for (Object o : list) {
                    if (o == null) {
                        continue;
                    }
                    ItemStack stack;
                    try {
                        stack = codec.decode(o);
                    } catch (Exception ex) {
                        stack = null;
                    }
                    if (stack != null && !isEmpty(stack)) {
                        contents.add(stack);
                    } else {
                        unreadable.add(o);
                    }
                }
                if (!unreadable.isEmpty()) {
                    getLogger().warning(unreadable.size() + " stored item(s) could not be loaded, probably because"
                            + " the mod that adds them is missing. They stay in data.yml and load again once it is back.");
                }
            }
            ConfigurationSection section = yml.getConfigurationSection("timers");
            if (section != null) {
                long now = System.currentTimeMillis();
                for (String key : section.getKeys(false)) {
                    try {
                        // Paused until the item is seen in a loaded chunk again.
                        timers.put(UUID.fromString(key), new Timer(section.getLong(key), now, true));
                    } catch (IllegalArgumentException ignored) {
                        // Corrupt key: skip it.
                    }
                }
            }
        }
        rebuildPages(contents);
    }
}
