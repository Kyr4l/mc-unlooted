package me.unlooted;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Auto-loots dropped items that nobody touched for a while.
 *
 * Every dropped item has its own timer that only runs while its chunk is loaded. A separate global
 * sweep runs on a fixed interval and loots, in one go, every item whose own timer reached the delay.
 * Looted items go into a shared, unlimited storage that players browse through a take-only menu.
 */
public class Unlooted extends JavaPlugin implements Listener {

    /** Vanilla uses 32767 as "can never be picked up" (shop displays, decorations...). */
    private static final int INFINITE_PICKUP_DELAY = 32767;
    /** Forget timers of items that stayed in unloaded chunks for this long. */
    private static final long FORGET_UNLOADED_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long CLEAR_CONFIRM_MS = 30_000L;

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

    /** Who dropped an item entity. */
    private static final class Owner {
        final UUID id;
        final String name;

        Owner(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Owner && ((Owner) o).id.equals(id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    /** A death whose drops are about to spawn this tick, so they can be matched to their owner. */
    private static final class PendingDeath {
        final Owner owner;
        final Location location;
        final List<ItemStack> drops;

        PendingDeath(Owner owner, Location location, List<ItemStack> drops) {
            this.owner = owner;
            this.location = location;
            this.drops = drops;
        }
    }

    /** Config keys that can be changed with /unlooted config, and the kind of value each one takes. */
    private static final Map<String, String> SETTINGS = new LinkedHashMap<String, String>();

    static {
        SETTINGS.put("loot-delay-seconds", "seconds, at least 1");
        SETTINGS.put("loot-interval-seconds", "seconds, at least 1");
        SETTINGS.put("owner-priority-seconds", "seconds, 0 to turn off");
        SETTINGS.put("expire-after-days", "days, 0 to keep items forever");
        SETTINGS.put("combat-block-seconds", "seconds, 0 to turn off");
        SETTINGS.put("max-pages", "whole number, 0 for unlimited");
        SETTINGS.put("excluded-worlds", "comma-separated world names, or none");
        SETTINGS.put("excluded-items", "comma-separated item names, * as wildcard, or none");
        SETTINGS.put("ignore-unpickupable-items", "true or false");
        SETTINGS.put("broadcast-message", "text, & color codes, {count} and {stacks}");
    }

    private final Map<UUID, Timer> timers = new HashMap<UUID, Timer>();
    /**
     * Who dropped each item entity. Kept apart from the timers, which can be forgotten and restarted:
     * an owner is only forgotten once the item is known to be gone, or after it went unseen for a week.
     */
    private final Map<UUID, Owner> owners = new HashMap<UUID, Owner>();
    private final Map<UUID, Long> ownerSeen = new HashMap<UUID, Long>();
    /** Deaths of this tick whose drops are about to spawn. Several players can die in the same tick. */
    private final List<PendingDeath> pendingDeaths = new ArrayList<PendingDeath>();
    /** Items that may have been destroyed this tick, checked together on the next one. */
    private final Set<Item> goneChecks = new HashSet<Item>();

    private ItemCodec codec;
    private Storage storage;
    private Menu menu;
    private final Messages messages = new Messages();
    private TakeLog takeLog;
    /** When each player's PvP combat tag runs out. */
    private final Map<UUID, Long> combatUntil = new HashMap<UUID, Long>();

    private long delayMs;
    private long priorityMs;
    private long expireMs;
    private long combatMs;
    private long intervalTicks;
    private BukkitTask sweepTask;
    private int maxGroups;
    private boolean ignoreUnpickupable;
    private Set<String> excludedWorlds = new HashSet<String>();
    private List<Pattern> excludedItems = new ArrayList<Pattern>();
    private String broadcastMessage;

    private File dataFile;
    /** Set when data.yml exists but cannot be read or moved aside: nothing may be saved over it. */
    private boolean storageBroken;
    private boolean dirty;
    private int saveTicks;
    private long saveSeq;
    private long writtenSeq;
    private final Object writeLock = new Object();
    private final Map<String, Long> pendingClears = new HashMap<String, Long>();

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            if (ConfigUpdater.update(new File(getDataFolder(), "config.yml"), getResource("config.yml"), getLogger())) {
                reloadConfig();
            }
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Could not update config.yml, using it as it is.", ex);
        }
        dataFile = new File(getDataFolder(), "data.yml");
        Tags.init(this);
        codec = new ItemCodec(getLogger());
        takeLog = new TakeLog(this);
        storage = new Storage(codec);
        menu = new Menu(this, storage);

        load();
        if (storageBroken) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(menu, this);
        registerEntityEvents();

        applyConfig();
        checkTagging();
        // Saves only when something changed, at most every 30 seconds, plus every 5 minutes for the timers.
        getServer().getScheduler().runTaskTimer(this, () -> {
            saveTicks++;
            expireOld();
            if (dirty || saveTicks % 10 == 0) {
                save(false);
            }
        }, 20L * 30, 20L * 30);
        getServer().getScheduler().runTaskTimer(this, menu::guard, 1L, 1L);
    }

