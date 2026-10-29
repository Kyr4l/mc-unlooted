package me.unlooted;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns item stacks into strings and back without losing any data, including data added by mods.
 *
 * Bukkit's own serialization goes through Material and ItemMeta, which only know vanilla items
 * and vanilla properties. This codec stores the server's raw item data instead:
 *
 * 1. Paper's ItemStack#serializeAsBytes when the server has it (modern Paper and its forks).
 * 2. Otherwise the raw NBT of the server's internal item stack, which includes Forge capability data
 *    on hybrid servers. The internal classes are found by their shape rather than by name, so this
 *    works on CraftBukkit, Spigot and Forge hybrids alike.
 * 3. Bukkit serialization as a last resort, with a warning.
 *
 * Every format is checked with a round trip at startup before it is used.
 */
final class ItemCodec {

    private static final String PAPER = "paper:";
    private static final String NBT = "nbt:";

    private final Logger log;

    private Method paperSerialize;
    private Method paperDeserialize;
    private boolean paperOk;

    private Method asNmsCopy;
    private Method asCraftMirror;
    private Method nmsSave;
    private Method nmsLoadStatic;
    private Constructor<?> nmsLoadCtor;
    private Constructor<?> compoundCtor;
    private Method readCompressed;
    private Method writeCompressed;
    private boolean nbtOk;

    /** The server's own, stack-aware max stack size (Forge's getItemStackLimit, item components...). */
    private Method nmsMaxStack;

    ItemCodec(Logger log) {
        this.log = log;
        try {
            paperSerialize = ItemStack.class.getMethod("serializeAsBytes");
            paperDeserialize = ItemStack.class.getMethod("deserializeBytes", byte[].class);
            paperOk = selfTest(PAPER);
        } catch (Throwable ignored) {
            paperOk = false;
        }
        try {
            findNbtAccess();
            nbtOk = selfTest(NBT);
        } catch (Throwable t) {
            nbtOk = false;
            if (!paperOk) {
                log.log(Level.FINE, "Raw NBT item storage unavailable", t);
            }
        }
        try {
            findMaxStack();
        } catch (Throwable ignored) {
            nmsMaxStack = null;
        }
        if (paperOk) {
            log.info("Storing items in Paper's lossless item format.");
        } else if (nbtOk) {
            log.info("Storing items as raw NBT, so modded item data is kept.");
        } else {
            log.warning("No lossless item format found on this server. Falling back to Bukkit serialization,"
                    + " which can lose data of modded items across restarts.");
        }
    }

    boolean isLossless() {
        return paperOk || nbtOk;
    }

    /** Returns a String in a lossless format, or the stack itself for Bukkit serialization. */
    Object encode(ItemStack stack) {
        if (paperOk) {
            try {
                return encode(stack, PAPER);
            } catch (Throwable t) {
                log.log(Level.WARNING, "Paper item serialization failed, trying the next format", t);
            }
        }
        if (nbtOk) {
            try {
                return encode(stack, NBT);
            } catch (Throwable t) {
                log.log(Level.WARNING, "NBT item serialization failed, using Bukkit serialization", t);
            }
        }
        return stack;
    }

    /**
     * Returns the stored stack, or null when the entry cannot be turned back into a real item
     * (for example because the mod that added it is missing). Callers keep such entries as they are.
     */
    ItemStack decode(Object stored) throws Exception {
        if (stored instanceof ItemStack) {
            return (ItemStack) stored;
        }
        if (!(stored instanceof String)) {
            return null;
        }
        String s = (String) stored;
        ItemStack result;
        if (s.startsWith(PAPER) && paperDeserialize != null) {
            result = (ItemStack) paperDeserialize.invoke(null, (Object) Base64.getDecoder().decode(s.substring(PAPER.length())));
        } else if (s.startsWith(NBT) && readCompressed != null) {
            byte[] bytes = Base64.getDecoder().decode(s.substring(NBT.length()));
            Object tag = readCompressed.invoke(null, new ByteArrayInputStream(bytes));
            Object nms = nmsLoadStatic != null ? nmsLoadStatic.invoke(null, tag) : nmsLoadCtor.newInstance(tag);
            result = (ItemStack) asCraftMirror.invoke(null, nms);
        } else {
            return null;
        }
        return result == null || result.getAmount() <= 0 ? null : result;
    }

