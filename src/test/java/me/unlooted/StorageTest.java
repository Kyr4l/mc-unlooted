package me.unlooted;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Storage model tests. They run without a server, using plain Bukkit item stacks. Bukkit needs a
 * server object to compare items, so a stand-in answers just that.
 */
public class StorageTest {

    private static final long NOW = 1_000_000L;
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private Storage storage;
    private ItemCodec codec;

    @BeforeClass
    public static void fakeServer() {
        if (Bukkit.getServer() != null) {
            return;
        }
        final ItemFactory factory = (ItemFactory) Proxy.newProxyInstance(StorageTest.class.getClassLoader(),
                new Class<?>[] {ItemFactory.class},
                (proxy, m, args) -> m.getName().equals("equals") && args != null && args.length == 2 ? args[0] == args[1] : null);
        Server server = (Server) Proxy.newProxyInstance(StorageTest.class.getClassLoader(), new Class<?>[] {Server.class},
                (proxy, m, args) -> {
                    switch (m.getName()) {
                        case "getItemFactory":
                            return factory;
                        case "getLogger":
                            return Logger.getLogger("test-server");
                        default:
                            return m.getReturnType() == String.class ? "test" : null;
                    }
                });
        Bukkit.setServer(server);
    }

    @Before
    public void setUp() {
        Logger quiet = Logger.getLogger("test");
        // The stand-in server has no lossless item format, so the codec's fallback warning is expected here.
        quiet.setLevel(java.util.logging.Level.OFF);
        codec = new ItemCodec(quiet);
        storage = new Storage(codec);
    }

    private static Storage.Origin at(long time) {
        return new Storage.Origin("world", 10, 64, -5, time);
    }

    private void add(Material type, int amount, UUID owner, long reservedUntil) {
        assertTrue(storage.add(new ItemStack(type, amount), owner, owner == null ? null : "Alice", reservedUntil,
                Integer.MAX_VALUE, NOW, at(NOW)));
    }

    @Test
    public void groupsIdenticalItemsAndSplitsByOwner() {
        add(Material.COBBLESTONE, 64, null, 0);
        add(Material.COBBLESTONE, 30, null, 0);
        add(Material.COBBLESTONE, 10, ALICE, NOW + 600_000);
        add(Material.DIRT, 5, null, 0);
        assertEquals(2, storage.groups().size());
        Storage.Group cobble = storage.groups().get(0);
        assertEquals(104, cobble.total());
        assertEquals(2, cobble.lots.size());
        assertEquals(3, cobble.stacks());
    }

    @Test
    public void differentDamageIsADifferentItem() {
        storage.add(new ItemStack(Material.DIAMOND_SWORD, 1, (short) 5), null, null, 0, Integer.MAX_VALUE, NOW, at(NOW));
        storage.add(new ItemStack(Material.DIAMOND_SWORD, 1, (short) 9), null, null, 0, Integer.MAX_VALUE, NOW, at(NOW));
        storage.add(new ItemStack(Material.DIAMOND_SWORD, 1, (short) 5), null, null, 0, Integer.MAX_VALUE, NOW, at(NOW));
        assertEquals(2, storage.groups().size());
        assertEquals(2, storage.groups().get(0).total());
    }

    @Test
    public void reservationsLimitWhoCanTake() {
        add(Material.COBBLESTONE, 94, null, 0);
        add(Material.COBBLESTONE, 10, ALICE, NOW + 600_000);
        Storage.Group cobble = storage.groups().get(0);
        assertEquals(94, storage.available(cobble, BOB, false, NOW));
        assertEquals(104, storage.available(cobble, ALICE, false, NOW));
        assertEquals(104, storage.available(cobble, BOB, true, NOW));
        assertEquals(ALICE, storage.ordered(cobble, ALICE, NOW).get(0).owner);
        assertEquals(ALICE, storage.ordered(cobble, BOB, NOW).get(1).owner);
        assertEquals(10, storage.reservedFor(ALICE, NOW));
        assertEquals(0, storage.reservedFor(BOB, NOW));
    }

    @Test
    public void expiredReservationsBecomeShared() {
        add(Material.COBBLESTONE, 94, null, 0);
        add(Material.COBBLESTONE, 10, ALICE, NOW + 600_000);
        Storage.Group cobble = storage.groups().get(0);
        storage.normalize(cobble, NOW + 600_001);
        assertEquals(1, cobble.lots.size());
        assertEquals(104, storage.available(cobble, BOB, false, NOW + 600_001));
    }

    @Test
    public void takingRemovesItemsAndEmptyGroups() {
        add(Material.COBBLESTONE, 104, null, 0);
        Storage.Group cobble = storage.groups().get(0);
        long version = storage.version();
        assertEquals(64, storage.take(cobble, cobble.lots.get(0), 64));
        assertTrue(storage.version() > version);
        assertEquals(40, storage.take(cobble, cobble.lots.get(0), 999));
        assertFalse(storage.contains(cobble));
        assertTrue(storage.isEmpty());
    }

    @Test
    public void pageCapOnlyBlocksNewKinds() {
        assertTrue(storage.add(new ItemStack(Material.STONE, 5), null, null, 0, 1, NOW, at(NOW)));
        assertFalse(storage.add(new ItemStack(Material.DIRT, 5), null, null, 0, 1, NOW, at(NOW)));
        assertTrue(storage.add(new ItemStack(Material.STONE, 5), null, null, 0, 1, NOW, at(NOW)));
        assertEquals(10, storage.totalItems());
    }