    /**
     * Menu items must stay recognisable after the server stores them, or the guard could mistake them
     * for real items. Checked once at startup by putting a pane into an inventory and reading it back.
     */
    private void checkTagging() {
        Inventory probe = Bukkit.createInventory(null, 9);
        probe.setItem(0, Tags.pane("BLACK", 15, "probe", "probe"));
        if (!Tags.isTagged(probe.getItem(0))) {
            getLogger().severe("Menu items can't be tagged on this server. Copies of menu icons are still blocked"
                    + " while the storage is open, but please report this with your server version.");
        }
    }

    /** Reads config.yml values into fields and (re)starts the global loot timer if its interval changed. */
    private void applyConfig() {
        messages.load(getConfig().getConfigurationSection("messages"));
        delayMs = Math.max(1, getConfig().getLong("loot-delay-seconds", 300)) * 1000L;
        priorityMs = Math.max(0, getConfig().getLong("owner-priority-seconds", 600)) * 1000L;
        expireMs = Math.max(0, getConfig().getLong("expire-after-days", 0)) * 24L * 60 * 60 * 1000;
        combatMs = Math.max(0, getConfig().getLong("combat-block-seconds", 10)) * 1000L;
        int maxPages = Math.max(0, getConfig().getInt("max-pages", 0));
        maxGroups = maxPages == 0 ? Integer.MAX_VALUE : (int) Math.min(Integer.MAX_VALUE, (long) maxPages * Menu.PER_PAGE);
        ignoreUnpickupable = getConfig().getBoolean("ignore-unpickupable-items", true);
        broadcastMessage = getConfig().getString("broadcast-message",
                "&e[Unlooted] &f{count} &eitem(s) were auto-looted. Type &f/unlooted &eto claim them.");

        Set<String> worlds = new HashSet<String>();
        for (String world : getConfig().getStringList("excluded-worlds")) {
            worlds.add(world.trim().toLowerCase(Locale.ROOT));
        }
        excludedWorlds = worlds;
        List<Pattern> items = new ArrayList<Pattern>();
        for (String item : getConfig().getStringList("excluded-items")) {
            items.add(glob(item));
        }
        excludedItems = items;

        long newInterval = Math.max(1, getConfig().getLong("loot-interval-seconds", 60)) * 20L;
        if (sweepTask == null || newInterval != intervalTicks) {
            if (sweepTask != null) {
                sweepTask.cancel();
            }
            intervalTicks = newInterval;
            sweepTask = getServer().getScheduler().runTaskTimer(this, () -> sweep(false), intervalTicks, intervalTicks);
        }
    }

