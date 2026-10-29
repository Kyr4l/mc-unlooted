package me.unlooted;

import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The storage menu. Each player gets their own view, drawn from the storage model, so nothing in the
 * menu is a real item: every click is handled here and taking is the only thing a player can do.
 *
 * The item list shows one icon per distinct item with its real amount. Clicking an item with several
 * stacks opens a list of them. The bottom row is a navigation bar with paging, search and sorting.
 */
final class Menu implements Listener {

    static final int PER_PAGE = 45;
    private static final long CLICK_COOLDOWN_MS = 150;
    /** Containers larger than this (big modded storage) are not scanned for escaped menu items. */
    private static final int SCAN_LIMIT = 256;

    private enum Nav { PREV, NEXT, BACK, SORT, SEARCH }

    enum Sort { RECENT, NAME, AMOUNT }

    /** One stack shown in a stack list. */
    private static final class StackRef {
        final Storage.Lot lot;
        final long amount;

        StackRef(Storage.Lot lot, long amount) {
            this.lot = lot;
            this.amount = amount;
        }
    }

    /** A player's open menu: which page of which list, what each slot does, and what was placed where. */
    private static final class View implements InventoryHolder {
        Inventory inventory;
        String title;
        /** Null for the item list, else the item whose stacks are shown. */
        Storage.Group group;
        int page;
        /** Item list page to return to from a stack list. */
        int returnPage;
        Object[] actions;
        ItemStack[] expected;
        /** Each slot's item as the server stored it after placing, the reference for spotting menu items. */
        ItemStack[] placed;
        long[] prints;
        /** Type and damage of every placed item, to skip most items quickly when looking for menu copies. */
        Set<Long> placedKinds;
        /** The player's inventory a tick ago, to tell items moved into the menu from copied ones. */
        long[] playerPrints;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Unlooted plugin;
    private final Storage storage;
    private final Map<UUID, Long> lastClick = new HashMap<UUID, Long>();
    private final Set<UUID> resyncing = new HashSet<UUID>();
    private final Map<UUID, Sort> sorts = new HashMap<UUID, Sort>();
    private final Map<UUID, String> searches = new HashMap<UUID, String>();
    private final Map<Sort, List<Storage.Group>> sortedCache = new HashMap<Sort, List<Storage.Group>>();
    private long sortedVersion = -1;
    private int tick;

