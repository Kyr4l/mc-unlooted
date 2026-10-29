package me.unlooted;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

/**
 * Records who took what from the storage. The latest entries stay in memory for /unlooted log, and every
 * entry is appended to takes.log in the background, in order.
 */
final class TakeLog {

    private static final int KEEP = 1000;

    private final Plugin plugin;
    private final File file;
    private final Deque<String> recent = new ArrayDeque<String>();
    private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<String>();
    private final Object fileLock = new Object();

    TakeLog(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "takes.log");
    }

    void add(String entry) {
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + entry;
        recent.addLast(line);
        while (recent.size() > KEEP) {
            recent.removeFirst();
        }
        pending.add(line);
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::flush);
        }
    }

    /** Newest first, keeping only lines that contain filter (case-insensitive) when one is given. */
    List<String> latest(String filter, int max) {
        List<String> out = new ArrayList<String>();
        String f = filter == null ? null : filter.toLowerCase(Locale.ROOT);
        for (java.util.Iterator<String> it = recent.descendingIterator(); it.hasNext() && out.size() < max; ) {
            String line = it.next();
            if (f == null || line.toLowerCase(Locale.ROOT).contains(f)) {
                out.add(line);
            }
        }
        return out;
    }

    void flush() {
        synchronized (fileLock) {
            List<String> lines = new ArrayList<String>();
            for (String line; (line = pending.poll()) != null; ) {
                lines.add(line);
            }
            if (lines.isEmpty()) {
                return;
            }
            try {
                plugin.getDataFolder().mkdirs();
                Files.write(file.toPath(), lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                plugin.getLogger().log(Level.WARNING, "Could not write takes.log", ex);
            }
        }
    }
}
