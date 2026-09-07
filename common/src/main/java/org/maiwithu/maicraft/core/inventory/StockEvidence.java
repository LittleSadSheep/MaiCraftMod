package org.maiwithu.maicraft.core.inventory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;
import org.maiwithu.maicraft.core.integration.create.CreateStockObservation;

/** One recently observed external stock source; never adds networks together or includes inventory. */
public final class StockEvidence {
    public static final long MAX_AGE_TICKS = 1200;
    public enum Source { AE2, CREATE, CONTAINER }
    public record Snapshot(Source source, Map<ResourceLocation, Long> stored,
                           Set<ResourceLocation> craftable, long observedGameTick) {
        public Snapshot { stored = Map.copyOf(stored); craftable = Set.copyOf(craftable); }
        public long storedCount(ResourceLocation item) { return stored.getOrDefault(item, 0L); }
        /** Only AE2 is currently connected to the tool acquisition source. */
        public boolean supportsToolSupply() { return source == Source.AE2; }
    }

    private static final Cache CACHE = new Cache();
    private static AbstractContainerMenu synchronizedMenu;
    private static LocalPlayer synchronizedPlayer;
    private StockEvidence() {}

    /** Called after a complete server container-content packet, never from a client block entity. */
    public static void containerSynchronized(AbstractContainerMenu menu) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.containerMenu == menu) {
            synchronizedMenu = menu;
            synchronizedPlayer = player;
        }
    }

    /** Exact menu-instance evidence; an unsynchronized freshly constructed GUI is not empty stock. */
    public static boolean isContainerSynchronized(LocalPlayer player, AbstractContainerMenu menu) {
        return player != null && synchronizedPlayer == player && synchronizedMenu == menu && player.containerMenu == menu;
    }

    /** Passive client-tick observation. This opens no screens and submits no packets. */
    public static void observe(LocalPlayer player) {
        if (player == null || player.clientLevel != Minecraft.getInstance().level) {
            CACHE.clear();
            synchronizedMenu = null;
            synchronizedPlayer = null;
            CreateStockObservation.reset();
            return;
        }
        CACHE.latest(player, player.clientLevel, inventory(player), player.level().getGameTime());
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == player.inventoryMenu || !MenuVisibility.matches(Minecraft.getInstance(), menu)) {
            CreateStockObservation.reset();
            return;
        }
        long tick = player.level().getGameTime();
        if (tick % 20 != 0) return; // A large network repository is sampled once per second, not every frame.
        Optional<Snapshot> observation;
        if (CreateStockObservation.supports(menu)) {
            observation = CreateStockObservation.observe(menu, tick);
        } else {
            CreateStockObservation.reset();
            observation = Ae2ResourceSupply.observeOpenStock(menu, tick);
            if (observation.isEmpty() && !menu.getClass().getName().startsWith("appeng.")
                    && synchronizedPlayer == player && synchronizedMenu == menu)
                observation = containerStock(player, menu, tick);
        }
        observation.ifPresent(stock -> CACHE.record(player, player.clientLevel, inventory(player), stock));
    }

    /** Planning hint only: carried gains debit the matching stock; actual supply must revalidate it. */
    public static Optional<Snapshot> latest(LocalPlayer player) {
        if (player == null || player.clientLevel != Minecraft.getInstance().level) return Optional.empty();
        return CACHE.latest(player, player.clientLevel, inventory(player), player.level().getGameTime());
    }

    private static Optional<Snapshot> containerStock(LocalPlayer player, AbstractContainerMenu menu, long tick) {
        Container backing = null;
        Set<Integer> playerSlots = new HashSet<>(), externalSlots = new HashSet<>();
        Map<ResourceLocation, Long> counts = new HashMap<>();
        for (Slot slot : menu.slots) {
            int index = slot.getContainerSlot();
            if (slot.container == player.getInventory()) {
                if (index >= 0 && index < 36) playerSlots.add(index);
                continue;
            }
            // Same proof used for generic container transfer: one backing and ordinary real slots.
            if (slot.getClass() != Slot.class || index < 0 || index >= slot.container.getContainerSize()
                    || !externalSlots.add(index) || (backing != null && backing != slot.container))
                return Optional.empty();
            backing = slot.container;
            add(counts, slot.getItem(), slot.getItem().getCount());
        }
        return playerSlots.size() == 36 && backing != null
                ? Optional.of(new Snapshot(Source.CONTAINER, counts, Set.of(), tick)) : Optional.empty();
    }

    private static Map<ResourceLocation, Long> inventory(LocalPlayer player) {
        Map<ResourceLocation, Long> counts = new HashMap<>();
        for (int i = 0; i < Math.min(36, player.getInventory().getContainerSize()); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            add(counts, stack, stack.getCount());
        }
        return counts;
    }

    public static void add(Map<ResourceLocation, Long> counts, ItemStack stack, long amount) {
        if (!stack.isEmpty() && amount > 0)
            counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()), amount,
                    (a, b) -> a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b);
    }

    static final class Cache {
        private Object player, world;
        private Map<ResourceLocation, Long> inventory, reported;
        private Snapshot snapshot;
        void record(Object player, Object world, Map<ResourceLocation, Long> inventory, Snapshot snapshot) {
            Map<ResourceLocation, Long> raw = snapshot.stored();
            if (this.snapshot != null && this.player == player && this.world == world
                    && this.snapshot.source() == snapshot.source()) {
                Map<ResourceLocation, Long> adjusted = new HashMap<>(raw);
                adjusted.replaceAll((item, amount) -> amount.equals(reported.get(item))
                        ? this.snapshot.storedCount(item) : amount);
                snapshot = new Snapshot(snapshot.source(), adjusted, snapshot.craftable(), snapshot.observedGameTick());
            }
            this.player = player;
            this.world = world;
            this.inventory = Map.copyOf(inventory);
            this.reported = raw;
            this.snapshot = snapshot;
        }
        Optional<Snapshot> latest(Object player, Object world, Map<ResourceLocation, Long> inventory, long tick) {
            if (snapshot == null) return Optional.empty();
            long age = tick - snapshot.observedGameTick();
            if (this.player != player || this.world != world || age < 0 || age > MAX_AGE_TICKS) {
                clear();
            } else if (!this.inventory.equals(inventory)) {
                Map<ResourceLocation, Long> remaining = new HashMap<>(snapshot.stored());
                remaining.replaceAll((item, amount) -> Math.max(0, amount - Math.max(0,
                        inventory.getOrDefault(item, 0L) - this.inventory.getOrDefault(item, 0L))));
                snapshot = new Snapshot(snapshot.source(), remaining, snapshot.craftable(), snapshot.observedGameTick());
                this.inventory = Map.copyOf(inventory);
            }
            return Optional.ofNullable(snapshot);
        }
        void clear() { player = null; world = null; inventory = null; reported = null; snapshot = null; }
    }
}