    Menu(Unlooted plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    private Messages msg() {
        return plugin.messages();
    }

    private static View viewOf(Inventory inventory) {
        InventoryHolder holder = inventory == null ? null : inventory.getHolder();
        return holder instanceof View ? (View) holder : null;
    }

    private static View openView(HumanEntity player) {
        return viewOf(player.getOpenInventory().getTopInventory());
    }

    static int listPages(int groups) {
        return Math.max(1, (groups + PER_PAGE - 1) / PER_PAGE);
    }

    // ------------------------------------------------------------------ listing

    /** The item list for a player: sorted by their sort mode and filtered by their search. */
    List<Storage.Group> listFor(UUID player) {
        Sort sort = sorts.containsKey(player) ? sorts.get(player) : Sort.RECENT;
        if (sortedVersion != storage.version()) {
            sortedCache.clear();
            sortedVersion = storage.version();
        }
        List<Storage.Group> sorted = sortedCache.get(sort);
        if (sorted == null) {
            sorted = new ArrayList<Storage.Group>(storage.groups());
            if (sort == Sort.RECENT) {
                final Map<Storage.Group, Long> last = new HashMap<Storage.Group, Long>();
                for (Storage.Group g : sorted) {
                    last.put(g, g.lastLooted());
                }
                Collections.sort(sorted, (a, b) -> Long.compare(last.get(b), last.get(a)));
            } else if (sort == Sort.NAME) {
                Collections.sort(sorted, Comparator.comparing(g -> g.searchText));
            } else {
                final Map<Storage.Group, Long> totals = new HashMap<Storage.Group, Long>();
                for (Storage.Group g : sorted) {
                    totals.put(g, g.total());
                }
                Collections.sort(sorted, (a, b) -> Long.compare(totals.get(b), totals.get(a)));
            }
            sortedCache.put(sort, sorted);
        }
        String query = searches.get(player);
        if (query == null) {
            return sorted;
        }
        List<Storage.Group> filtered = new ArrayList<Storage.Group>();
        for (Storage.Group g : sorted) {
            if (g.searchText.contains(query)) {
                filtered.add(g);
            }
        }
        return filtered;
    }

    /** Sets or clears a player's search. Returns the number of matching items. */
    int search(Player player, String query) {
        if (query == null || query.trim().isEmpty()) {
            searches.remove(player.getUniqueId());
        } else {
            searches.put(player.getUniqueId(), query.trim().toLowerCase(Locale.ROOT));
        }
        return listFor(player.getUniqueId()).size();
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
            List<Storage.Group> groups = listFor(viewer);
            pages = listPages(groups.size());
            page = Math.max(0, Math.min(page, pages - 1));
            for (int i = page * PER_PAGE; i < Math.min(groups.size(), (page + 1) * PER_PAGE); i++) {
                Storage.Group g = groups.get(i);
                storage.normalize(g, now);
                icons.add(groupIcon(g, viewer, bypass, now));
                actions.add(g);
            }
            name = msg().get("title");
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
            name = Tags.displayName(group.template, msg().get("unknown-item"));
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
        view.placed = new ItemStack[size];
        view.prints = new long[size];
        view.placedKinds = new HashSet<Long>();

        for (int i = 0; i < Math.min(icons.size(), size - 9); i++) {
            view.expected[i] = icons.get(i);
            view.actions[i] = actions.get(i);
        }
        ItemStack[] bar = navigationBar(viewer, page, pages, group != null);
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
        } else {
            view.actions[size - 7] = Nav.SEARCH;
            view.actions[size - 3] = Nav.SORT;
        }
        Inventory inventory = view.inventory;
        for (int i = 0; i < size; i++) {
            inventory.setItem(i, view.expected[i]);
            ItemStack placed = inventory.getItem(i);
            view.placed[i] = placed == null ? null : placed.clone();
            view.prints[i] = print(placed);
            if (placed != null) {
                view.placedKinds.add(kind(placed));
            }
        }
        view.playerPrints = prints(player.getInventory());
        if (!reuse) {
            player.openInventory(inventory);
        }
    }

    private ItemStack groupIcon(Storage.Group group, UUID viewer, boolean bypass, long now) {
        long available = storage.available(group, viewer, bypass, now);
        long reserved = group.total() - available;
        List<String> lines = new ArrayList<String>();
        lines.add(msg().get("available", "amount", number(available)));
        if (reserved > 0) {
            lines.add(msg().get("reserved-owners", "amount", number(reserved)));
        }
        if (available > 0) {
            if (group.stacks() > 1) {
                lines.add(msg().get("hint-choose"));
                lines.add(msg().get("hint-take-stack"));
            } else {
                lines.add(msg().get("hint-take"));
            }
            lines.add(msg().get("hint-half"));
            if (group.stacks() > 1) {
                lines.add(msg().get("hint-fill"));
            }
        } else if (group.stacks() > 1) {
            lines.add(msg().get("hint-choose"));
        }
        return Tags.icon(group.template, 1, lines, Tags.displayName(group.template, msg().get("unknown-item")));
    }

    private ItemStack stackIcon(Storage.Group group, Storage.Lot lot, long amount, UUID viewer, boolean bypass, long now) {
        List<String> lines = new ArrayList<String>();
        if (lot.reserved(now)) {
            lines.add(lot.owner.equals(viewer)
                    ? msg().get("reserved-you")
                    : msg().get("reserved-for", "owner", lot.ownerName == null ? "?" : lot.ownerName,
                    "time", duration(lot.reservedUntil - now)));
        }
        if (lot.last != null && lot.last.world != null) {
            lines.add(msg().get("found-at", "world", lot.last.world, "x", lot.last.x, "y", lot.last.y, "z", lot.last.z));
        }
        if (lot.canTake(viewer, bypass, now)) {
            lines.add(msg().get("hint-take"));
            lines.add(msg().get("hint-half"));
        }
        if (lines.isEmpty()) {
            lines.add(msg().get("hint-take"));
        }
        return Tags.icon(group.template, (int) amount, lines, Tags.displayName(group.template, msg().get("unknown-item")));
    }