    @Test
    public void expiryDeletesOnlyOldLots() {
        storage.add(new ItemStack(Material.STONE, 5), null, null, 0, Integer.MAX_VALUE, NOW, at(NOW - 10_000));
        storage.add(new ItemStack(Material.DIRT, 7), null, null, 0, Integer.MAX_VALUE, NOW, at(NOW));
        assertEquals(5, storage.expire(NOW - 5_000));
        assertEquals(1, storage.groups().size());
        assertEquals(7, storage.totalItems());
    }

    @Test
    public void latestOriginIsKept() {
        storage.add(new ItemStack(Material.STONE, 5), null, null, 0, Integer.MAX_VALUE, NOW, new Storage.Origin("a", 1, 2, 3, NOW));
        storage.add(new ItemStack(Material.STONE, 5), null, null, 0, Integer.MAX_VALUE, NOW, new Storage.Origin("b", 4, 5, 6, NOW + 1));
        Storage.Lot lot = storage.groups().get(0).lots.get(0);
        assertEquals("b", lot.last.world);
        assertEquals(NOW + 1, storage.groups().get(0).lastLooted());
    }

    @Test
    public void saveFileRoundTripKeepsEverything() throws Exception {
        add(Material.COBBLESTONE, 104, null, 0);
        add(Material.ARROW, 7, BOB, NOW + 5000);
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("groups", storage.snapshot());
        YamlConfiguration back = new YamlConfiguration();
        back.loadFromString(yml.saveToString());
        Storage loaded = new Storage(codec);
        loaded.load(back.getList("groups"), null, NOW);
        assertEquals(storage.groups().size(), loaded.groups().size());
        assertEquals(storage.totalItems(), loaded.totalItems());
        Storage.Lot arrows = loaded.groups().get(1).lots.get(0);
        assertEquals(BOB, arrows.owner);
        assertEquals(NOW + 5000, arrows.reservedUntil);
        assertEquals("world", arrows.last.world);
        assertEquals(-5, arrows.last.z);
    }

    @Test
    public void oldItemListIsConverted() {
        List<Object> legacy = new ArrayList<Object>(Arrays.asList(new ItemStack(Material.STONE, 64), new ItemStack(Material.STONE, 64),
                new ItemStack(Material.AIR, 1), "nbt:not-decodable-here", null));
        storage.load(null, legacy, NOW);
        assertEquals(1, storage.groups().size());
        assertEquals(128, storage.totalItems());
        assertEquals(1, storage.unreadable.size());
        assertTrue(storage.snapshot().contains("nbt:not-decodable-here"));
    }

    /**
     * A codec whose raw data includes something Bukkit's isSimilar ignores (here the legacy data byte),
     * standing in for data such as Forge capabilities.
     */
    private static ItemCodec hiddenDataCodec() {
        Logger quiet = Logger.getLogger("test-hidden");
        quiet.setLevel(java.util.logging.Level.OFF);
        return new ItemCodec(quiet) {
            @Override
            @SuppressWarnings("deprecation")
            Object fingerprint(ItemStack one) {
                return one.getData() == null ? null : Byte.valueOf(one.getData().getData());
            }
        };
    }

    @SuppressWarnings("deprecation")
    private static ItemStack stoneWithHiddenData(int amount, int hidden) {
        ItemStack stack = new ItemStack(Material.STONE, amount);
        stack.setData(new org.bukkit.material.MaterialData(Material.STONE, (byte) hidden));
        return stack;
    }

    @Test
    public void itemsDifferingOnlyInHiddenDataNeverShareAnEntry() {
        Storage hidden = new Storage(hiddenDataCodec());
        hidden.add(stoneWithHiddenData(5, 1), null, null, 0, Integer.MAX_VALUE, NOW, at(NOW));
        hidden.add(stoneWithHiddenData(7, 2), null, null, 0, Integer.MAX_VALUE, NOW, at(NOW));
        hidden.add(stoneWithHiddenData(3, 1), null, null, 0, Integer.MAX_VALUE, NOW, at(NOW));
        assertEquals(2, hidden.groups().size());
        assertEquals(8, hidden.groups().get(0).total());
        assertEquals(7, hidden.groups().get(1).total());
        assertEquals(1, hidden.groups().get(0).template.getData().getData());
        assertEquals(2, hidden.groups().get(1).template.getData().getData());
    }

    @Test
    public void storedTemplatesAreNeverChangedByMatching() {
        Storage hidden = new Storage(hiddenDataCodec());
        hidden.add(stoneWithHiddenData(5, 1), null, null, 0, Integer.MAX_VALUE, NOW, at(NOW));
        ItemStack before = hidden.groups().get(0).template.clone();
        for (int i = 0; i < 20; i++) {
            hidden.add(stoneWithHiddenData(1, i % 3), null, null, 0, Integer.MAX_VALUE, NOW, at(NOW));
        }
        ItemStack after = hidden.groups().get(0).template;
        assertTrue(before.isSimilar(after));
        assertEquals(before.getAmount(), after.getAmount());
        assertEquals(before.getData().getData(), after.getData().getData());
        assertEquals(5 + 7, hidden.groups().get(0).total());
        assertEquals(5 + 20, hidden.totalItems());
    }

    @Test
    public void messagesFillPlaceholdersAndColors() {
        Messages messages = new Messages();
        messages.load(null);
        assertEquals("§eAvailable: §f1,234", messages.get("available", "amount", Menu.number(1234)));
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("messages.available", "&aStock: {amount}");
        messages.load(yml.getConfigurationSection("messages"));
        assertEquals("§aStock: 5", messages.get("available", "amount", 5));
    }
}
