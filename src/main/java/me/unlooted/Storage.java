package me.unlooted;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The shared storage. Items are grouped by exact item and data: each group keeps one template stack
 * and the total amount, split into lots by who may take them. Counts are longs, so storage is unlimited.
 */
final class Storage {

    /** Where and when items were looted. */
    static final class Origin {
        final String world;
        final int x;
        final int y;
        final int z;
        final long time;

        Origin(String world, int x, int y, int z, long time) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.time = time;
        }
    }

    /** Some amount of a group. Owned lots are reserved for their owner until reservedUntil. */
    static final class Lot {
        long amount;
        final UUID owner;
        final String ownerName;
        long reservedUntil;
        /** The most recent loot that added to this lot. Its time drives expiry and the "Recent" sort. */
        Origin last;

        Lot(long amount, UUID owner, String ownerName, long reservedUntil, Origin last) {
            this.amount = amount;
            this.owner = owner;
            this.ownerName = ownerName;
            this.reservedUntil = reservedUntil;
            this.last = last;
        }

        boolean reserved(long now) {
            return owner != null && reservedUntil > now;
        }

        boolean canTake(UUID viewer, boolean bypass, long now) {
            return bypass || !reserved(now) || owner.equals(viewer);
        }
    }

    /**
     * One distinct item. The template has amount 1 and is never changed or handed out directly, only
     * cloned. Its raw data isn't kept separately: it is computed from the template when needed.
     */
    static final class Group {
        /** Order of creation: a stable tie-breaker for sorting. */
        final long id;
        final ItemStack template;
        /** The template's stored form, computed once: a codec string, or the stack for Bukkit serialization. */
        final Object stored;
        final int maxStack;
        final String bucket;
        /** Readable name, worked out once since reading it means copying the item's data. Null if unknown. */
        private final String name;
        /** Lower-case name and type, for search. */
        final String searchText;
        final List<Lot> lots = new ArrayList<Lot>();

        Group(long id, ItemStack template, Object stored, int maxStack, String bucket) {
            this.id = id;
            this.template = template;
            this.stored = stored;
            this.maxStack = maxStack;
            this.bucket = bucket;
            this.name = Tags.displayName(template, null);
            this.searchText = ((name == null ? "" : name) + " " + template.getType().name().replace('_', ' '))
                    .toLowerCase(java.util.Locale.ROOT);
        }

        /** The item's readable name, or unknown when it has none. */
        String name(String unknown) {
            return name == null ? unknown : name;
        }

        long lastLooted() {
            long last = 0;
            for (Lot lot : lots) {
                if (lot.last != null) {
                    last = Math.max(last, lot.last.time);
                }
            }
            return last;
        }

        long total() {
            long total = 0;
            for (Lot lot : lots) {
                total += lot.amount;
            }
            return total;
        }

        /** Number of stacks the group splits into when shown one stack per slot. */
        long stacks() {
            long stacks = 0;
            for (Lot lot : lots) {
                stacks += (lot.amount + maxStack - 1) / maxStack;
            }
            return stacks;
        }
    }

    /** Told about every change, so views can keep sorted lists up to date without re-sorting. */
    interface Listener {
        void added(Group group);

        void changed(Group group);

        void removed(Group group);

        /** Several changes at once, from a batch: changed groups were already known, added ones are new. */
        void changed(Collection<Group> changed, Collection<Group> added);

        /** Many changes at once: rebuild from scratch. */
        void reset();
    }

    private final ItemCodec codec;
    private final List<Group> groups = new ArrayList<Group>();
    /** Every lot that has an owner, by owner, so per-player counts never scan the whole storage. */
    private final Map<UUID, List<Lot>> ownedLots = new HashMap<UUID, List<Lot>>();
    private Listener listener;
    private long nextId;
    /** Groups by a cheap key (type and damage), so finding a stack's group never scans the whole storage. */
    private final Map<String, List<Group>> buckets = new HashMap<String, List<Group>>();
    /** Stored entries that couldn't be turned back into items (missing mod...). Kept as they are, never lost. */
    final List<Object> unreadable = new ArrayList<Object>();
    /** Goes up on every change, so views can cache sorted lists. */
    private long version;

    /**
     * Set while a batch of adds runs. Templates' raw data is kept for the batch only, so adding many
     * stacks of the same item computes it once, and the listener hears about all changes at the end.
     */
    private Map<Group, Object> batchPrints;
    private Set<Group> batchChanged;
    private Set<Group> batchAdded;

    Storage(ItemCodec codec) {
        this.codec = codec;
    }

    /** Starts a batch of adds. Every startBatch must be followed by endBatch. */
    void startBatch() {
        batchPrints = new IdentityHashMap<Group, Object>();
        batchChanged = Collections.newSetFromMap(new IdentityHashMap<Group, Boolean>());
        batchAdded = Collections.newSetFromMap(new IdentityHashMap<Group, Boolean>());
    }

    void endBatch() {
        Set<Group> changed = batchChanged;
        Set<Group> added = batchAdded;
        batchPrints = null;
        batchChanged = null;
        batchAdded = null;
        if (changed == null || listener == null || (changed.isEmpty() && added.isEmpty())) {
            return;
        }
        changed.removeAll(added);
        // Groups removed while the batch ran (none today) were already reported.
        List<Group> stillChanged = new ArrayList<Group>();
        for (Group group : changed) {
            if (contains(group)) {
                stillChanged.add(group);
            }
        }
        List<Group> stillAdded = new ArrayList<Group>();
        for (Group group : added) {
            if (contains(group)) {
                stillAdded.add(group);
            }
        }
        listener.changed(stillChanged, stillAdded);
    }

    List<Group> groups() {
        return Collections.unmodifiableList(groups);
    }

    boolean isEmpty() {
        return groups.isEmpty();
    }

    boolean contains(Group group) {
        List<Group> list = buckets.get(group.bucket);
        return list != null && list.contains(group);
    }

    long version() {
        return version;
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Items currently reserved for this player. */
    long reservedFor(UUID owner, long now) {
        long total = 0;
        List<Lot> lots = ownedLots.get(owner);
        if (lots != null) {
            for (Lot lot : lots) {
                if (lot.reserved(now)) {
                    total += lot.amount;
                }
            }
        }
        return total;
    }

    private void indexAdd(Lot lot) {
        if (lot.owner != null) {
            List<Lot> lots = ownedLots.get(lot.owner);
            if (lots == null) {
                lots = new ArrayList<Lot>();
                ownedLots.put(lot.owner, lots);
            }
            lots.add(lot);
        }
    }

    private void indexRemove(Lot lot) {
        if (lot.owner != null) {
            List<Lot> lots = ownedLots.get(lot.owner);
            if (lots != null) {
                for (Iterator<Lot> it = lots.iterator(); it.hasNext(); ) {
                    if (it.next() == lot) {
                        it.remove();
                        break;
                    }
                }
                if (lots.isEmpty()) {
                    ownedLots.remove(lot.owner);
                }
            }
        }
    }

    long totalItems() {
        long total = 0;
        for (Group group : groups) {
            total += group.total();
        }
        return total;
    }

    @SuppressWarnings("deprecation")
    private static String bucketOf(ItemStack stack) {
        return stack.getType().name() + ":" + stack.getDurability();
    }

    /**
     * The group holding exactly this item, or null. Bukkit's isSimilar picks candidates cheaply; for a
     * candidate, the full raw data of both sides is compared, so items differing only in data Bukkit
     * can't see (Forge capabilities...) never share a group. The new item's raw data is only computed
     * once a candidate exists.
     */
    private Group find(ItemStack one) {
        List<Group> list = buckets.get(bucketOf(one));
        if (list == null) {
            return null;
        }
        boolean computed = false;
        Object fingerprint = null;
        for (Group group : list) {
            if (!group.template.isSimilar(one)) {
                continue;
            }
            if (!computed) {
                fingerprint = codec.fingerprint(one);
                computed = true;
            }
            if (sameRawData(group, fingerprint)) {
                return group;
            }
        }
        return null;
    }

    /**
     * True when the full raw data of a group's template equals a candidate's, given the candidate's
     * fingerprint. The template's raw data is computed here instead of being kept in memory (outside a
     * batch): templates never change, so the result is the same every time. NBT compounds compare by
     * content, so key order doesn't matter. When raw NBT is unavailable, Bukkit's isSimilar decides.
     */
    private boolean sameRawData(Group group, Object candidate) {
        if (candidate == null) {
            return true;
        }
        Object stored;
        if (batchPrints != null && batchPrints.containsKey(group)) {
            stored = batchPrints.get(group);
        } else {
            stored = codec.fingerprint(group.template);
            if (batchPrints != null) {
                batchPrints.put(group, stored);
            }
        }
        return stored == null || stored.equals(candidate);
    }

    private Group newGroup(ItemStack one, Object stored) {
        Group group = new Group(nextId++, one, stored, codec.maxStackSize(one), bucketOf(one));
        groups.add(group);
        List<Group> list = buckets.get(group.bucket);
        if (list == null) {
            list = new ArrayList<Group>();
            buckets.put(group.bucket, list);
        }
        list.add(group);
        return group;
    }

    private void removeGroup(Group group) {
        groups.remove(group);
        List<Group> list = buckets.get(group.bucket);
        if (list != null) {
            list.remove(group);
            if (list.isEmpty()) {
                buckets.remove(group.bucket);
            }
        }
    }

    /**
     * Adds a whole stack. Returns false, adding nothing, when the stack needs a new group and the
     * storage already holds maxGroups groups.
     */
    boolean add(ItemStack stack, UUID owner, String ownerName, long reservedUntil, int maxGroups, long now, Origin origin) {
        ItemStack one = stack.clone();
        one.setAmount(1);
        Group group = find(one);
        boolean created = group == null;
        if (created) {
            if (groups.size() >= maxGroups) {
                return false;
            }
            group = newGroup(one, codec.encode(one));
        }
        normalize(group, now);
        addLot(group, stack.getAmount(), owner, ownerName, reservedUntil, origin);
        version++;
        if (batchChanged != null) {
            (created ? batchAdded : batchChanged).add(group);
        } else if (listener != null) {
            if (created) {
                listener.added(group);
            } else {
                listener.changed(group);
            }
        }
        return true;
    }

    private void addLot(Group group, long amount, UUID owner, String ownerName, long reservedUntil, Origin origin) {
        if (owner == null || reservedUntil <= 0) {
            owner = null;
            ownerName = null;
            reservedUntil = 0;
        }
        for (Lot lot : group.lots) {
            if (Objects.equals(lot.owner, owner)) {
                lot.amount += amount;
                lot.reservedUntil = Math.max(lot.reservedUntil, reservedUntil);
                if (origin != null && (lot.last == null || origin.time >= lot.last.time)) {
                    lot.last = origin;
                }
                return;
            }
        }
        Lot lot = new Lot(amount, owner, ownerName, reservedUntil, origin);
        group.lots.add(lot);
        indexAdd(lot);
    }

    /** Folds lots whose reservation ran out into the group's shared lot. */
    void normalize(Group group, long now) {
        long expired = 0;
        Origin latest = null;
        for (Iterator<Lot> it = group.lots.iterator(); it.hasNext(); ) {
            Lot lot = it.next();
            if (lot.owner != null && lot.reservedUntil <= now) {
                expired += lot.amount;
                if (lot.last != null && (latest == null || lot.last.time > latest.time)) {
                    latest = lot.last;
                }
                it.remove();
                indexRemove(lot);
            }
        }
        if (expired > 0) {
            addLot(group, expired, null, null, 0, latest);
        }
    }

    /** Amount of a group this viewer may take right now. */
    long available(Group group, UUID viewer, boolean bypass, long now) {
        long available = 0;
        for (Lot lot : group.lots) {
            if (lot.canTake(viewer, bypass, now)) {
                available += lot.amount;
            }
        }
        return available;
    }

    /** The group's lots in display order: the viewer's own reserved items, shared items, then others' reserved items. */
    List<Lot> ordered(Group group, UUID viewer, long now) {
        List<Lot> own = new ArrayList<Lot>();
        List<Lot> shared = new ArrayList<Lot>();
        List<Lot> others = new ArrayList<Lot>();
        for (Lot lot : group.lots) {
            if (!lot.reserved(now)) {
                shared.add(lot);
            } else if (lot.owner.equals(viewer)) {
                own.add(lot);
            } else {
                others.add(lot);
            }
        }
        own.addAll(shared);
        own.addAll(others);
        return own;
    }

    /** Removes up to amount from a lot, dropping the lot and group when they run empty. Returns what was removed. */
    long take(Group group, Lot lot, long amount) {
        long taken = Math.min(amount, lot.amount);
        lot.amount -= taken;
        if (lot.amount <= 0) {
            group.lots.remove(lot);
            indexRemove(lot);
        }
        boolean emptied = group.lots.isEmpty();
        if (emptied) {
            removeGroup(group);
        }
        version++;
        if (listener != null) {
            if (emptied) {
                listener.removed(group);
            } else {
                listener.changed(group);
            }
        }
        return taken;
    }

    /** Deletes lots last looted before cutoff. Returns the number of items deleted. */
    long expire(long cutoff) {
        long removed = 0;
        for (Group group : new ArrayList<Group>(groups)) {
            boolean touched = false;
            for (Iterator<Lot> it = group.lots.iterator(); it.hasNext(); ) {
                Lot lot = it.next();
                if (lot.last != null && lot.last.time < cutoff) {
                    removed += lot.amount;
                    it.remove();
                    indexRemove(lot);
                    touched = true;
                }
            }
            if (group.lots.isEmpty()) {
                removeGroup(group);
                if (listener != null) {
                    listener.removed(group);
                }
            } else if (touched && listener != null) {
                listener.changed(group);
            }
        }
        if (removed > 0) {
            version++;
        }
        return removed;
    }

    /** Deletes every item kind the filter accepts, with all its stacks. Returns the number of items deleted. */
    long removeIf(java.util.function.Predicate<Group> filter) {
        long removed = 0;
        for (Group group : new ArrayList<Group>(groups)) {
            if (!filter.test(group)) {
                continue;
            }
            for (Lot lot : group.lots) {
                removed += lot.amount;
                indexRemove(lot);
            }
            group.lots.clear();
            removeGroup(group);
            if (listener != null) {
                listener.removed(group);
            }
        }
        if (removed > 0) {
            version++;
        }
        return removed;
    }

    void clear() {
        groups.clear();
        buckets.clear();
        ownedLots.clear();
        unreadable.clear();
        version++;
        if (listener != null) {
            listener.reset();
        }
    }

    // ------------------------------------------------------------------ persistence

    /**
     * A copy of the storage taken at one moment, cheap to take on the main thread. Everything it refers
     * to never changes (stored forms, owners, origins), so it can be turned into data on any thread.
     */
    static final class Snapshot {
        private final Object[] stored;
        /** Lot count of each group, in order; the lots themselves follow one after another. */
        private final int[] lotCounts;
        private final long[] amounts;
        private final UUID[] owners;
        private final String[] ownerNames;
        private final long[] reservedUntil;
        private final Origin[] origins;
        private final Object[] unreadable;

        private Snapshot(List<Group> groups, List<Object> unreadable) {
            int lots = 0;
            for (Group group : groups) {
                lots += group.lots.size();
            }
            stored = new Object[groups.size()];
            lotCounts = new int[groups.size()];
            amounts = new long[lots];
            owners = new UUID[lots];
            ownerNames = new String[lots];
            reservedUntil = new long[lots];
            origins = new Origin[lots];
            int g = 0;
            int l = 0;
            for (Group group : groups) {
                stored[g] = group.stored;
                lotCounts[g] = group.lots.size();
                g++;
                for (Lot lot : group.lots) {
                    amounts[l] = lot.amount;
                    owners[l] = lot.owner;
                    ownerNames[l] = lot.ownerName;
                    reservedUntil[l] = lot.reservedUntil;
                    origins[l] = lot.last;
                    l++;
                }
            }
            this.unreadable = unreadable.toArray();
        }

        /** Plain data for data.yml: maps, lists, strings and numbers only (plus stacks under Bukkit serialization). */
        List<Object> toData() {
            List<Object> out = new ArrayList<Object>(stored.length + unreadable.length);
            int l = 0;
            for (int g = 0; g < stored.length; g++) {
                Map<String, Object> entry = new LinkedHashMap<String, Object>();
                entry.put("item", stored[g]);
                List<Object> lots = new ArrayList<Object>(lotCounts[g]);
                for (int end = l + lotCounts[g]; l < end; l++) {
                    Map<String, Object> lot = new LinkedHashMap<String, Object>();
                    lot.put("amount", amounts[l]);
                    if (owners[l] != null) {
                        lot.put("owner", owners[l].toString());
                        lot.put("owner-name", ownerNames[l]);
                        lot.put("reserved-until", reservedUntil[l]);
                    }
                    Origin last = origins[l];
                    if (last != null) {
                        lot.put("looted-at", last.time);
                        if (last.world != null) {
                            lot.put("world", last.world);
                            lot.put("x", last.x);
                            lot.put("y", last.y);
                            lot.put("z", last.z);
                        }
                    }
                    lots.add(lot);
                }
                entry.put("lots", lots);
                out.add(entry);
            }
            Collections.addAll(out, unreadable);
            return out;
        }
    }

    Snapshot snapshot() {
        return new Snapshot(groups, unreadable);
    }

    /** Loads groups saved by 0.3.0+, and loose item lists saved by earlier versions. */
    void load(List<?> savedGroups, List<?> legacyItems, long now) {
        if (savedGroups != null) {
            for (Object o : savedGroups) {
                if (o == null) {
                    continue;
                }
                if (!(o instanceof Map)) {
                    unreadable.add(o);
                    continue;
                }
                Map<?, ?> entry = (Map<?, ?>) o;
                Object stored = entry.get("item");
                ItemStack one = decode(stored);
                if (one == null) {
                    unreadable.add(o);
                    continue;
                }
                one = one.clone();
                one.setAmount(1);
                Group group = find(one);
                if (group == null) {
                    group = newGroup(one, stored instanceof String ? stored : codec.encode(one));
                }
                Object lots = entry.get("lots");
                if (lots instanceof List) {
                    for (Object l : (List<?>) lots) {
                        if (l instanceof Map) {
                            loadLot(group, (Map<?, ?>) l, now);
                        }
                    }
                }
                if (group.lots.isEmpty()) {
                    removeGroup(group);
                }
            }
        }
        if (legacyItems != null) {
            for (Object o : legacyItems) {
                if (o == null) {
                    continue;
                }
                ItemStack stack = decode(o);
                if (stack == null) {
                    unreadable.add(o);
                } else if (!(o instanceof ItemStack && stack.getType() == Material.AIR)) {
                    // Bukkit-format AIR entries from 0.1.0 were already lost when saved: skip them.
                    add(stack, null, null, 0, Integer.MAX_VALUE, now, new Origin(null, 0, 0, 0, now));
                }
            }
        }
        version++;
        if (listener != null) {
            listener.reset();
        }
    }

    private ItemStack decode(Object stored) {
        try {
            ItemStack stack = codec.decode(stored);
            return stack == null || stack.getAmount() <= 0 ? null : stack;
        } catch (Exception e) {
            return null;
        }
    }

    private void loadLot(Group group, Map<?, ?> l, long now) {
        Object amount = l.get("amount");
        if (!(amount instanceof Number) || ((Number) amount).longValue() <= 0) {
            return;
        }
        UUID owner = null;
        try {
            owner = l.get("owner") == null ? null : UUID.fromString(String.valueOf(l.get("owner")));
        } catch (IllegalArgumentException ignored) {
            // Unknown owner: the lot becomes shared.
        }
        Object until = l.get("reserved-until");
        Object name = l.get("owner-name");
        // Lots saved before 0.4.0 have no loot time: count them as looted now, so expiry doesn't delete them at once.
        Object time = l.get("looted-at");
        Object world = l.get("world");
        Origin origin = new Origin(world == null ? null : String.valueOf(world), intOf(l.get("x")), intOf(l.get("y")),
                intOf(l.get("z")), time instanceof Number ? ((Number) time).longValue() : now);
        addLot(group, ((Number) amount).longValue(), owner, name == null ? null : String.valueOf(name),
                until instanceof Number ? ((Number) until).longValue() : 0, origin);
    }

    private static int intOf(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }
}
