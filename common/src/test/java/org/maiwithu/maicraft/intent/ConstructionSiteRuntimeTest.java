// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.util.UUID;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.machine.ConstructionSiteGeometry;
import org.maiwithu.maicraft.core.integration.machine.MachineSnapshots;

/** 用真实方块状态和完整快照缓存验证密集地形、设计耗时、现场变化与回执消费。 */
public final class ConstructionSiteRuntimeTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            world.player.setUUID(UUID.randomUUID());
            world.position(new Vec3(8.5, 8, 8.5));
            for (int y = 1; y <= 7; y++) for (int z = 1; z <= 15; z++) for (int x = 1; x <= 15; x++)
                world.set(new BlockPos(x, y, z), Blocks.SMOOTH_STONE.defaultBlockState());
            BlockPos anchor = world.player.blockPosition();
            var regular = MachineSnapshots.inspect(world.player, "old", anchor, 7);
            check(!regular.report().get("structure_complete").getAsBoolean(), "fixture exceeds the ordinary 768-block presentation limit");
            var site = MachineSnapshots.constructionSite(world.player, "site", anchor, 7);
            check(site.report().get("structure_complete").getAsBoolean(), "all loaded site geometry is retained despite dense terrain");
            check(site.report().getAsJsonArray("relative_blocks").size() == 1575, "no underground cells are lost from the cached fingerprint evidence");
            check(ConstructionSiteGeometry.describe(site).getAsJsonArray("surface_and_obstacles").size() == 15, "the model only needs fifteen exact floor runs");
            var clock = world.level.getClass().getDeclaredField("time"); clock.setAccessible(true); clock.setLong(world.level, 5000);
            check(MachineSnapshots.requireForConstruction(world.player, site.id()).id().equals(site.id()), "unchanged geometry survives long design work");
            // 在线 plan 使用同一锚点完成原生蓝图检查，不领材料、不消费编号，execute 还能继续使用。
            IntentRuntime runtime = IntentRuntime.get();
            runtime.remember("site", new Goal.WorldPosition(anchor.getX(), anchor.getY(), anchor.getZ(), site.dimension()));
            JsonObject request = JsonParser.parseString("""
                    {"ability":"maicraft:build_machine","outcome":"build on the platform",
                     "target":{"kind":"landmark","label":"site"},"parameters":{"allow_modify":true,
                     "blueprint":{"blocks":[{"offset":[0,0,0],"block_id":"minecraft:barrel"}]}}}
                    """).getAsJsonObject();
            request.getAsJsonObject("parameters").addProperty("snapshot_id", site.id());
            Goal goal = Goal.fromJson(request);
            var planned = MachinePlanPreflight.review(goal, world.player, runtime);
            check(planned.get("valid").getAsBoolean() && planned.getAsJsonArray("checks").get(0).getAsJsonObject()
                    .get("site_anchor_verified").getAsBoolean(), "plan validates the real snapshot binding");
            check(MachineSnapshots.requireForConstruction(world.player, site.id()).id().equals(site.id()), "plan leaves the receipt available to execute");
            try { MachineSnapshots.requireFresh(world.player, site.id()); throw new AssertionError("native action reused an old site receipt"); }
            catch (IllegalArgumentException expected) { check(expected.getMessage().contains("expired"), "native actions retain their freshness rule"); }
            world.set(anchor.below(), Blocks.GOLD_BLOCK.defaultBlockState());
            var changed = MachinePlanPreflight.review(goal, world.player, runtime);
            check(!changed.get("valid").getAsBoolean() && changed.toString().contains("snapshot_id"), "plan reports changed geometry at the receipt path before execution");
            try { MachineSnapshots.requireForConstruction(world.player, site.id()); throw new AssertionError("changed site was accepted"); }
            catch (IllegalArgumentException expected) { check(expected.getMessage().contains("changed"), "construction rechecks actual geometry"); }
            MachineSnapshots.consume(site);
            try { MachineSnapshots.requireForConstruction(world.player, site.id()); throw new AssertionError("consumed site was accepted"); }
            catch (IllegalArgumentException expected) { check(expected.getMessage().contains("missing"), "consumed anchors cannot start another build"); }
            check(world.blockUses() == 0 && world.itemUses() == 0 && world.player.getInventory().isEmpty(), "survey never uses blocks or supplies materials");
        }
        System.out.println("ConstructionSiteRuntimeTest: passed");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