    private ItemStack[] navigationBar(UUID viewer, int page, int pages, boolean stackList) {
        Messages m = msg();
        ItemStack[] bar = new ItemStack[9];
        ItemStack filler = Tags.pane("BLACK", 15, m.get("bar-page", "page", page + 1, "pages", pages), m.get("bar-lore"));
        for (int i = 0; i < 9; i++) {
            bar[i] = filler;
        }
        if (page > 0) {
            bar[0] = Tags.pane("RED", 14, m.get("prev"), m.get("goto-lore", "page", page, "pages", pages));
        }
        if (page < pages - 1) {
            bar[8] = Tags.pane("GREEN", 13, m.get("next"), m.get("goto-lore", "page", page + 2, "pages", pages));
        }
        if (stackList) {
            bar[4] = Tags.pane("YELLOW", 4, m.get("back"), m.get("back-lore"));
        } else {
            String query = searches.get(viewer);
            bar[2] = query == null
                    ? Tags.pane("LIGHT_BLUE", 3, m.get("search"), m.get("search-lore"))
                    : Tags.pane("LIGHT_BLUE", 3, m.get("search-active", "query", query), m.get("search-active-lore"));
            Sort sort = sorts.containsKey(viewer) ? sorts.get(viewer) : Sort.RECENT;
            bar[6] = Tags.pane("CYAN", 9, m.get("sort", "mode", m.get("sort-" + sort.name().toLowerCase(Locale.ROOT))), m.get("sort-lore"));
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
                player.sendMessage(msg().get("storage-empty-now"));
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

    boolean isViewing(Player player) {
        return openView(player) != null;
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        View view = viewOf(top);
        if (view == null) {
            // Outside the menu: a menu item that escaped somehow is removed the moment it is touched.
            // This also covers creative mode, where the client says which item to put in a slot.
            if (Tags.isTagged(e.getCurrentItem())) {
                e.setCancelled(true);
                e.setCurrentItem(null);
            }
            if (Tags.isTagged(e.getCursor())) {
                e.setCancelled(true);
                if (!(e instanceof InventoryCreativeEvent)) {
                    e.getView().setCursor(null);
                }
            }
            return;
        }
        if (e instanceof InventoryCreativeEvent) {
            // A creative client can claim any item for a slot, including copies of menu icons.
            if (isMenuItem(view, e.getCursor())) {
                e.setCancelled(true);
                resync(e.getWhoClicked());
            }
            return;
        }
        int raw = e.getRawSlot();
        if (raw < 0 || raw >= top.getSize()) {
            // Players may rearrange their own inventory, but nothing goes into the storage.
            if (e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY || e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
                e.setCancelled(true);
                resync(e.getWhoClicked());
            }
            return;
        }
        e.setCancelled(true);
        // Client-side sorting mods predict the move locally: resend the real contents so no ghost items stay.
        resync(e.getWhoClicked());
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
        final View clicked = view;
        // Opening menus and giving items inside a click event is unsafe, so act on the next tick.
        plugin.getServer().getScheduler().runTask(plugin, () -> handle(player, clicked, action, click));
    }

    /** Resends the player's open inventory next tick. A burst of clicks still sends only one update. */
    private void resync(HumanEntity who) {
        if (who instanceof Player && plugin.isEnabled() && resyncing.add(who.getUniqueId())) {
            final Player player = (Player) who;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                resyncing.remove(player.getUniqueId());
                if (player.isOnline()) {
                    player.updateInventory();
                }
            });
        }
    }

    private void handle(Player player, View view, Object action, ClickType click) {
        if (!player.isOnline() || openView(player) != view) {
            return;
        }
        UUID id = player.getUniqueId();
        if (action == Nav.PREV || action == Nav.NEXT) {
            open(player, view.group, view.page + (action == Nav.PREV ? -1 : 1), view.returnPage);
        } else if (action == Nav.BACK) {
            open(player, null, view.returnPage, view.returnPage);
        } else if (action == Nav.SEARCH) {
            searches.remove(id);
            open(player, null, 0, 0);
        } else if (action == Nav.SORT) {
            Sort current = sorts.containsKey(id) ? sorts.get(id) : Sort.RECENT;
            sorts.put(id, Sort.values()[(current.ordinal() + 1) % Sort.values().length]);
            open(player, null, 0, 0);
        } else if (action instanceof Storage.Group) {
            Storage.Group group = (Storage.Group) action;
            if (!storage.contains(group)) {
                refreshAll();
                return;
            }
            switch (click) {
                case SHIFT_LEFT:
                    take(player, group, group.maxStack);
                    break;
                case RIGHT:
                    take(player, group, half(Math.min(group.maxStack, available(player, group))));
                    break;
                case SHIFT_RIGHT:
                    take(player, group, Long.MAX_VALUE);
                    break;
                default:
                    if (group.stacks() > 1) {
                        open(player, group, 0, view.page);
                    } else {
                        take(player, group, group.maxStack);
                    }
            }
        } else if (action instanceof StackRef) {
            StackRef ref = (StackRef) action;
            Storage.Group group = view.group;
            if (group == null || !storage.contains(group) || !group.lots.contains(ref.lot)) {
                refreshAll();
            } else if (!ref.lot.canTake(id, player.hasPermission("unlooted.admin"), System.currentTimeMillis())) {
                player.sendMessage(msg().get("reserved-stack"));
            } else {
                long amount = click == ClickType.RIGHT ? half(ref.amount) : ref.amount;
                long[] result = giveFromLot(player, group, ref.lot, Math.min(amount, ref.lot.amount));
                finishTake(player, group, result[0], result[1] > 0);
            }
        }
    }

