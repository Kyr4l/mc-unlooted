package me.unlooted;

import org.bukkit.plugin.Plugin;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Records who took what from the storage. Every entry is printed to the server console, where the
 * server's own log keeps it, and the latest entries stay in memory for /unlooted log. No file is written.
 */
final class TakeLog {

    private static final int KEEP = 1000;

    private final Plugin plugin;
    private final Deque<String> recent = new ArrayDeque<String>();

    TakeLog(Plugin plugin) {
        this.plugin = plugin;
    }

    void add(String entry) {
        plugin.getLogger().info(entry);
        recent.addLast(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + entry);
        while (recent.size() > KEEP) {
            recent.removeFirst();
        }
    }

    /** Newest first, keeping only lines that contain filter (case-insensitive) when one is given. */
    List<String> latest(String filter, int max) {
        List<String> out = new ArrayList<String>();
        String f = filter == null ? null : filter.toLowerCase(Locale.ROOT);
        for (Iterator<String> it = recent.descendingIterator(); it.hasNext() && out.size() < max; ) {
            String line = it.next();
            if (f == null || line.toLowerCase(Locale.ROOT).contains(f)) {
                out.add(line);
            }
        }
        return out;
    }
}