    /** Item names match Bukkit material names, ignoring case and any "namespace:" prefix. * matches anything. */
    private static Pattern glob(String name) {
        String n = name.trim();
        if (n.indexOf(':') >= 0) {
            n = n.substring(n.indexOf(':') + 1);
        }
        StringBuilder regex = new StringBuilder();
        for (String part : n.split("\\*", -1)) {
            if (regex.length() > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    @Override
    public void onDisable() {
        if (menu != null) {
            menu.closeAll();
        }
        long now = System.currentTimeMillis();
        for (Timer t : timers.values()) {
            t.advance(now);
            t.paused = true;
        }
        if (storage != null) {
            save(true);
        }
        if (takeLog != null) {
            takeLog.flush();
        }
    }

    void markDirty() {
        dirty = true;
    }

    Messages messages() {
        return messages;
    }

    void log(String entry) {
        takeLog.add(entry);
    }

    /** Deletes stored items older than expire-after-days, when it is set. */
    private void expireOld() {
        if (expireMs <= 0) {
            return;
        }
        long removed = storage.expire(System.currentTimeMillis() - expireMs);
        if (removed > 0) {
            markDirty();
            menu.refreshAll();
            getLogger().info("Deleted " + removed + " stored item(s) older than the expire-after-days setting.");
            log("Expiry deleted " + removed + " item(s)");
        }
    }

    // ------------------------------------------------------------------ per-item timers and owners

    @SuppressWarnings("deprecation")
    private boolean ignored(Item item) {
        if (ignoreUnpickupable && item.getPickupDelay() >= INFINITE_PICKUP_DELAY) {
            return true;
        }
        if (!excludedWorlds.isEmpty() && excludedWorlds.contains(item.getWorld().getName().toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (!excludedItems.isEmpty()) {
            String type = item.getItemStack().getType().name();
            for (Pattern pattern : excludedItems) {
                if (pattern.matcher(type).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        Item item = e.getEntity();
        ItemStack stack = item.getItemStack();
        if (Tags.isTagged(stack)) {
            item.remove();
            return;
        }
        timers.put(item.getUniqueId(), new Timer(0, System.currentTimeMillis(), false));
        for (PendingDeath death : pendingDeaths) {
            if (!death.location.getWorld().equals(item.getWorld()) || death.location.distanceSquared(item.getLocation()) >= 9) {
                continue;
            }
            for (Iterator<ItemStack> it = death.drops.iterator(); it.hasNext(); ) {
                ItemStack drop = it.next();
                if (drop.getAmount() == stack.getAmount() && drop.isSimilar(stack)) {
                    setOwner(item.getUniqueId(), death.owner);
                    it.remove();
                    return;
                }
            }
        }
    }

    private void setOwner(UUID item, Owner owner) {
        owners.put(item, owner);
        ownerSeen.put(item, System.currentTimeMillis());
    }

    private void forgetOwner(UUID item) {
        owners.remove(item);
        ownerSeen.remove(item);
    }

    /**
     * Forgets an item once it is really gone, checked a tick after something may have destroyed it.
     * Hoppers retry pickups constantly, so all checks of a tick share one task.
     */
    private void forgetIfGone(Item item) {
        if (!isEnabled()) {
            return;
        }
        if (goneChecks.isEmpty()) {
            getServer().getScheduler().runTask(this, () -> {
                for (Item checked : goneChecks) {
                    if (checked.isDead()) {
                        timers.remove(checked.getUniqueId());
                        forgetOwner(checked.getUniqueId());
                    }
                }
                goneChecks.clear();
            });
        }
        goneChecks.add(item);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDropTagged(PlayerDropItemEvent e) {
        if (Tags.isTagged(e.getItemDrop().getItemStack())) {
            e.getItemDrop().remove();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (e.getItemDrop().isDead()) {
            return;
        }
        setOwner(e.getItemDrop().getUniqueId(), new Owner(e.getPlayer().getUniqueId(), e.getPlayer().getName()));
    }

    /** Death drops spawn right after this event, in the same tick: remember them to tag their owner. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        if (e.getDrops().isEmpty() || (e.getKeepInventory())) {
            return;
        }
        List<ItemStack> drops = new ArrayList<ItemStack>();
        for (ItemStack drop : e.getDrops()) {
            if (drop != null && drop.getAmount() > 0) {
                drops.add(drop.clone());
            }
        }
        Player player = e.getEntity();
        if (pendingDeaths.isEmpty()) {
            getServer().getScheduler().runTask(this, pendingDeaths::clear);
        }
        pendingDeaths.add(new PendingDeath(new Owner(player.getUniqueId(), player.getName()), player.getLocation(), drops));
    }

    /** A pickup (even a partial one) counts as interaction: restart the item's timer. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent e) {
        restart(e.getItem());
        forgetIfGone(e.getItem());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent e) {
        restart(e.getItem());
        forgetIfGone(e.getItem());
    }

    /** Items burn in fire and lava, and break in explosions or on cactus. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Item) {
            forgetIfGone((Item) e.getEntity());
        }
    }

    private void restart(Item item) {
        timers.put(item.getUniqueId(), new Timer(0, System.currentTimeMillis(), false));
    }

    /** Items of different owners never merge, so a reservation always covers exactly its owner's items. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMergeOwners(ItemMergeEvent e) {
        if (!Objects.equals(owners.get(e.getEntity().getUniqueId()), owners.get(e.getTarget().getUniqueId()))) {
            e.setCancelled(true);
        }
    }

    /** Like vanilla, a merged stack keeps the younger of the two ages. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMerge(ItemMergeEvent e) {
        long now = System.currentTimeMillis();
        Timer source = timers.remove(e.getEntity().getUniqueId());
        forgetOwner(e.getEntity().getUniqueId());
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
                pause(entity.getUniqueId(), now);
            }
        }
    }

    /** Stops the timer of an item whose chunk or entity section is unloading. */
    private void pause(UUID id, long now) {
        Timer t = timers.get(id);
        if (t != null && !t.paused) {
            t.advance(now);
            t.paused = true;
        }
    }

    /**
     * Since 1.17 entities load and unload apart from their chunk, through their own events. Those don't
     * exist in the 1.12 API this plugin compiles against, so they are found and registered by reflection.
     */
    private void registerEntityEvents() {
        registerEntitiesEvent("org.bukkit.event.world.EntitiesUnloadEvent", true);
        registerEntitiesEvent("org.bukkit.event.world.EntitiesLoadEvent", false);
    }

    @SuppressWarnings("unchecked")
    private void registerEntitiesEvent(String className, final boolean unload) {
        final Class<? extends Event> type;
        final Method getEntities;
        try {
            type = (Class<? extends Event>) Class.forName(className);
            getEntities = type.getMethod("getEntities");
        } catch (Throwable t) {
            return;
        }
        getServer().getPluginManager().registerEvent(type, this, EventPriority.MONITOR, (listener, event) -> {
            if (!type.isInstance(event)) {
                return;
            }
            List<?> entities;
            try {
                entities = (List<?>) getEntities.invoke(event);
            } catch (Exception ex) {
                return;
            }
            long now = System.currentTimeMillis();
            for (Object entity : entities) {
                if (entity instanceof Item) {
                    UUID id = ((Item) entity).getUniqueId();
                    if (unload) {
                        pause(id, now);
                    } else {
                        resume(id, now);
                    }
                }
            }
        }, this, true);
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
     * Global loot timer. Scans every loaded item entity (this also catches items that mods spawn
     * without firing ItemSpawnEvent) and loots all items whose own timer reached the delay at once.
     * With force, every item is looted regardless of its timer.
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
            if (!seen.contains(entry.getKey()) && (!t.paused || now - t.lastUpdate > FORGET_UNLOADED_MS)) {
                it.remove();
            }
        }
        for (UUID id : seen) {
            if (owners.containsKey(id)) {
                ownerSeen.put(id, now);
            }
        }
        for (Iterator<Map.Entry<UUID, Owner>> it = owners.entrySet().iterator(); it.hasNext(); ) {
            UUID id = it.next().getKey();
            Long last = ownerSeen.get(id);
            if (!timers.containsKey(id) && (last == null || now - last > FORGET_UNLOADED_MS)) {
                it.remove();
                ownerSeen.remove(id);
            }
        }

        return due.isEmpty() ? 0 : loot(due, now);
    }

    private int loot(List<Item> due, long now) {
        int count = 0;
        int stacks = 0;
        Map<UUID, Long> reservedPerOwner = new HashMap<UUID, Long>();
        for (Item item : due) {
            ItemStack stack = item.getItemStack();
            UUID id = item.getUniqueId();
            if (stack == null || stack.getAmount() <= 0 || Tags.isTagged(stack)) {
                timers.remove(id);
                forgetOwner(id);
                item.remove();
                continue;
            }
            Owner owner = priorityMs > 0 ? owners.get(id) : null;
            Location at = item.getLocation();
            Storage.Origin origin = new Storage.Origin(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), now);
            boolean stored = storage.add(stack.clone(), owner == null ? null : owner.id, owner == null ? null : owner.name,
                    owner == null ? 0 : now + priorityMs, maxGroups, now, origin);
            if (stored) {
                count += stack.getAmount();
                stacks++;
                if (owner != null) {
                    Long before = reservedPerOwner.get(owner.id);
                    reservedPerOwner.put(owner.id, (before == null ? 0 : before) + stack.getAmount());
                }
                timers.remove(id);
                forgetOwner(id);
                item.remove();
            }
            // Otherwise max-pages is reached and the item needs a new entry: it stays on the ground.
        }
        if (count > 0) {
            markDirty();
            menu.refreshAll();
            Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', broadcastMessage
                    .replace("{count}", String.valueOf(count))
                    .replace("{stacks}", String.valueOf(stacks))));
            for (Map.Entry<UUID, Long> entry : reservedPerOwner.entrySet()) {
                Player owner = Bukkit.getPlayer(entry.getKey());
                if (owner != null) {
                    owner.sendMessage(messages.get("owner-looted", "count", Menu.number(entry.getValue()),
                            "time", Menu.duration(priorityMs)));
                }
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "loot":
                return forceLoot(sender, label, args);
            case "clear":
                return clear(sender, label, args);
            case "reload":
                return reload(sender);
            case "config":
                return configCommand(sender, label, args);
            case "search":
                return searchCommand(sender, label, args);
            case "log":
                return logCommand(sender, args);
            default:
                return openStorage(sender, label, args);
        }
    }

    private boolean openStorage(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(messages.get("players-only"));
            return true;
        }
        Player p = (Player) sender;
        if (!p.hasPermission("unlooted.use")) {
            p.sendMessage(messages.get("no-permission"));
            return true;
        }
        if (inCombat(p)) {
            p.sendMessage(messages.get("in-combat"));
            return true;
        }
        if (storage.isEmpty()) {
            p.sendMessage(messages.get("no-items"));
            return true;
        }
        int pages = Menu.listPages(menu.listFor(p.getUniqueId()).size());
        int page = 1;
        if (args.length > 0) {
            try {
                page = Integer.parseInt(args[0]);
            } catch (NumberFormatException ex) {
                p.sendMessage(messages.get("usage-page", "label", label));
                return true;
            }
        }
        if (page < 1 || page > pages) {
            p.sendMessage(messages.get("page-range", "pages", pages));
            return true;
        }
        menu.openList(p, page - 1);
        return true;
    }

    /** /unlooted search [text]: opens the item list showing only matching items. No text clears the search. */
    private boolean searchCommand(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(messages.get("players-only"));
            return true;
        }
        Player p = (Player) sender;
        if (!p.hasPermission("unlooted.use")) {
            p.sendMessage(messages.get("no-permission"));
            return true;
        }
        if (inCombat(p)) {
            p.sendMessage(messages.get("in-combat"));
            return true;
        }
        if (storage.isEmpty()) {
            p.sendMessage(messages.get("no-items"));
            return true;
        }
        String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        if (menu.search(p, query) == 0) {
            p.sendMessage(messages.get("search-none", "query", query));
            menu.search(p, null);
            return true;
        }
        menu.openList(p, 0);
        return true;
    }

    /** /unlooted log [name]: the latest takes, optionally only those mentioning a player. */
    private boolean logCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("unlooted.admin")) {
            sender.sendMessage(messages.get("no-permission"));
            return true;
        }
        String filter = args.length > 1 ? args[1] : null;
        List<String> lines = takeLog.latest(filter, 15);
        if (lines.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No matching entries since the last restart. Older ones are in takes.log.");
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + "Latest takes, newest first. The full history is in plugins/Unlooted/takes.log.");
        for (String line : lines) {
            sender.sendMessage(ChatColor.GRAY + line);
        }
        return true;
    }

    // ------------------------------------------------------------------ combat and joins

    private boolean inCombat(Player player) {
        Long until = combatUntil.get(player.getUniqueId());
        return combatMs > 0 && until != null && until > System.currentTimeMillis();
    }

    /** Player-versus-player hits tag both players for combat-block-seconds, and close the victim's storage. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (combatMs <= 0 || !(e.getEntity() instanceof Player)) {
            return;
        }
        Entity damager = e.getDamager();
        if (damager instanceof Projectile && ((Projectile) damager).getShooter() instanceof Entity) {
            damager = (Entity) ((Projectile) damager).getShooter();
        }
        if (!(damager instanceof Player) || damager.equals(e.getEntity())) {
            return;
        }
        long until = System.currentTimeMillis() + combatMs;
        for (Player player : new Player[] {(Player) e.getEntity(), (Player) damager}) {
            combatUntil.put(player.getUniqueId(), until);
            if (menu.isViewing(player)) {
                player.closeInventory();
                player.sendMessage(messages.get("in-combat"));
            }
        }
    }

    /** Tells players who log in about items reserved for them. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player player = e.getPlayer();
        getServer().getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            long reserved = storage.reservedFor(player.getUniqueId(), System.currentTimeMillis());
            if (reserved > 0) {
                player.sendMessage(messages.get("owner-join", "count", Menu.number(reserved)));
            }
        }, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        combatUntil.remove(e.getPlayer().getUniqueId());
    }

    /** /unlooted loot [all]: runs the global loot sweep now. "all" also ignores the per-item timers. */
    private boolean forceLoot(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("unlooted.loot")) {
            sender.sendMessage(messages.get("no-permission"));
            return true;
        }
        boolean all = args.length > 1 && args[1].equalsIgnoreCase("all");
        if (args.length > 2 || (args.length == 2 && !all)) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " loot [all]");
            return true;
        }
        if (sweep(all) == 0) {
            sender.sendMessage(ChatColor.YELLOW + (all
                    ? "There were no items on the ground to loot."
                    : "No item has been on the ground long enough. Use /" + label + " loot all to loot everything."));
        }
        return true;
    }