    private long available(Player player, Storage.Group group) {
        return storage.available(group, player.getUniqueId(), player.hasPermission("unlooted.admin"), System.currentTimeMillis());
    }

    private static long half(long amount) {
        return Math.max(1, amount / 2);
    }

    /** Takes up to amount of a group, the player's own reserved items first, until their inventory is full. */
    private void take(Player player, Storage.Group group, long amount) {
        long now = System.currentTimeMillis();
        boolean bypass = player.hasPermission("unlooted.admin");
        long remaining = amount;
        long taken = 0;
        boolean full = false;
        boolean any = false;
        for (Storage.Lot lot : storage.ordered(group, player.getUniqueId(), now)) {
            if (remaining <= 0 || full) {
                break;
            }
            if (!lot.canTake(player.getUniqueId(), bypass, now)) {
                continue;
            }
            any = true;
            while (remaining > 0 && lot.amount > 0 && !full) {
                long[] result = giveFromLot(player, group, lot, Math.min(Math.min(remaining, lot.amount), group.maxStack));
                taken += result[0];
                remaining -= result[0];
                full = result[1] > 0 || result[0] == 0;
            }
        }
        if (!any) {
            player.sendMessage(msg().get("reserved-items"));
        }
        finishTake(player, group, taken, full);
    }

    /**
     * Moves up to one stack from a lot into the player's inventory, logging it. Whatever doesn't fit stays
     * in storage. Returns {taken, leftover}.
     */
    private long[] giveFromLot(Player player, Storage.Group group, Storage.Lot lot, long amount) {
        ItemStack item = group.template.clone();
        item.setAmount((int) Math.max(1, Math.min(amount, group.maxStack)));
        int wanted = item.getAmount();
        long leftover = 0;
        for (ItemStack left : player.getInventory().addItem(item).values()) {
            leftover += left.getAmount();
        }
        long taken = wanted - leftover;
        if (taken > 0) {
            String from = lot.owner == null ? "" : lot.owner.equals(player.getUniqueId()) ? " (own)" : " (reserved for " + lot.ownerName + ")";
            storage.take(group, lot, taken);
            plugin.markDirty();
            plugin.log(player.getName() + " took " + taken + " " + Tags.displayName(group.template, "unknown item") + from);
        }
        return new long[] {taken, leftover};
    }

