package me.unlooted;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

    private static final int PAGE_SLOTS = 54;
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

    private long delayMs;
    private int maxSlots;
    private boolean ignoreUnpickupable;
    private String broadcastMessage;
    private File dataFile;
    private boolean rebuilding;
    private boolean compactQueued;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        delayMs = Math.max(1, getConfig().getLong("loot-delay-seconds", 300)) * 1000L;
        long intervalTicks = Math.max(1, getConfig().getLong("loot-interval-seconds", 60)) * 20L;
        int maxPages = Math.max(0, getConfig().getInt("max-pages", 0));
        maxSlots = maxPages == 0 ? Integer.MAX_VALUE : maxPages * PAGE_SLOTS;
        ignoreUnpickupable = getConfig().getBoolean("ignore-unpickupable-items", true);
        broadcastMessage = getConfig().getString("broadcast-message",
                "&e[Unlooted] &f{count} &eitem(s) were auto-looted. Type &f/unlooted &eto claim them.");
        dataFile = new File(getDataFolder(), "data.yml");

        load();
        getServer().getPluginManager().registerEvents(this, this);

        getServer().getScheduler().runTaskTimer(this, () -> sweep(false), intervalTicks, intervalTicks);
        getServer().getScheduler().runTaskTimer(this, this::save, 20L * 300, 20L * 300);
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
        int count = 0;
        int stacks = 0;

        for (Item item : due) {
            ItemStack stack = item.getItemStack();
            if (stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0) {
                timers.remove(item.getUniqueId());
                item.remove();
                continue;
            }
            ItemStack left = store(contents, stack.clone());
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

    /** Adds a stack to the list, merging into similar stacks first. Returns what did not fit, or null. */
    private ItemStack store(List<ItemStack> contents, ItemStack stack) {
        int max = Math.max(1, stack.getMaxStackSize());
        for (ItemStack existing : contents) {
            if (stack.getAmount() <= 0) {
                break;
            }
            if (existing.isSimilar(stack) && existing.getAmount() < max) {
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

    // ------------------------------------------------------------------ dynamic shared inventory

    private List<ItemStack> drainPages() {
        List<ItemStack> contents = new ArrayList<ItemStack>();
        for (Inventory page : pages) {
            for (ItemStack stack : page.getContents()) {
                if (stack != null && stack.getType() != Material.AIR && stack.getAmount() > 0) {
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
            int size = ((to - from + 8) / 9) * 9;
            String title = total == 1 ? "Unlooted Items" : "Unlooted Items " + (p + 1) + "/" + total;
            Inventory page = Bukkit.createInventory(null, size, title);
            for (int i = from; i < to; i++) {
                page.setItem(i - from, contents.get(i));
            }
            pages.add(page);
        }

        for (Map.Entry<HumanEntity, Integer> entry : viewers.entrySet()) {
            HumanEntity viewer = entry.getKey();
            if (!pages.isEmpty() && viewer instanceof Player && ((Player) viewer).isOnline()) {
                viewer.openInventory(pages.get(Math.min(entry.getValue(), pages.size() - 1)));
            }
        }
    }

    /** Once nobody looks at the shared inventory any more, shrink it to what is left. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (rebuilding || !isEnabled() || !pages.contains(e.getInventory()) || compactQueued) {
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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<String>();
        if (args.length == 1) {
            if ("loot".startsWith(args[0].toLowerCase()) && sender.hasPermission("unlooted.loot")) {
                out.add("loot");
            }
            for (int i = 1; i <= pages.size(); i++) {
                if (String.valueOf(i).startsWith(args[0])) {
                    out.add(String.valueOf(i));
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("loot") && "all".startsWith(args[1].toLowerCase())) {
            out.add("all");
        }
        return out;
    }

    // ------------------------------------------------------------------ persistence

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("items", drainPages());
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Timer> entry : timers.entrySet()) {
            yml.set("timers." + entry.getKey(), entry.getValue().current(now));
        }
        try {
            getDataFolder().mkdirs();
            yml.save(dataFile);
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Could not save data.yml", ex);
        }
    }

    private void load() {
        List<ItemStack> contents = new ArrayList<ItemStack>();
        if (dataFile.exists()) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
            List<?> list = yml.getList("items");
            if (list != null) {
                for (Object o : list) {
                    if (o instanceof ItemStack && ((ItemStack) o).getType() != Material.AIR) {
                        contents.add((ItemStack) o);
                    }
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
