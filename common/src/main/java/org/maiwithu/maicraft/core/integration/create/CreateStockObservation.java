package org.maiwithu.maicraft.core.integration.create;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.core.inventory.StockEvidence;

/** Optional Create 6 stockkeeper protocol; reads completed stock responses without ordering packages. */
public final class CreateStockObservation {
    private static Object previousHolder, previousSummary;
    private CreateStockObservation() {}
    public static boolean supports(Object menu) {
        return menu != null && menu.getClass().getName().equals(
                "com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestMenu");
    }
    public static void reset() { previousHolder = null; previousSummary = null; }

    public static Optional<StockEvidence.Snapshot> observe(Object menu, long tick) {
        if (!supports(menu)) return Optional.empty();
        try {
            Object holder = menu.getClass().getField("contentHolder").get(menu);
            Object summary = holder.getClass().getMethod("getLastClientsideStockSnapshotAsSummary").invoke(holder);
            if (!receivedCompleteSummary(holder, summary)) return Optional.empty();
            // Create allocates a new summary only after the final packet of the response arrives.
            // getTicksSinceLastUpdate measures requests, not responses, so it is not freshness proof.
            return Optional.of(new StockEvidence.Snapshot(StockEvidence.Source.CREATE,
                    readSummary(summary), Set.of(), tick));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    static boolean receivedCompleteSummary(Object holder, Object summary) {
        // The first sight of an existing BE snapshot supplies no receipt time for this GUI session.
        boolean received = holder == previousHolder && summary != null && summary != previousSummary;
        previousHolder = holder;
        previousSummary = summary;
        return received;
    }

    static Map<ResourceLocation, Long> readSummary(Object summary) throws ReflectiveOperationException {
        Object raw = summary.getClass().getMethod("getStacks").invoke(summary);
        if (!(raw instanceof Iterable<?> entries)) throw new IllegalStateException("missing stock entries");
        Map<ResourceLocation, Long> counts = new HashMap<>();
        for (Object entry : entries) {
            if (entry == null || entry.getClass().getSimpleName().equals("CraftableBigItemStack")) continue;
            Object stack = entry.getClass().getField("stack").get(entry);
            Object count = entry.getClass().getField("count").get(entry);
            if (!(stack instanceof ItemStack item) || !(count instanceof Number amount))
                throw new IllegalStateException("invalid stock entry");
            StockEvidence.add(counts, item, amount.longValue());
        }
        return Map.copyOf(counts);
    }
}
