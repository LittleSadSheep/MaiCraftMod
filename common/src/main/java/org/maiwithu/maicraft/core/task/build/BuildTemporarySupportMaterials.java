// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.FallingBlock;

/** Read-only reservations over the full concrete build, including later semantic supply batches. */
public final class BuildTemporarySupportMaterials {
    public record Choice(Item item, int inventorySlot) {}
    private BuildTemporarySupportMaterials() {}

    public static Map<Item, Integer> remaining(List<BuildTaskRecord.Target> targets, Predicate<BuildTaskRecord.Target> satisfied) {
        Map<Item, Integer> reserved = new LinkedHashMap<>();
        for (var target : targets) {
            int cost = target.materialCount();
            if (cost > 0 && !satisfied.test(target)) reserved.merge(target.item(), cost, Math::addExact);
        }
        return Map.copyOf(reserved);
    }

    public static Item choose(List<Item> allowed, Map<Item, Integer> reserved, ToIntFunction<Item> carried,
                              int required, boolean creativeFree) {
        if (required < 1) throw new IllegalArgumentException("A support chain must consume a positive material quantity");
        // Preserve configured order within each tier, while preferring materials with no permanent demand.
        for (int tier = 0; tier < 2; tier++) for (Item item : allowed) {
            int permanent = reserved.getOrDefault(item, 0);
            if ((permanent == 0) != (tier == 0) || !eligible(item)) continue;
            if (creativeFree || canSpend(carried.applyAsInt(item), permanent, required)) return item;
        }
        return null;
    }

    public static Choice inventoryChoice(List<ItemStack> main, List<Item> allowed, Map<Item, Integer> reserved) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (int slot = 0; slot < Math.min(36, main.size()); slot++) {
            ItemStack stack = main.get(slot);
            if (!stack.isEmpty()) counts.merge(stack.getItem(), stack.getCount(), Math::addExact);
        }
        Item material = choose(allowed, reserved, item -> counts.getOrDefault(item, 0), 1, false);
        if (material == null) return null;
        for (int slot = 0; slot < Math.min(36, main.size()); slot++) if (main.get(slot).is(material)) return new Choice(material, slot);
        return null;
    }

    public static boolean canSpend(int carried, int reserved, int required) {
        return carried >= 0 && reserved >= 0 && required > 0 && (long) carried - reserved >= required;
    }

    private static boolean eligible(Item item) {
        if (!(item instanceof BlockItem block)) return false;
        var state = block.getBlock().defaultBlockState();
        return !state.hasBlockEntity() && !(block.getBlock() instanceof FallingBlock)
                && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }
}