    /** /unlooted clear [confirm]: permanently deletes everything in the storage. */
    private boolean clear(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("unlooted.admin")) {
            sender.sendMessage(messages.get("no-permission"));
            return true;
        }
        long now = System.currentTimeMillis();
        int entries = storage.groups().size() + storage.unreadable.size();
        String summary = Menu.number(storage.totalItems()) + " item(s) of " + storage.groups().size() + " kind(s)"
                + (storage.unreadable.isEmpty() ? "" : ", plus " + storage.unreadable.size() + " entry(s) from missing mods");

        boolean confirming = args.length > 1 && args[1].equalsIgnoreCase("confirm");
        Long asked = pendingClears.get(sender.getName());
        if (!confirming || asked == null || now - asked > CLEAR_CONFIRM_MS) {
            if (entries == 0) {
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
        menu.closeAll();
        storage.clear();
        save(false);
        getLogger().info(sender.getName() + " cleared the storage, deleting " + summary + ".");
        log(sender.getName() + " cleared the storage, deleting " + summary);
        sender.sendMessage(ChatColor.GREEN + "Storage cleared. Deleted " + summary + ".");
        return true;
    }

    /** /unlooted reload: re-reads config.yml from disk. */
    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("unlooted.admin")) {
            sender.sendMessage(messages.get("no-permission"));
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
            sender.sendMessage(messages.get("no-permission"));
            return true;
        }
        if (args.length == 1) {
            sender.sendMessage(ChatColor.YELLOW + "Unlooted settings. Change one with /" + label + " config <key> <value>");
            for (String key : SETTINGS.keySet()) {
                sender.sendMessage(ChatColor.GRAY + " " + key + ": " + ChatColor.WHITE + getConfig().get(key));
            }
            return true;
        }
        String key = args[1].toLowerCase(Locale.ROOT);
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
                case "owner-priority-seconds":
                case "expire-after-days":
                case "combat-block-seconds":
                case "max-pages":
                    long number = Long.parseLong(raw);
                    long min = key.startsWith("loot-") ? 1 : 0;
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
                case "excluded-worlds":
                case "excluded-items":
                    List<String> list = new ArrayList<String>();
                    if (!raw.equalsIgnoreCase("none")) {
                        for (String part : raw.split(",")) {
                            if (!part.trim().isEmpty()) {
                                list.add(part.trim());
                            }
                        }
                    }
                    List<String> quoted = new ArrayList<String>();
                    for (String entry : list) {
                        quoted.add(quote(entry));
                    }
                    value = list;
                    yaml = "[" + String.join(", ", quoted) + "]";
                    break;
                default:
                    value = raw;
                    yaml = quote(raw);
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

