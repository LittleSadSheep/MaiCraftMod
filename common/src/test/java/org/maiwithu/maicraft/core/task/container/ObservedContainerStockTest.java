// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.container;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.core.inventory.StockEvidence;

/** 验证已打开容器的库存提示有时效和身份边界，双箱按一次实际观察去重。 */
public final class ObservedContainerStockTest {
    private static final ResourceLocation LOG = ResourceLocation.parse("minecraft:oak_log");
    public static void main(String[] args) {
        // 双箱两半共用一次界面观察的十六根原木；读取提示不扫描周边仓库，也不重复计数。
        var cache = new ContainerSupplySources.Cache(); Object player = new Object(), world = new Object(), first = new Object(), second = new Object();
        BlockPos a = new BlockPos(1, 0, 0), b = a.east(); var doubleChest = Map.of(a, first, b, second);
        var stock = new StockEvidence.Snapshot(StockEvidence.Source.CONTAINER, Map.of(LOG, 16L), Set.of(), 20);
        cache.record(player, world, doubleChest, stock); AtomicInteger inspected = new AtomicInteger();
        check(cache.observed(player, world, BlockPos.ZERO, 8, 21, at -> { inspected.incrementAndGet(); return true; }, at -> doubleChest).get(LOG) == 16,
                "two chest halves provide one sixteen-log hint, not thirty-two");
        check(inspected.get() == 2, "only the cached footprint is inspected; no region is searched");
        // 任一半超出范围、受保护或结构改变，整份双箱库存都不能继续影响取料路线。
        check(cache.observed(player, world, BlockPos.ZERO, 1, 21, at -> true, at -> doubleChest).isEmpty(), "the complete double chest must remain inside the allowed radius");
        check(cache.observed(player, world, BlockPos.ZERO, 8, 21, at -> !at.equals(b), at -> doubleChest).isEmpty(), "an unloaded or use-protected second half excludes the whole source");
        check(cache.observed(player, world, BlockPos.ZERO, 8, 21, at -> true, at -> Map.of(a, first)).isEmpty(), "splitting a double chest invalidates its earlier combined menu contents");
        cache.record(player, world, doubleChest, stock);
        check(cache.observed(player, world, BlockPos.ZERO, 8, 21, at -> true, at -> Map.of(a, first, b, new Object())).isEmpty(), "replacement entity identity invalidates the hint");
        cache.record(player, world, doubleChest, stock);
        // 超时、换玩家、换世界或时钟回退后清除旧证据，不能把过去看到的材料当作当前可用。
        check(cache.observed(player, world, BlockPos.ZERO, 8, 1221, at -> true, at -> doubleChest).isEmpty(), "expired stock cannot improve a recipe's score");
        cache.record(player, world, doubleChest, stock);
        check(cache.observed(new Object(), world, BlockPos.ZERO, 8, 21, at -> true, at -> doubleChest).isEmpty(), "another player cannot inherit observed contents");
        cache.record(player, world, doubleChest, stock);
        check(cache.observed(player, new Object(), BlockPos.ZERO, 8, 21, at -> true, at -> doubleChest).isEmpty(), "another world cannot inherit observed contents");
        cache.record(player, world, doubleChest, stock);
        check(cache.observed(player, world, BlockPos.ZERO, 8, 19, at -> true, at -> doubleChest).isEmpty(), "clock rollback invalidates the observation");
        System.out.println("ObservedContainerStockTest: bounded identity/TTL/radius/protection hints and double-chest deduplication passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
