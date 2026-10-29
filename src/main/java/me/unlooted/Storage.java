package me.unlooted;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The shared storage. Items are grouped by exact item and data: each group keeps one template stack
 * and the total amount, split into lots by who may take them. Counts are longs, so storage is unlimited.
 */
final class Storage {

    /** Some amount of a group. Owned lots are reserved for their owner until reservedUntil. */
    static final class Lot {
        long amount;
        final UUID owner;
        final String ownerName;
        long reservedUntil;

        Lot(long amount, UUID owner, String ownerName, long reservedUntil) {
            this.amount = amount;
            this.owner = owner;
            this.ownerName = ownerName;
            this.reservedUntil = reservedUntil;
        }

        boolean reserved(long now) {
            return owner != null && reservedUntil > now;
        }

        boolean canTake(UUID viewer, boolean bypass, long now) {
            return bypass || !reserved(now) || owner.equals(viewer);
        }
    }

    /** One distinct item. The template has amount 1 and is never handed out directly, only cloned. */
    static final class Group {
        final ItemStack template;
        final Object fingerprint;
        /** The template's stored form, computed once: a codec string, or the stack for Bukkit serialization. */
        final Object stored;
        final int maxStack;
        final String bucket;
        final List<Lot> lots = new ArrayList<Lot>();

        Group(ItemStack template, Object fingerprint, Object stored, int maxStack, String bucket) {
            this.template = template;
            this.fingerprint = fingerprint;
            this.stored = stored;
            this.maxStack = maxStack;
            this.bucket = bucket;
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

    private final ItemCodec codec;
    private final List<Group> groups = new ArrayList<Group>();
    /** Groups by a cheap key (type and damage), so finding a stack's group never scans the whole storage. */
    private final Map<String, List<Group>> buckets = new HashMap<String, List<Group>>();
    /** Stored entries that couldn't be turned back into items (missing mod...). Kept as they are, never lost. */
    final List<Object> unreadable = new ArrayList<Object>();

    Storage(ItemCodec codec) {
        this.codec = codec;
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

    int indexOf(Group group) {
        return groups.indexOf(group);
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

    private Group find(ItemStack one, Object fingerprint) {
        List<Group> list = buckets.get(bucketOf(one));
        if (list != null) {
            for (Group group : list) {
                if (codec.sameData(group.template, group.fingerprint, one, fingerprint)) {
                    return group;
                }
            }
        }
        return null;
    }

    private Group newGroup(ItemStack one, Object fingerprint, Object stored) {
        Group group = new Group(one, fingerprint, stored, codec.maxStackSize(one), bucketOf(one));
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
    boolean add(ItemStack stack, UUID owner, String ownerName, long reservedUntil, int maxGroups, long now) {
        ItemStack one = stack.clone();
        one.setAmount(1);
        Object fingerprint = codec.fingerprint(one);
        Group group = find(one, fingerprint);
        if (group == null) {
            if (groups.size() >= maxGroups) {
                return false;
            }
            group = newGroup(one, fingerprint, codec.encode(one));
        }
        normalize(group, now);
        addLot(group, stack.getAmount(), owner, ownerName, reservedUntil);
        return true;
    }

    private static void addLot(Group group, long amount, UUID owner, String ownerName, long reservedUntil) {
        if (owner == null || reservedUntil <= 0) {
            owner = null;
            ownerName = null;
            reservedUntil = 0;
        }
        for (Lot lot : group.lots) {
            if (Objects.equals(lot.owner, owner)) {
                lot.amount += amount;
                lot.reservedUntil = Math.max(lot.reservedUntil, reservedUntil);
                return;
            }
        }
        group.lots.add(new Lot(amount, owner, ownerName, reservedUntil));
    }

    /** Folds lots whose reservation ran out into the group's shared lot. */
    void normalize(Group group, long now) {
        long expired = 0;
        for (Iterator<Lot> it = group.lots.iterator(); it.hasNext(); ) {
            Lot lot = it.next();
            if (lot.owner != null && lot.reservedUntil <= now) {
                expired += lot.amount;
                it.remove();
            }
        }
        if (expired > 0) {
            addLot(group, expired, null, null, 0);
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
        }
        if (group.lots.isEmpty()) {
            removeGroup(group);
        }
        return taken;
    }

    void clear() {
        groups.clear();
        buckets.clear();
        unreadable.clear();
    }

    // ------------------------------------------------------------------ persistence

    /** Plain data for data.yml: maps, lists, strings and numbers only (plus stacks under Bukkit serialization). */
    List<Object> snapshot() {
        List<Object> out = new ArrayList<Object>();
        for (Group group : groups) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("item", group.stored);
            List<Object> lots = new ArrayList<Object>();
            for (Lot lot : group.lots) {
                Map<String, Object> l = new LinkedHashMap<String, Object>();
                l.put("amount", lot.amount);
                if (lot.owner != null) {
                    l.put("owner", lot.owner.toString());
                    l.put("owner-name", lot.ownerName);
                    l.put("reserved-until", lot.reservedUntil);
                }
                lots.add(l);
            }
            entry.put("lots", lots);
            out.add(entry);
        }
        out.addAll(unreadable);
        return out;
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
                Object fingerprint = codec.fingerprint(one);
                Group group = find(one, fingerprint);
                if (group == null) {
                    group = newGroup(one, fingerprint, stored instanceof String ? stored : codec.encode(one));
                }
                Object lots = entry.get("lots");
                if (lots instanceof List) {
                    for (Object l : (List<?>) lots) {
                        if (l instanceof Map) {
                            loadLot(group, (Map<?, ?>) l);
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
                    add(stack, null, null, 0, Integer.MAX_VALUE, now);
                }
            }
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

    private static void loadLot(Group group, Map<?, ?> l) {
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
        addLot(group, ((Number) amount).longValue(), owner, name == null ? null : String.valueOf(name),
                until instanceof Number ? ((Number) until).longValue() : 0);
    }
}
