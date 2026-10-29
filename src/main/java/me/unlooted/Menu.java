package me.unlooted;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The storage menu. Each player gets their own view, drawn from the storage model, so nothing in the
 * menu is a real item: every click is handled here and taking is the only thing a player can do.
 *
 * The item list shows one icon per distinct item with its real amount. Clicking an item with several
 * stacks opens a sub-menu listing them. The bottom row is a navigation bar.
 */
final class Menu implements Listener {

    static final int PER_PAGE = 45;
    private static final long CLICK_COOLDOWN_MS = 150;
    /** Containers larger than this (big modded storage) are not scanned for escaped menu items. */
    private static final int SCAN_LIMIT = 256;

    private enum Nav { PREV, NEXT, BACK }

    /** One stack shown in a sub-menu. */
    private static final class StackRef {
        final Storage.Lot lot;
        final long amount;

        StackRef(Storage.Lot lot, long amount) {
            this.lot = lot;
            this.amount = amount;
        }
    }

    /** A player's open menu: which page of which list, and what each slot does. */
    private static final class View implements InventoryHolder {
        Inventory inventory;
        String title;
        /** Null for the item list, else the item whose stacks are shown. */
        Storage.Group group;
        int page;
        /** Item list page to return to from a sub-menu. */
        int returnPage;
        Object[] actions;
        ItemStack[] expected;
        long[] prints;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Unlooted plugin;
    private final Storage storage;
    private final Map<UUID, Long> lastClick = new HashMap<UUID, Long>();
    private int tick;