    private void finishTake(Player player, Storage.Group group, long taken, boolean full) {
        if (full) {
            player.sendMessage(msg().get("inventory-full"));
        }
        if (taken > 0) {
            refreshAll();
        }
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
                resync(e.getWhoClicked());
                return;
            }
        }
    }

    // ------------------------------------------------------------------ guarding

    /**
     * Runs every tick. Sorting mods and similar tools can change menu slots without any click event.
     * Changed slots are restored from what was placed there. A foreign item found in the menu is only
     * given back when the player's inventory lost that item since the last tick, so a moved item comes
     * home and a copied one is deleted. Menu items that reached the player's inventory are removed.
     */
    void guard() {
        tick++;
        for (Player player : Bukkit.getOnlinePlayers()) {
            View view = openView(player);
            if (view == null) {
                continue;
            }
            Inventory inventory = view.inventory;
            PlayerInventory own = player.getInventory();
            long[] before = view.playerPrints;
            long[] now = prints(own);
            Map<Long, Long> lost = null;
            boolean changed = false;
            for (int i = 0; i < inventory.getSize(); i++) {
                ItemStack current = inventory.getItem(i);
                if (print(current) == view.prints[i]) {
                    continue;
                }
                changed = true;
                if (current != null && current.getAmount() > 0 && !isMenuItem(view, current)) {
                    if (lost == null) {
                        lost = lostKinds(before, now);
                    }
                    Long kind = kind(current);
                    Long missing = lost.get(kind);
                    if (missing != null && missing >= current.getAmount()) {
                        lost.put(kind, missing - current.getAmount());
                        giveBack(player, current.clone());
                    }
                }
                inventory.setItem(i, view.expected[i]);
            }
            if (changed || tick % 20 == 0) {
                purge(player, view);
                now = prints(own);
            }
            view.playerPrints = now;
        }
    }

    /** True for anything that is, or copies, an item this view placed in the menu. */
    private static boolean isMenuItem(View view, ItemStack item) {
        if (item == null || item.getAmount() <= 0) {
            return false;
        }
        if (Tags.isTagged(item)) {
            return true;
        }
        if (!view.placedKinds.contains(kind(item))) {
            return false;
        }
        for (ItemStack placed : view.placed) {
            if (placed != null && placed.isSimilar(item)) {
                return true;
            }
        }
        return false;
    }

    /** Removes menu items, tagged or copied from this view, from the player's inventory and cursor. */
    private static void purge(Player player, View view) {
        Tags.purge(player);
        if (view == null) {
            return;
        }
        if (isMenuItem(view, player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isMenuItem(view, contents[i])) {
                inventory.setItem(i, null);
            }
        }
    }

    /** For each item kind, how many fewer the player holds now than a tick ago. */
    private static Map<Long, Long> lostKinds(long[] before, long[] now) {
        Map<Long, Long> totals = new HashMap<Long, Long>();
        if (before == null) {
            return totals;
        }
        for (int i = 0; i < before.length; i++) {
            if (before[i] != 0) {
                Long kind = before[i] & ~(0xFFFFL << 16);
                totals.put(kind, (totals.containsKey(kind) ? totals.get(kind) : 0L) + ((before[i] >>> 16) & 0xFFFFL));
            }
        }
        for (int i = 0; i < now.length; i++) {
            if (now[i] != 0) {
                Long kind = now[i] & ~(0xFFFFL << 16);
                totals.put(kind, (totals.containsKey(kind) ? totals.get(kind) : 0L) - ((now[i] >>> 16) & 0xFFFFL));
            }
        }
        return totals;
    }

    private static long[] prints(PlayerInventory inventory) {
        ItemStack[] contents = inventory.getContents();
        long[] out = new long[contents.length];
        for (int i = 0; i < contents.length; i++) {
            out[i] = print(contents[i]);
        }
        return out;
    }

    private static void giveBack(Player player, ItemStack item) {
        for (ItemStack left : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItem(player.getLocation(), left);
        }
    }

    /** Type, amount and damage packed into one number: a cheap way to notice a slot changed. */
    @SuppressWarnings("deprecation")
    private static long print(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) {
            return 0;
        }
        return ((stack.getType().ordinal() + 1L) << 32) | ((stack.getAmount() & 0xFFFFL) << 16) | (stack.getDurability() & 0xFFFFL);
    }

    /** Type and damage only. */
    private static long kind(ItemStack stack) {
        return print(stack) & ~(0xFFFFL << 16);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        final HumanEntity player = e.getPlayer();
        final View view = viewOf(e.getInventory());
        if (view == null) {
            // Closing any other container: clear out menu items that might have been smuggled into it.
            Tags.purge(e.getInventory(), SCAN_LIMIT);
            return;
        }
        if (player instanceof Player) {
            purge((Player) player, view);
            // The cursor item goes back to the player after this event, so check again next tick.
            if (plugin.isEnabled()) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    purge((Player) player, view);
                    ((Player) player).updateInventory();
                });
            }
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
        UUID id = e.getPlayer().getUniqueId();
        lastClick.remove(id);
        searches.remove(id);
        sorts.remove(id);
    }

    // ------------------------------------------------------------------ text

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

    static String duration(long ms) {
        long seconds = Math.max(1, (ms + 999) / 1000);
        return seconds >= 60 ? (seconds / 60) + "m " + (seconds % 60) + "s" : seconds + "s";
    }
}
