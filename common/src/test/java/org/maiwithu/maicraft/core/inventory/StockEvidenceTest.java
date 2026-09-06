package org.maiwithu.maicraft.core.inventory;

import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

public final class StockEvidenceTest {
    public static void main(String[] args) {
        var iron = ResourceLocation.parse("minecraft:iron_ingot");
        var diamond = ResourceLocation.parse("minecraft:diamond");
        var wood = ResourceLocation.parse("minecraft:oak_log");
        Object player = new Object(), world = new Object();
        var inventory = Map.of(iron, 3L);
        var stock = new StockEvidence.Snapshot(StockEvidence.Source.AE2,
                Map.of(iron, 64L), Set.of(diamond), 20);
        var cache = new StockEvidence.Cache();
        check(stock.storedCount(diamond) == 0, "craftable is not stored stock");
        check(stock.supportsToolSupply(), "AE2 has a connected acquisition adapter");
        cache.record(player, world, inventory, stock);
        check(cache.latest(player, world, inventory, 21).orElseThrow().storedCount(iron) == 64,
                "external stock never includes the three carried ingots");
        check(cache.latest(player, world, Map.of(iron, 3L, wood, 12L), 22).orElseThrow().storedCount(iron) == 64,
                "organizing wood does not erase an iron-stock planning hint");
        check(cache.latest(player, world, Map.of(iron, 4L), 23).orElseThrow().storedCount(iron) == 63,
                "a carried gain debits only matching external stock");
        check(cache.latest(player, world, inventory, 24).orElseThrow().storedCount(iron) == 63,
                "using the acquired ingot does not add it back to storage");
        cache.record(player, world, inventory, stock);
        check(cache.latest(player, world, inventory, 25).orElseThrow().storedCount(iron) == 63,
                "re-reading an unchanged repository cannot erase the withdrawal debit");
        check(cache.latest(player, world, inventory, 1221).isEmpty(), "stock expires after sixty seconds");
        cache.record(player, world, inventory, stock);
        check(cache.latest(new Object(), world, inventory, 21).isEmpty(), "body replacement invalidates evidence");
        cache.record(player, world, inventory, stock);
        check(cache.latest(player, new Object(), inventory, 21).isEmpty(), "another world cannot reuse evidence");
        cache.record(player, world, inventory, stock);
        check(cache.latest(player, world, inventory, 19).isEmpty(), "clock rollback invalidates evidence");
        cache.record(player, world, inventory, stock);
        cache.record(player, world, inventory, new StockEvidence.Snapshot(
                StockEvidence.Source.CREATE, Map.of(iron, 12L), Set.of(), 21));
        var latest = cache.latest(player, world, inventory, 21).orElseThrow();
        check(latest.storedCount(iron) == 12, "two networks are never added together");
        check(!latest.supportsToolSupply(), "Create observation cannot pretend to deliver packages");
        System.out.println("StockEvidenceTest: passed");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
