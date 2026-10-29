package me.unlooted;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The storage's items in each sort order, kept up to date one change at a time: a changed item is
 * taken out and put back at its new place, so a take or a loot never re-sorts the whole list. Lists
 * are built on first use. Every order ends with the creation id, so it is complete and stable.
 */
final class SortedGroups implements Storage.Listener {

    enum Mode { RECENT, NAME, AMOUNT }

    private static final Comparator<Storage.Group> RECENT = (a, b) -> {
        int c = Long.compare(b.lastLooted(), a.lastLooted());
        return c != 0 ? c : Long.compare(b.id, a.id);
    };
    private static final Comparator<Storage.Group> NAME = (a, b) -> {
        int c = a.searchText.compareTo(b.searchText);
        return c != 0 ? c : Long.compare(a.id, b.id);
    };
    private static final Comparator<Storage.Group> AMOUNT = (a, b) -> {
        int c = Long.compare(b.total(), a.total());
        return c != 0 ? c : Long.compare(a.id, b.id);
    };

    private final Storage storage;
    private final Map<Mode, List<Storage.Group>> lists = new EnumMap<Mode, List<Storage.Group>>(Mode.class);

    SortedGroups(Storage storage) {
        this.storage = storage;
    }

    static Comparator<Storage.Group> comparator(Mode mode) {
        return mode == Mode.RECENT ? RECENT : mode == Mode.NAME ? NAME : AMOUNT;
    }

    /** The items in this order. Read only: callers must not change the list. */
    List<Storage.Group> get(Mode mode) {
        List<Storage.Group> list = lists.get(mode);
        if (list == null) {
            list = new ArrayList<Storage.Group>(storage.groups());
            Collections.sort(list, comparator(mode));
            lists.put(mode, list);
        }
        return Collections.unmodifiableList(list);
    }

    @Override
    public void added(Storage.Group group) {
        for (Map.Entry<Mode, List<Storage.Group>> entry : lists.entrySet()) {
            insert(entry.getValue(), group, comparator(entry.getKey()));
        }
    }

    @Override
    public void changed(Storage.Group group) {
        for (Map.Entry<Mode, List<Storage.Group>> entry : lists.entrySet()) {
            remove(entry.getValue(), group);
            insert(entry.getValue(), group, comparator(entry.getKey()));
        }
    }

    @Override
    public void removed(Storage.Group group) {
        for (List<Storage.Group> list : lists.values()) {
            remove(list, group);
        }
    }

    @Override
    public void reset() {
        lists.clear();
    }

    private static void insert(List<Storage.Group> list, Storage.Group group, Comparator<Storage.Group> order) {
        int at = Collections.binarySearch(list, group, order);
        list.add(at < 0 ? -at - 1 : at, group);
    }

    /** By identity, since the group's sort key has usually changed already. */
    private static void remove(List<Storage.Group> list, Storage.Group group) {
        for (Iterator<Storage.Group> it = list.iterator(); it.hasNext(); ) {
            if (it.next() == group) {
                it.remove();
                return;
            }
        }
    }
}