    /**
     * The item's complete raw data as a comparable object, or null when raw NBT is unavailable.
     * Pass a stack with amount 1 so the amount doesn't take part in comparisons.
     */
    Object fingerprint(ItemStack one) {
        if (!nbtOk) {
            return null;
        }
        try {
            return nmsSave.invoke(asNmsCopy.invoke(null, one), compoundCtor.newInstance());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * True when two stacks hold the same item with the same data. Bukkit's isSimilar misses data it
     * doesn't know, such as Forge capabilities, so the raw NBT is compared too when it is available.
     * NBT compounds compare by content, so key order doesn't matter.
     */
    boolean sameData(ItemStack a, Object fingerprintA, ItemStack b, Object fingerprintB) {
        if (!a.isSimilar(b)) {
            return false;
        }
        if (fingerprintA != null && fingerprintB != null) {
            return fingerprintA.equals(fingerprintB);
        }
        return true;
    }

    /** How many of this item fit in one stack, asking the server itself when possible. */
    int maxStackSize(ItemStack stack) {
        if (nmsMaxStack != null && asNmsCopy != null) {
            try {
                int max = (Integer) nmsMaxStack.invoke(asNmsCopy.invoke(null, stack));
                if (max > 0) {
                    return max;
                }
            } catch (Throwable ignored) {
                // Fall back to Bukkit below.
            }
        }
        return Math.max(1, stack.getMaxStackSize());
    }

    // ------------------------------------------------------------------ internals

    private String encode(ItemStack stack, String format) throws Exception {
        byte[] bytes;
        if (format.equals(PAPER)) {
            bytes = (byte[]) paperSerialize.invoke(stack);
        } else {
            Object nms = asNmsCopy.invoke(null, stack);
            Object tag = nmsSave.invoke(nms, compoundCtor.newInstance());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeCompressed.invoke(null, tag, out);
            bytes = out.toByteArray();
        }
        return format + Base64.getEncoder().encodeToString(bytes);
    }

    private boolean selfTest(String format) {
        try {
            ItemStack probe = new ItemStack(Material.STONE, 3);
            ItemStack back = decode(encode(probe, format));
            return back != null && back.getAmount() == 3 && back.isSimilar(probe);
        } catch (Throwable t) {
            return false;
        }
    }

    private void findNbtAccess() throws Exception {
        String craftPackage = Bukkit.getServer().getClass().getPackage().getName();
        Class<?> craftItemStack = Class.forName(craftPackage + ".inventory.CraftItemStack");
        asNmsCopy = craftItemStack.getMethod("asNMSCopy", ItemStack.class);
        Class<?> nmsItem = asNmsCopy.getReturnType();
        asCraftMirror = craftItemStack.getMethod("asCraftMirror", nmsItem);

        // The save method takes a compound tag and returns it: writeToNBT / save.
        for (Method m : nmsItem.getMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (!Modifier.isStatic(m.getModifiers()) && params.length == 1
                    && m.getReturnType() == params[0] && isCompound(params[0])) {
                nmsSave = m;
                break;
            }
        }
        if (nmsSave == null) {
            throw new IllegalStateException("No item save method");
        }
        Class<?> compound = nmsSave.getReturnType();
        compoundCtor = compound.getConstructor();

        // Loading: a static factory (1.13+) or a constructor taking the tag (1.12).
        for (Method m : nmsItem.getMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (Modifier.isStatic(m.getModifiers()) && params.length == 1
                    && params[0] == compound && m.getReturnType() == nmsItem) {
                nmsLoadStatic = m;
                break;
            }
        }
        if (nmsLoadStatic == null) {
            nmsLoadCtor = nmsItem.getDeclaredConstructor(compound);
            nmsLoadCtor.setAccessible(true);
        }

        // Compressed NBT streams live next to the compound class under one of these names.
        String nbtPackage = compound.getPackage().getName();
        for (String name : new String[] {"NBTCompressedStreamTools", "CompressedStreamTools", "NbtIo"}) {
            Class<?> tools;
            try {
                tools = Class.forName(nbtPackage + "." + name);
            } catch (ClassNotFoundException e) {
                continue;
            }
            for (Method m : tools.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 1 && params[0] == InputStream.class && m.getReturnType() == compound) {
                    readCompressed = m;
                } else if (params.length == 2 && params[0] == compound && params[1] == OutputStream.class) {
                    writeCompressed = m;
                }
            }
            if (readCompressed != null && writeCompressed != null) {
                return;
            }
        }
        throw new IllegalStateException("No compressed NBT reader and writer");
    }

    /**
     * The internal stack's max size method. On Forge hybrids it is patched to ask the item, so mods that
     * limit stacks by their data are respected. Names: Spigot and Mojang mappings, then Forge's SRG name.
     */
    private void findMaxStack() throws Exception {
        String craftPackage = Bukkit.getServer().getClass().getPackage().getName();
        Class<?> craftItemStack = Class.forName(craftPackage + ".inventory.CraftItemStack");
        if (asNmsCopy == null) {
            asNmsCopy = craftItemStack.getMethod("asNMSCopy", ItemStack.class);
        }
        Class<?> nmsItem = asNmsCopy.getReturnType();
        for (String name : new String[] {"getMaxStackSize", "func_77976_d"}) {
            try {
                Method m = nmsItem.getMethod(name);
                if (m.getReturnType() == int.class) {
                    nmsMaxStack = m;
                    if (maxStackSize(new ItemStack(Material.STONE)) == 64) {
                        return;
                    }
                    nmsMaxStack = null;
                }
            } catch (NoSuchMethodException ignored) {
                // Try the next name.
            }
        }
    }

    private static boolean isCompound(Class<?> type) {
        if (type.isPrimitive() || !type.getSimpleName().contains("Compound")) {
            return false;
        }
        try {
            type.getConstructor();
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
