// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.suicide;

import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** 已加载岩浆与悬崖提供真实候选；凝固、积水、未加载和无效参数不能伪造危险或成功。 */
public final class SuicideHazardsTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        // 无窗口 JVM 没有数据包加载阶段，显式绑定原版流体标签，才能按游戏内同一证据识别岩浆。
        BuiltInRegistries.FLUID.bindTags(Map.of(FluidTags.LAVA, List.of(
                BuiltInRegistries.FLUID.wrapAsHolder(Fluids.LAVA), BuiltInRegistries.FLUID.wrapAsHolder(Fluids.FLOWING_LAVA))));
        var defaults = SuicideRequest.parse(new JsonObject());
        check(defaults.method().equals("auto") && defaults.radius() == 24 && !defaults.keepInventoryConfirmed(), "默认不能猜测多人规则");
        for (String invalid : List.of("{\"method\":\"kill\"}", "{\"method\":1}", "{\"method\":null}",
                "{\"search_radius\":3}", "{\"search_radius\":65}", "{\"timeout_seconds\":2.5}",
                "{\"timeout_seconds\":\"120\"}", "{\"keep_inventory_confirmed\":\"true\"}")) {
            try { SuicideRequest.parse(JsonParser.parseString(invalid).getAsJsonObject()); throw new AssertionError(invalid); }
            catch (IllegalArgumentException expected) { }
        }
        try (var world = new InteractionWorldTestHarness()) {
            world.position(new Vec3(8.5, 2, 8.5));
            world.set(new BlockPos(8, 1, 8), Blocks.STONE.defaultBlockState());
            world.set(new BlockPos(9, 1, 8), Blocks.LAVA.defaultBlockState());
            check(SuicideHazards.standing(world.level, new BlockPos(8, 2, 8)), "岸边脚位应可站立，边界=" + world.level.isInWorldBounds(new BlockPos(8, 2, 8)));
            var lava = new SuicideHazards(world.player, new SuicideRequest("lava", 8, 120, true));
            while (!lava.scan()) { }
            var candidate = lava.choose(world.player, Set.of());
            check(candidate != null && candidate.method().equals("lava"), "岸边岩浆应能用原生移动尝试进入");
            var next = lava.choose(world.player, Set.of(candidate.key()));
            check(next == null || !next.key().equals(candidate.key()), "没有效果的同一危险不能无限重试");
            world.set(candidate.entry().below(), Blocks.OBSIDIAN.defaultBlockState());
            check(!SuicideHazards.valid(world.player, candidate), "岩浆凝固后必须重新选择危险");
            check(world.blockUses() == 0 && world.itemUses() == 0, "找危险不能修改地形或背包");
        }
        try (var world = new InteractionWorldTestHarness()) {
            world.position(new Vec3(8.5, 10, 8.5));
            world.set(new BlockPos(8, 9, 8), Blocks.STONE.defaultBlockState());
            world.set(new BlockPos(9, 0, 8), Blocks.STONE.defaultBlockState());
            var fall = new SuicideHazards(world.player, new SuicideRequest("fall", 12, 120, true));
            while (!fall.scan()) { }
            var candidate = fall.choose(world.player, Set.of());
            check(candidate != null && candidate.method().equals("fall"), "高台旁有实际落地柱时应能尝试坠落");
            world.set(new BlockPos(candidate.entry().getX(), 1, candidate.entry().getZ()), Blocks.WATER.defaultBlockState());
            check(!SuicideHazards.valid(world.player, candidate), "已有水缓冲的柱不能误报为已观察到的摔伤地点");
            check(SuicideHazards.fallHeight(world.level, new BlockPos(16, 10, 8)) == 0, "未加载列不能读方块或伪造悬崖");
        }
        System.out.println("SuicideHazardsTest: passed");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