    Menu(Unlooted plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    private static View viewOf(Inventory inventory) {
        InventoryHolder holder = inventory == null ? null : inventory.getHolder();
        return holder instanceof View ? (View) holder : null;
    }

    private static View openView(Player player) {
        return viewOf(player.getOpenInventory().getTopInventory());
    }

    static int listPages(int groups) {
        return Math.max(1, (groups + PER_PAGE - 1) / PER_PAGE);
    }

    // ------------------------------------------------------------------ drawing

    void openList(Player player, int page) {
        open(player, null, page, page);
    }

    private void open(Player player, Storage.Group group, int page, int returnPage) {
        long now = System.currentTimeMillis();
        UUID viewer = player.getUniqueId();
        boolean bypass = player.hasPermission("unlooted.admin");
        List<ItemStack> icons = new ArrayList<ItemStack>();
        List<Object> actions = new ArrayList<Object>();
        int pages;
        String name;

        if (group == null) {
            List<Storage.Group> groups = storage.groups();
            pages = listPages(groups.size());
            page = Math.max(0, Math.min(page, pages - 1));
            for (int i = page * PER_PAGE; i < Math.min(groups.size(), (page + 1) * PER_PAGE); i++) {
                Storage.Group g = groups.get(i);
                storage.normalize(g, now);
                icons.add(groupIcon(g, viewer, bypass, now));
                actions.add(g);
            }
            name = "Unlooted Items";
        } else {
            storage.normalize(group, now);
            long stacks = group.stacks();
            pages = (int) Math.max(1, Math.min(Integer.MAX_VALUE, (stacks + PER_PAGE - 1) / PER_PAGE));
            page = Math.max(0, Math.min(page, pages - 1));
            long skip = (long) page * PER_PAGE;
            for (Storage.Lot lot : storage.ordered(group, viewer, now)) {
                long lotStacks = (lot.amount + group.maxStack - 1) / group.maxStack;
                if (skip >= lotStacks) {
                    skip -= lotStacks;
                    continue;
                }
                for (long s = skip; s < lotStacks && icons.size() < PER_PAGE; s++) {
                    long amount = s == lotStacks - 1 ? lot.amount - (lotStacks - 1) * group.maxStack : group.maxStack;
                    icons.add(stackIcon(group, lot, amount, viewer, bypass, now));
                    actions.add(new StackRef(lot, amount));
                }
                skip = 0;
                if (icons.size() >= PER_PAGE) {
                    break;
                }
            }
            name = displayName(group.template);
        }

        int size = (Math.max(1, (icons.size() + 8) / 9) + 1) * 9;
        String title = title(name, page, pages);
        View view = openView(player);
        boolean reuse = view != null && view.inventory.getSize() == size && view.title.equals(title);
        if (!reuse) {
            view = new View();
            view.title = title;
            view.inventory = Bukkit.createInventory(view, size, title);
        }
        view.group = group;
        view.page = page;
        view.returnPage = returnPage;
        view.actions = new Object[size];
        view.expected = new ItemStack[size];
        view.prints = new long[size];

        Inventory inventory = view.inventory;
        for (int i = 0; i < size - 9; i++) {
            if (i < icons.size()) {
                view.expected[i] = icons.get(i);
                view.actions[i] = actions.get(i);
            }
        }
        ItemStack[] bar = navigationBar(page, pages, group != null);
        for (int i = 0; i < 9; i++) {
            view.expected[size - 9 + i] = bar[i];
        }
        if (page > 0) {
            view.actions[size - 9] = Nav.PREV;
        }
        if (page < pages - 1) {
            view.actions[size - 1] = Nav.NEXT;
        }
        if (group != null) {
            view.actions[size - 5] = Nav.BACK;
        }
        for (int i = 0; i < size; i++) {
            inventory.setItem(i, view.expected[i]);
            view.prints[i] = print(inventory.getItem(i));
        }
        if (!reuse) {
            player.openInventory(inventory);
        }
    }

    private ItemStack groupIcon(Storage.Group group, UUID viewer, boolean bypass, long now) {
        long available = storage.available(group, viewer, bypass, now);
        long reserved = group.total() - available;
        List<String> lines = new ArrayList<String>();
        lines.add(ChatColor.YELLOW + "Available: " + ChatColor.WHITE + number(available));
        if (reserved > 0) {
            lines.add(ChatColor.RED + "Reserved for owners: " + ChatColor.WHITE + number(reserved));
        }
        if (group.stacks() > 1) {
            lines.add(ChatColor.GRAY + "Click to choose a stack");
            lines.add(ChatColor.GRAY + "Shift-click to take a stack");
        } else if (available > 0) {
            lines.add(ChatColor.GRAY + "Click to take");
        }
        return Tags.icon(group.template, 1, lines, displayName(group.template));
    }

    private ItemStack stackIcon(Storage.Group group, Storage.Lot lot, long amount, UUID viewer, boolean bypass, long now) {
        List<String> lines = new ArrayList<String>();
        if (lot.reserved(now)) {
            lines.add(lot.owner.equals(viewer)
                    ? ChatColor.GREEN + "Reserved for you"
                    : ChatColor.RED + "Reserved for " + (lot.ownerName == null ? "its owner" : lot.ownerName)
                    + ", " + duration(lot.reservedUntil - now) + " left");
        }
        if (lot.canTake(viewer, bypass, now)) {
            lines.add(ChatColor.GRAY + "Click to take");
        }
        return Tags.icon(group.template, (int) amount, lines, displayName(group.template));
    }

    private static ItemStack[] navigationBar(int page, int pages, boolean subMenu) {
        ItemStack[] bar = new ItemStack[9];
        ItemStack filler = Tags.pane("BLACK", 15, ChatColor.GRAY + "Page " + (page + 1) + "/" + pages,
                ChatColor.DARK_GRAY + "Navigation bar, not lootable");
        for (int i = 0; i < 9; i++) {
            bar[i] = filler;
        }
        if (page > 0) {
            bar[0] = Tags.pane("RED", 14, ChatColor.RED + "« Previous page", ChatColor.GRAY + "Go to page " + page + "/" + pages);
        }
        if (page < pages - 1) {
            bar[8] = Tags.pane("GREEN", 13, ChatColor.GREEN + "Next page »", ChatColor.GRAY + "Go to page " + (page + 2) + "/" + pages);
        }
        if (subMenu) {
            bar[4] = Tags.pane("YELLOW", 4, ChatColor.YELLOW + "Back to all items", ChatColor.GRAY + "Return to the item list");
        }
        return bar;
    }

    /** Re-draws every open menu after the storage changed. */
    void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            View view = openView(player);
            if (view == null) {
                continue;
            }
            if (storage.isEmpty()) {
                player.closeInventory();
                player.sendMessage(ChatColor.YELLOW + "The storage is empty now.");
            } else if (view.group != null && !storage.contains(view.group)) {
                open(player, null, view.returnPage, view.returnPage);
            } else {
                open(player, view.group, view.page, view.returnPage);
            }
        }
    }

