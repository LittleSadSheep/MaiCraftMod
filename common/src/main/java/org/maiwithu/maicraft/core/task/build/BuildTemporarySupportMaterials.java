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

/** 对完整具体建筑方案执行只读材料预留，包括后续语义供料批次。 */
public final class BuildTemporarySupportMaterials {
    public record Choice(Item item, int inventorySlot) {}
    public record SupplyNeed(Item item, int requiredFinalCount) {}
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
        // 保留每个优先级层级中的配置顺序，同时优先选用没有永久需求的材料。
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

    /** 缺垫块时选择一种确实允许的材料，供料按永久预留加整条支撑链计算，不能把几种零散方块凑成一条单材质支撑。 */
    public static SupplyNeed supplyNeed(List<Item> allowed, Map<Item, Integer> reserved,
                                       ToIntFunction<Item> carried, int required) {
        if (required < 1) throw new IllegalArgumentException("support quantity must be positive");
        SupplyNeed best = null;
        long smallestMissing = Long.MAX_VALUE;
        for (int tier = 0; tier < 2; tier++) {
            for (Item item : allowed) {
                int permanent = reserved.getOrDefault(item, 0);
                if ((permanent == 0) != (tier == 0) || !eligible(item)) continue;
                long total = (long) permanent + required;
                if (total > Integer.MAX_VALUE) continue;
                long missing = Math.max(0L, total - carried.applyAsInt(item));
                if (missing < smallestMissing) {
                    best = new SupplyNeed(item, (int) total);
                    smallestMissing = missing;
                }
            }
            if (best != null) return best;
        }
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
