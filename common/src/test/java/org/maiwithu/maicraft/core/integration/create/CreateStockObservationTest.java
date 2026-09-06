package org.maiwithu.maicraft.core.integration.create;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class CreateStockObservationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Object holder = new Object(), old = new Object(), received = new Object();
        CreateStockObservation.reset();
        check(!CreateStockObservation.receivedCompleteSummary(holder, old), "old BE cache has unknown age");
        check(!CreateStockObservation.receivedCompleteSummary(holder, old), "partial packets keep the old summary");
        check(CreateStockObservation.receivedCompleteSummary(holder, received), "final packet replaces the summary");
        check(!CreateStockObservation.receivedCompleteSummary(holder, received), "reads cannot renew a response timestamp");
        check(!CreateStockObservation.receivedCompleteSummary(new Object(), received), "another terminal needs new evidence");
        var counts = CreateStockObservation.readSummary(new Summary());
        check(counts.get(ResourceLocation.parse("minecraft:iron_ingot")) == 64,
                "summary counts, not ItemStack display counts, describe stored stock");
        check(!counts.containsKey(ResourceLocation.parse("minecraft:diamond")), "craftable preview is not stored stock");
        CreateStockObservation.reset();
        System.out.println("CreateStockObservationTest: passed");
    }
    public static class Entry {
        public ItemStack stack = new ItemStack(Items.IRON_INGOT);
        public int count = 64;
    }
    public static final class CraftableBigItemStack extends Entry {
        CraftableBigItemStack() { stack = new ItemStack(Items.DIAMOND); count = 192; }
    }
    public static final class Summary {
        public List<Entry> getStacks() { return List.of(new Entry(), new CraftableBigItemStack()); }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