    void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (openView(player) != null) {
                player.closeInventory();
            }
        }
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        View view = viewOf(top);
        if (view == null) {
            // Outside the menu: a menu item that escaped somehow is removed the moment it is touched.
            if (Tags.isTagged(e.getCurrentItem())) {
                e.setCancelled(true);
                e.setCurrentItem(null);
            }
            if (Tags.isTagged(e.getCursor())) {
                e.setCancelled(true);
                e.getView().setCursor(null);
            }
            return;
        }
        int raw = e.getRawSlot();
        if (raw < 0 || raw >= top.getSize()) {
            // Players may rearrange their own inventory, but nothing goes into the storage.
            if (e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY || e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
                e.setCancelled(true);
            }
            return;
        }
        e.setCancelled(true);
        Object action = view.actions[raw];
        ClickType click = e.getClick();
        if (action == null || !(e.getWhoClicked() instanceof Player)
                || (click != ClickType.LEFT && click != ClickType.RIGHT && click != ClickType.SHIFT_LEFT && click != ClickType.SHIFT_RIGHT)) {
            return;
        }
        final Player player = (Player) e.getWhoClicked();
        long now = System.currentTimeMillis();
        Long last = lastClick.get(player.getUniqueId());
        if (last != null && now - last < CLICK_COOLDOWN_MS) {
            return;
        }
        lastClick.put(player.getUniqueId(), now);
        final boolean shift = e.isShiftClick();
        final View clicked = view;
        // Opening menus and giving items inside a click event is unsafe, so act on the next tick.
        plugin.getServer().getScheduler().runTask(plugin, () -> handle(player, clicked, action, shift));
    }

    private void handle(Player player, View view, Object action, boolean shift) {
        if (!player.isOnline() || openView(player) != view) {
            return;
        }
        if (action == Nav.PREV || action == Nav.NEXT) {
            open(player, view.group, view.page + (action == Nav.PREV ? -1 : 1), view.returnPage);
        } else if (action == Nav.BACK) {
            open(player, null, view.returnPage, view.returnPage);
        } else if (action instanceof Storage.Group) {
            Storage.Group group = (Storage.Group) action;
            if (!storage.contains(group)) {
                refreshAll();
            } else if (shift || group.stacks() <= 1) {
                takeStack(player, group);
            } else {
                open(player, group, 0, view.page);
            }
        } else if (action instanceof StackRef) {
            StackRef ref = (StackRef) action;
            Storage.Group group = view.group;
            if (group == null || !storage.contains(group) || !group.lots.contains(ref.lot)) {
                refreshAll();
            } else if (!ref.lot.canTake(player.getUniqueId(), player.hasPermission("unlooted.admin"), System.currentTimeMillis())) {
                player.sendMessage(ChatColor.RED + "That stack is reserved for its owner.");
            } else {
                give(player, group, ref.lot, ref.amount);
            }
        }
    }

    private void takeStack(Player player, Storage.Group group) {
        long now = System.currentTimeMillis();
        boolean bypass = player.hasPermission("unlooted.admin");
        for (Storage.Lot lot : storage.ordered(group, player.getUniqueId(), now)) {
            if (lot.canTake(player.getUniqueId(), bypass, now)) {
                give(player, group, lot, Math.min(group.maxStack, lot.amount));
                return;
            }
        }
        player.sendMessage(ChatColor.RED + "Those items are reserved for their owner.");
    }

    /** Moves items from storage into the player's inventory. Whatever doesn't fit stays in storage. */
    private void give(Player player, Storage.Group group, Storage.Lot lot, long amount) {
        ItemStack item = group.template.clone();
        item.setAmount((int) Math.min(amount, lot.amount));
        int wanted = item.getAmount();
        long leftover = 0;
        for (ItemStack left : player.getInventory().addItem(item).values()) {
            leftover += left.getAmount();
        }
        long taken = wanted - leftover;
        if (taken > 0) {
            storage.take(group, lot, taken);
            plugin.markDirty();
        }
        if (leftover > 0) {
            player.sendMessage(ChatColor.RED + "Your inventory is full.");
        }
        refreshAll();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (viewOf(top) == null) {
            return;
        }
        for (int raw : e.getRawSlots()) {
            if (raw < top.getSize()) {
                e.setCancelled(true);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ guarding

    /**
     * Runs every tick. Sorting mods and similar tools can change menu slots without any click event.
     * Changed slots are restored, anything real that was moved in goes back to the player, and menu
     * items that reached the player are removed. Slots are compared by a cheap print, not item data.
     */
    void guard() {
        tick++;
        for (Player player : Bukkit.getOnlinePlayers()) {
            View view = openView(player);
            if (view == null) {
                continue;
            }
            Inventory inventory = view.inventory;
            boolean changed = false;
            for (int i = 0; i < inventory.getSize(); i++) {
                ItemStack current = inventory.getItem(i);
                if (print(current) == view.prints[i]) {
                    continue;
                }
                changed = true;
                if (current != null && current.getAmount() > 0 && !Tags.isTagged(current)) {
                    giveBack(player, current.clone());
                }
                inventory.setItem(i, view.expected[i]);
            }
            if (changed || tick % 20 == 0) {
                Tags.purge(player);
            }
        }
    }

    private static void giveBack(Player player, ItemStack item) {
        for (ItemStack left : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItem(player.getLocation(), left);
        }
    }

    @SuppressWarnings("deprecation")
    private static long print(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) {
            return 0;
        }
        return ((stack.getType().ordinal() + 1L) << 32) | ((stack.getAmount() & 0xFFFFL) << 16) | (stack.getDurability() & 0xFFFFL);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        final org.bukkit.entity.HumanEntity player = e.getPlayer();
        if (viewOf(e.getInventory()) == null) {
            // Closing any other container: clear out menu items that might have been smuggled into it.
            Tags.purge(e.getInventory(), SCAN_LIMIT);
            return;
        }
        // The cursor item goes back to the player after this event, so check again next tick.
        Tags.purge(player);
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> Tags.purge(player));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (viewOf(e.getInventory()) == null) {
            Tags.purge(e.getInventory(), SCAN_LIMIT);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Tags.purge(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastClick.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ text

    static String displayName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return ChatColor.stripColor(meta.getDisplayName());
        }
        String type = item.getType().name();
        if (type.equals("AIR")) {
            return "Unknown item";
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

    /** Inventory titles may be at most 32 characters on 1.12. */
    private static String title(String name, int page, int pages) {
        String suffix = pages > 1 ? " " + (page + 1) + "/" + pages : "";
        int room = 32 - suffix.length();
        if (name.length() > room) {
            name = name.substring(0, Math.max(1, room - 1)) + "…";
        }
        return name + suffix;
    }

    static String number(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static String duration(long ms) {
        long seconds = Math.max(1, (ms + 999) / 1000);
        return seconds >= 60 ? (seconds / 60) + "m " + (seconds % 60) + "s" : seconds + "s";
    }
}
