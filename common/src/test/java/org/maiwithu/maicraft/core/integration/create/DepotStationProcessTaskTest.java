// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;

/**
 * 置物台加工的回执以背包实际变化为准：放上的原料记为负数，收回的产物记为正数，没变的物品不列出。
 * 直播实测里铁板放上后注液出了蜂蜜胶却没人收取；这里锁住收取结论只看实际增减，不按配方推断。
 */
public final class DepotStationProcessTaskTest {
    public static void main(String[] args) {
        // 一块铁板放上置物台、注液后收回一个蜂蜜胶：铁板 -1、蜂蜜胶 +1，没变的扳手不出现。
        var before = Map.of("create:iron_sheet", 1, "simulated:honey_glue", 1, "create:wrench", 1);
        var after = Map.of("simulated:honey_glue", 2, "create:wrench", 1);
        var changes = DepotStationProcessTask.inventoryChanges(before, after);
        check(changes.equals(Map.of("create:iron_sheet", -1, "simulated:honey_glue", 1)), "changes list consumed and collected items: " + changes);
        // 到时未加工、原料被原样收回：背包没有净变化，回执不能声称收到了产物。
        check(DepotStationProcessTask.inventoryChanges(before, before).isEmpty(), "returned input leaves no net change");
        // 只认 Create 置物台，普通方块不能被当成加工工位。
        if (BuiltInRegistries.BLOCK.containsKey(DepotStationProcessTask.DEPOT))
            check(DepotStationProcessTask.isDepot(BuiltInRegistries.BLOCK.get(DepotStationProcessTask.DEPOT)), "depot recognized");
        check(!DepotStationProcessTask.isDepot(Blocks.STONE), "stone is not a depot");
        check(DepotStationProcessTask.DEPOT.equals(ResourceLocation.fromNamespaceAndPath("create", "depot")), "depot id");
        System.out.println("DepotStationProcessTaskTest: passed");
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("depot station process: " + what);
    }
}