    private static String quote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
                // A list written in block style continues on the following "- " lines: drop them.
                while (i + 1 < lines.size() && lines.get(i + 1).trim().startsWith("- ")) {
                    lines.remove(i + 1);
                }
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
        boolean admin = sender.hasPermission("unlooted.admin");
        if (args.length == 1) {
            String typed = args[0].toLowerCase(Locale.ROOT);
            if ("loot".startsWith(typed) && sender.hasPermission("unlooted.loot")) {
                out.add("loot");
            }
            if ("search".startsWith(typed) && sender.hasPermission("unlooted.use")) {
                out.add("search");
            }
            for (String sub : new String[] {"clear", "reload", "config", "log"}) {
                if (sub.startsWith(typed) && admin) {
                    out.add(sub);
                }
            }
            for (int i = 1; i <= Menu.listPages(storage.groups().size()) && !storage.isEmpty(); i++) {
                if (String.valueOf(i).startsWith(args[0])) {
                    out.add(String.valueOf(i));
                }
            }
        } else if (args.length == 2) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            if (args[0].equalsIgnoreCase("loot") && "all".startsWith(typed)) {
                out.add("all");
            } else if (args[0].equalsIgnoreCase("clear") && admin && "confirm".startsWith(typed)) {
                out.add("confirm");
            } else if (args[0].equalsIgnoreCase("config") && admin) {
                for (String key : SETTINGS.keySet()) {
                    if (key.startsWith(typed)) {
                        out.add(key);
                    }
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("config") && admin) {
            String typed = args[2].toLowerCase(Locale.ROOT);
            if (args[1].equalsIgnoreCase("ignore-unpickupable-items")) {
                for (String b : new String[] {"true", "false"}) {
                    if (b.startsWith(typed)) {
                        out.add(b);
                    }
                }
            } else if (args[1].equalsIgnoreCase("excluded-worlds")) {
                for (World world : Bukkit.getWorlds()) {
                    if (world.getName().toLowerCase(Locale.ROOT).startsWith(typed)) {
                        out.add(world.getName());
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ persistence

    /**
     * Saves storage and timers. The snapshot is taken on the main thread; turning it into YAML and
     * writing the file happen in the background, except on shutdown or under Bukkit serialization.
     * Every save gets a sequence number so an older background write can never replace a newer one.
     */
    private void save(boolean sync) {
        if (storageBroken) {
            return;
        }
        final Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("format", 2);
        root.put("groups", storage.snapshot());
        long now = System.currentTimeMillis();
        Map<String, Object> timerData = new LinkedHashMap<String, Object>();
        for (Map.Entry<UUID, Timer> entry : timers.entrySet()) {
            timerData.put(entry.getKey().toString(), entry.getValue().current(now));
        }
        root.put("timers", timerData);
        Map<String, Object> ownerData = new LinkedHashMap<String, Object>();
        for (Map.Entry<UUID, Owner> entry : owners.entrySet()) {
            Map<String, Object> o = new LinkedHashMap<String, Object>();
            o.put("owner", entry.getValue().id.toString());
            o.put("owner-name", entry.getValue().name);
            Long seenAt = ownerSeen.get(entry.getKey());
            o.put("seen", seenAt == null ? now : seenAt);
            ownerData.put(entry.getKey().toString(), o);
        }
        root.put("owners", ownerData);
        dirty = false;
        final long seq = ++saveSeq;
        if (sync || !codec.isLossless()) {
            write(seq, dump(root));
        } else {
            getServer().getScheduler().runTaskAsynchronously(this, () -> write(seq, dump(root)));
        }
    }

    private static String dump(Map<String, Object> root) {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            yml.set(entry.getKey(), entry.getValue());
        }
        return yml.saveToString();
    }

    /** Writes to a temporary file first, so a crash mid-save never leaves a half-written data.yml. */
    private void write(long seq, String text) {
        synchronized (writeLock) {
            if (seq <= writtenSeq) {
                return;
            }
            File tmp = new File(getDataFolder(), "data.yml.tmp");
            try {
                getDataFolder().mkdirs();
                Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(tmp.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tmp.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                writtenSeq = seq;
            } catch (IOException ex) {
                getLogger().log(Level.SEVERE, "Could not save data.yml", ex);
            }
        }
    }

    private void loadOwner(UUID item, String owner, String name, long seen) {
        if (owner == null) {
            return;
        }
        try {
            owners.put(item, new Owner(UUID.fromString(owner), name));
            ownerSeen.put(item, seen);
        } catch (IllegalArgumentException ignored) {
            // Unknown owner: the item becomes shared.
        }
    }

    private void load() {
        if (!dataFile.exists()) {
            return;
        }
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
            }
            return;
        }

        long now = System.currentTimeMillis();
        storage.load(yml.getList("groups"), yml.getList("items"), now);
        if (!storage.unreadable.isEmpty()) {
            getLogger().warning(storage.unreadable.size() + " stored item(s) could not be loaded, probably because"
                    + " the mod that adds them is missing. They stay in data.yml and load again once it is back.");
        }

        ConfigurationSection section = yml.getConfigurationSection("timers");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                UUID id;
                try {
                    id = UUID.fromString(key);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                long age;
                if (section.isConfigurationSection(key)) {
                    ConfigurationSection t = section.getConfigurationSection(key);
                    age = t.getLong("age");
                    // Saved by 0.3.0 to 0.5.1, which kept the owner with the timer.
                    loadOwner(id, t.getString("owner"), t.getString("owner-name", "its owner"), now);
                } else {
                    age = section.getLong(key);
                }
                // Paused until the item is seen in a loaded chunk again.
                timers.put(id, new Timer(age, now, true));
            }
        }
        ConfigurationSection ownerSection = yml.getConfigurationSection("owners");
        if (ownerSection != null) {
            for (String key : ownerSection.getKeys(false)) {
                ConfigurationSection o = ownerSection.getConfigurationSection(key);
                if (o == null) {
                    continue;
                }
                try {
                    loadOwner(UUID.fromString(key), o.getString("owner"), o.getString("owner-name", "its owner"), o.getLong("seen", now));
                } catch (IllegalArgumentException ignored) {
                    // Corrupt key: skip it.
                }
            }
        }
        if (yml.contains("items")) {
            // Converted from the old format: write the new one straight away.
            dirty = true;
        }
    }
}
