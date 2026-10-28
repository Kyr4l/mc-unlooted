package me.unlooted;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public class Unlooted extends JavaPlugin implements Listener {

    private static final long RETRY_TICKS = 20L * 30;

    private Inventory shared;
    private long delayTicks;
    private File dataFile;
    /** Items whose 300s timer is running; despawn is blocked for these. */
    private final Set<UUID> tracked = new HashSet<UUID>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        delayTicks = Math.max(1, getConfig().getLong("loot-delay-seconds", 300)) * 20L;
        int rows = Math.min(6, Math.max(1, getConfig().getInt("rows", 6)));
        shared = Bukkit.createInventory(null, rows * 9, "Unlooted Items");
        dataFile = new File(getDataFolder(), "data.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        // Periodic autosave every 5 minutes.
        getServer().getScheduler().runTaskTimer(this, new Runnable() {
            public void run() {
                save();
            }
        }, 20L * 300, 20L * 300);
    }

    @Override
    public void onDisable() {
        save();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        final Item item = e.getEntity();
        final UUID id = item.getUniqueId();
        tracked.add(id);
        schedule(item, delayTicks);
    }

    private void schedule(final Item item, long ticks) {
        getServer().getScheduler().runTaskLater(this, new Runnable() {
            public void run() {
                collect(item);
            }
        }, ticks);
    }

    private void collect(Item item) {
        UUID id = item.getUniqueId();
        if (item.isDead() || !item.isValid()) {
            // Picked up, merged, burned or in an unloaded chunk: nothing to loot.
            tracked.remove(id);
            return;
        }
        ItemStack stack = item.getItemStack();
        if (stack == null || stack.getType() == org.bukkit.Material.AIR) {
            tracked.remove(id);
            item.remove();
            return;
        }
        Map<Integer, ItemStack> left = shared.addItem(stack.clone());
        if (left.isEmpty()) {
            tracked.remove(id);
            item.remove();
        } else {
            // Shared inventory is full: keep what didn't fit in the world and retry.
            item.setItemStack(left.values().iterator().next());
            schedule(item, RETRY_TICKS);
        }
    }

    /** Keep tracked items alive until their own timer fires (vanilla despawn is also 5 minutes). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDespawn(ItemDespawnEvent e) {
        if (tracked.contains(e.getEntity().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().equals(shared)) {
            save();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can open the shared inventory.");
            return true;
        }
        Player p = (Player) sender;
        if (!p.hasPermission("unlooted.use")) {
            p.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }
        p.openInventory(shared);
        return true;
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("items", java.util.Arrays.asList(shared.getContents()));
        try {
            getDataFolder().mkdirs();
            yml.save(dataFile);
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Could not save data.yml", ex);
        }
    }

    private void load() {
        if (!dataFile.exists()) {
            return;
        }
        List<?> list = YamlConfiguration.loadConfiguration(dataFile).getList("items");
        if (list == null) {
            return;
        }
        for (int i = 0; i < list.size() && i < shared.getSize(); i++) {
            Object o = list.get(i);
            if (o instanceof ItemStack) {
                shared.setItem(i, (ItemStack) o);
            }
        }
    }
}
