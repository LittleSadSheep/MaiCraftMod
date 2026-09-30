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
import org.maiwithu.maicraft.core.integration.machine.MachineSnapshotRejection;
import org.maiwithu.maicraft.core.integration.machine.MachineSnapshots;
import org.maiwithu.maicraft.task.TaskState;

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
            // 思考耗时不让未变化的设备失效；普通操作与生产都复用原观察，不伪造新库存或新的观察时间。
            check(MachineSnapshots.requireForProduction(world.player,site.id()).gameTime()==site.gameTime(),
                    "production revalidates geometry without rewriting original observation time");
            check(MachineSnapshots.requireFresh(world.player,site.id()).gameTime()==site.gameTime(),
                    "ordinary operations retain unchanged evidence after long design work");
            try { MachineSnapshots.requireForProduction(world.player,regular.id()); throw new AssertionError("partial structure was accepted"); }
            catch (IllegalArgumentException expected) { check(expected.getMessage().contains("incomplete"),"production still needs complete structural evidence"); }
            clock.setLong(world.level,site.gameTime()-1);
            // 游戏时间回拨并未改变机器，不能让原位置的操作因时钟管理而被迫重新勘测。
            check(MachineSnapshots.requireForProduction(world.player,site.id()).id().equals(site.id())
                            && MachineSnapshots.requireFresh(world.player,site.id()).id().equals(site.id()),
                    "clock reversal alone does not invalidate observed geometry");
            clock.setLong(world.level,5000);
            // 在线 plan 使用同一锚点完成原生蓝图检查，不领材料、不消费编号，execute 还能继续使用。
            IntentRuntime runtime = IntentRuntime.get();
            runtime.remember("site", new Goal.WorldPosition(anchor.getX(), anchor.getY(), anchor.getZ(), site.dimension()));
            // 原生接线参数保留玩家点名的部件家族；兼容旧别名时也明确落到链式传动箱，而不是混成锁链传动轮。
            for (String family : new String[]{"auto","chain_conveyor","encased_chain_drive","chain_drive"}) {
                var connection = JsonParser.parseString("""
                        {"ability":"maicraft:connect_mechanical_power","outcome":"连接已知动力接口",
                         "target":{"kind":"landmark","label":"site"},"parameters":{"source_label":"site","target_label":"site"}}
                        """).getAsJsonObject();
                connection.getAsJsonObject("parameters").addProperty("transmission",family);
                var action = (IntentAction.Tool) AbilityAdapter.adapt(Goal.fromJson(connection),world.player,runtime);
                check(JsonParser.parseString(action.argumentsJson()).getAsJsonObject().get("transmission").getAsString()
                        .equals(family.equals("chain_drive") ? "encased_chain_drive" : family),"semantic adapter preserves explicit transmission family");
            }
            // 请求同时给出大区域与已记住的机器时，实际接线绑定机器；显式类型在区域内筛选，不能仅做显示名。
            runtime.remember("receiver",new Goal.WorldPosition(4,8,4,site.dimension()));
            var named=JsonParser.parseString("""
                    {"ability":"maicraft:connect_mechanical_power","outcome":"连接目标机器",
                     "target":{"kind":"area","label":"site"},"parameters":{"source_label":"site","target_label":"receiver"}}
                    """).getAsJsonObject();
            var resolved=(IntentAction.Tool)AbilityAdapter.adapt(Goal.fromJson(named),world.player,runtime);
            var endpoint=JsonParser.parseString(resolved.argumentsJson()).getAsJsonObject();
            check(endpoint.get("destination_x").getAsInt()==4 && endpoint.get("destination_z").getAsInt()==4,
                    "named machine location takes precedence over the broad area center");
            named.getAsJsonObject("parameters").addProperty("target_label","minecraft:hopper");
            endpoint=JsonParser.parseString(((IntentAction.Tool)AbilityAdapter.adapt(Goal.fromJson(named),world.player,runtime)).argumentsJson()).getAsJsonObject();
            check(endpoint.get("destination_name").getAsString().equals("minecraft:hopper") && endpoint.get("destination_x").getAsInt()==anchor.getX(),
                    "registered type is preserved alongside the observed search area");
            named.getAsJsonObject("parameters").addProperty("target_label","unremembered receiver");
            check(AbilityAdapter.adapt(Goal.fromJson(named),world.player,runtime) instanceof IntentAction.Decision,
                    "an unresolved explicit machine name cannot silently select another device in the area");
            check("minecraft:hopper".equals(AbilityAdapter.relationBlockId("the observed minecraft:hopper."))
                    && AbilityAdapter.relationBlockId("minecraft:hopper and minecraft:stone")==null,
                    "one explicit registered type survives a prior-result relation without guessing among multiple types");
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
            // 玩家绕到工地另一侧后重复勘察，观察与执行旧计划都必须留在原工地，不能把整台机器平移。
            world.position(new Vec3(10.5, 8, 9.5));
            var fixed = runtime.constructionAnchor("site", new Goal.WorldPosition(10, 8, 9, site.dimension()));
            check(fixed.x() == anchor.getX() && fixed.z() == anchor.getZ(), "同名工地不跟随角色移动");
            var refreshed = MachineSnapshots.constructionSite(world.player, "site", new BlockPos(fixed.x(), fixed.y(), fixed.z()), 7);
            check(refreshed.center().equals(site.center()) && MachinePlanPreflight.review(goal, world.player, runtime)
                    .get("valid").getAsBoolean(), "刷新现场后旧计划仍绑定同一锚点");
            // 列表只显示同名同址的最新观察；等待时间不把仍未变化的场地误标成过期。
            clock.setLong(world.level,7001);
            var summaries = MachineSnapshots.summaries(world.player);
            var sites = summaries.getAsJsonArray("machines").asList().stream().map(value -> value.getAsJsonObject())
                    .filter(value -> value.get("label").getAsString().equals("site")).toList();
            check(sites.size() == 1 && sites.getFirst().get("snapshot_id").getAsString().equals(refreshed.id())
                    && sites.getFirst().get("cached_versions").getAsInt() == 2,"repeated observations are one machine reference with version count");
            check(!sites.getFirst().get("expired").getAsBoolean() && !sites.getFirst().get("structure_changed").getAsBoolean()
                            && sites.getFirst().get("construction_anchor_reusable").getAsBoolean(),
                    "unchanged operating evidence and construction anchors survive elapsed time");
            check(MachineSnapshots.requireForConstruction(world.player,site.id()).id().equals(site.id()),"summary deduplication cannot consume older referenced observations");
            try {
                runtime.constructionAnchor("site", new Goal.WorldPosition(10, 8, 9, "minecraft:the_nether"));
                throw new AssertionError("跨维度观察不应覆盖工地");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("dimension_mismatch"), "明确报告工地维度不匹配");
            }
            // 施工任务创建后尚未放置任何方块：补料失败再改材料策略，仍应复用并重验原场地。
            check(MachineAbilityAdapter.adapt(goal, world.player, runtime, null) instanceof IntentAction.Native,
                    "the first construction attempt creates its native task");
            request.getAsJsonObject("parameters").addProperty("material_policy", "storage_available");
            Goal retried = Goal.fromJson(request);
            check(MachineAbilityAdapter.adapt(retried, world.player, runtime, null) instanceof IntentAction.Native,
                    "a supply-only retry can reuse unchanged construction geometry");
            check(MachineSnapshots.requireFresh(world.player,site.id()).id().equals(site.id()),
                    "creating construction attempts does not age out unchanged operation evidence");
            world.set(anchor.below(), Blocks.GOLD_BLOCK.defaultBlockState());
            // 最新的缓存概况也按实际方块变化判断，仍保留原工地锚点供逐格续建。
            var changedSummary = MachineSnapshots.summaries(world.player).getAsJsonArray("machines").asList().stream()
                    .map(value -> value.getAsJsonObject()).filter(value -> value.get("label").getAsString().equals("site")).findFirst().orElseThrow();
            check(changedSummary.get("expired").getAsBoolean() && changedSummary.get("structure_changed").getAsBoolean()
                            && changedSummary.get("construction_anchor_reusable").getAsBoolean(),
                    "actual scene changes invalidate operation evidence without blocking construction anchors");
            try { MachineSnapshots.requireForProduction(world.player,site.id()); throw new AssertionError("changed production geometry was accepted"); }
            catch (MachineSnapshotRejection expected) {
                JsonObject latest = (JsonObject) expected.details().get("latest_snapshot");
                var current = MachineSnapshots.requireFresh(world.player,latest.get("snapshot_id").getAsString());
                check(!current.id().equals(site.id()) && current.center().equals(anchor) && current.radius()==site.radius()
                                && latest.getAsJsonObject("target").get("label").getAsString().equals("site")
                                && latest.getAsJsonArray("palette").toString().contains("minecraft:gold_block"),
                        "changed geometry returns a usable current snapshot at the original named anchor");
            }
            executeReturnsCurrentObservation(world, runtime, site, anchor);
            var changed = MachinePlanPreflight.review(goal, world.player, runtime);
            // 施工或其他实际变化发生后继续用原锚点执行，具体格子交给原生施工，不再生成一次无谓的勘测决策。
            check(changed.get("valid").getAsBoolean()
                            && MachineSnapshots.requireForConstruction(world.player, site.id()).center().equals(anchor)
                            && MachineAbilityAdapter.adapt(goal, world.player, runtime, null) instanceof IntentAction.Native,
                    "changed construction geometry still starts native work at the original anchor");
            MachineSnapshots.consume(site);
            try { MachineSnapshots.requireForProduction(world.player,site.id()); throw new AssertionError("consumed production reference was reused"); }
            catch (IllegalArgumentException expected) { check(expected.getMessage().contains("missing"),"production cannot revive a consumed receipt"); }
            try { MachineSnapshots.requireForConstruction(world.player, site.id()); throw new AssertionError("consumed site was accepted"); }
            catch (IllegalArgumentException expected) { check(expected.getMessage().contains("missing"), "consumed anchors cannot start another build"); }
            missingReferencesReturnCurrentSite(world,runtime,goal,anchor);
            check(world.blockUses() == 0 && world.itemUses() == 0 && world.player.getInventory().isEmpty(), "survey never uses blocks or supplies materials");
        }
        System.out.println("ConstructionSiteRuntimeTest: passed");
    }

    // 缓存编号消失后仍能按已记地标定位；规划和直接执行都交付新绑定，蓝图不必重新设计或另发勘测。
    private static void missingReferencesReturnCurrentSite(InteractionWorldTestHarness world, IntentRuntime runtime,
            Goal original, BlockPos anchor) {
        JsonObject planned = MachinePlanPreflight.review(original,world.player,runtime);
        JsonObject latest = planned.getAsJsonObject("latest_snapshot");
        check(!planned.get("valid").getAsBoolean() && planned.get("failure_code").getAsString().equals("machine_snapshot_missing")
                        && MachineSnapshots.requireForConstruction(world.player,latest.get("snapshot_id").getAsString()).center().equals(anchor),
                "missing-reference planning returns current evidence at the named site despite player movement");
        JsonObject revised = original.toJson();
        revised.getAsJsonObject("parameters").addProperty("snapshot_id",latest.get("snapshot_id").getAsString());
        revised.add("target",latest.getAsJsonObject("target").deepCopy());
        check(MachinePlanPreflight.review(Goal.fromJson(revised),world.player,runtime).get("valid").getAsBoolean()
                        && revised.getAsJsonObject("parameters").get("blueprint").equals(original.parameters().get("blueprint")),
                "the unchanged authored blueprint can be replanned using the returned reference alone");
        var action = MachineAbilityAdapter.adapt(original,world.player,runtime,null);
        check(action instanceof IntentAction.Report && !((IntentAction.Report)action).result().success()
                        && ((IntentAction.Report)action).result().data().containsKey("latest_snapshot"),
                "direct execution also returns a current site instead of another observation decision");
    }

    // 模型沿旧现场开机器时，在任何点击前结束本次尝试，并在同一执行结果中给出新编号与新方块。
    private static void executeReturnsCurrentObservation(InteractionWorldTestHarness world, IntentRuntime runtime,
            MachineSnapshots.Snapshot previous, BlockPos anchor) {
        JsonObject request = JsonParser.parseString("""
                {"ability":"maicraft:operate_machine","outcome":"启动已观察的机器",
                 "target":{"kind":"landmark","label":"site"},
                 "parameters":{"operation":"set_control","powered":true,"allow_use":true}}
                """).getAsJsonObject();
        request.getAsJsonObject("parameters").addProperty("snapshot_id", previous.id());
        Goal goal = Goal.fromJson(request);
        var record = new IntentTaskRecord(UUID.randomUUID(), null, goal);
        var task = new IntentTask(world.player, record, runtime);
        check(task.tick(world.player)==TaskState.FAILED && record.decisionSnapshot()==null,
                "changed-scene execution returns its failure without another observation decision");
        JsonObject data = record.attempts().getLast().result().getAsJsonObject("data");
        JsonObject latest = data.getAsJsonObject("latest_snapshot");
        check(data.get("failure_code").getAsString().equals("machine_snapshot_changed")
                        && data.get("previous_snapshot_id").getAsString().equals(previous.id())
                        && latest.getAsJsonObject("target").get("label").getAsString().equals("site")
                        && latest.getAsJsonArray("palette").toString().contains("minecraft:gold_block")
                        && MachineSnapshots.requireFresh(world.player,latest.get("snapshot_id").getAsString()).center().equals(anchor),
                "semantic failure preserves the exact new snapshot and original target despite player movement");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
