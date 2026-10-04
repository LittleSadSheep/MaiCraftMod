// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import static org.maiwithu.maicraft.core.task.dimension.NetherPortalFrameTest.check;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.machine.assembly.FluidPlacementTaskRecord;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.intent.SemanticAbilityCatalog;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 选址阶段挑选可达候选并诚实汇报；回执区分零源岩浆、有源无合规池、全部不可达；契约禁止桶装岩浆顶替。
 */
public final class PortalCastingHonestSelectionTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        b1SurveyPrefersReachable();
        b1TaskFallsThroughOnPrepareSiteFailure();
        b1AllUnreachableReportsTally(false);
        b1AllUnreachableReportsTally(true);
        b2ShoreCheckReasons();
        b4ThreeFailureStates();
        antiBlindnessReachableStance();
        contractStatesPoolIsSourceAndSite();
        System.out.println("PortalCastingHonestSelectionTest: reachability, rejection tally and contract honesty passed");
    }

    /** B1：近池站台不可达、远池可站时，选址必须锁定远池，并把近池候选留作回退证据。 */
    private static void b1SurveyPrefersReachable() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            seedPool(world, new BlockPos(2, 1, 4), false);
            seedPool(world, new BlockPos(13, 1, 4), true);
            var origin = world.player.blockPosition();
            try (var survey = new PortalCastingSurvey(world.level, origin, 32)) {
                NetherPortalCastingLayout picked = null;
                for (int i = 0; i < 200 && picked == null; i++) {
                    world.nextTick();
                    TargetIndex.clientTick(world.level);
                    picked = survey.tick();
                }
                check(picked != null, "survey must lock the reachable candidate within its budget");
                check(picked.origin().getX() >= 12, "survey picks the reachable far pool, not the first by distance");
                var nearer = survey.candidates().stream()
                        .filter(c -> c.layout().origin().getX() < 11).toList();
                check(nearer.size() >= 1, "the nearer unreachable pool is retained as evidence");
                check(nearer.stream().noneMatch(PortalCastingSurvey.Candidate::reachable),
                        "nearer pools were probed unreachable, not silently dropped");
            }
        }
    }

    /** B1：第一个候选的站台施工失败且未放置任何方块时，任务换下一个候选继续，而不是终止。 */
    private static void b1TaskFallsThroughOnPrepareSiteFailure() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            world.position(new Vec3(7.5, 2, 5.5));
            seedPool(world, new BlockPos(3, 1, 4), false);
            seedPool(world, new BlockPos(13, 1, 4), false);
            world.inventory.setItem(0, new ItemStack(Items.WATER_BUCKET));
            world.inventory.setItem(1, new ItemStack(Items.COBBLESTONE, 64));
            world.inventory.setItem(2, new ItemStack(Items.STONE_PICKAXE));
            // 选址回放只讨论站台可达性，先满足点火前置。
            world.inventory.setItem(3, new ItemStack(Items.FLINT_AND_STEEL));
            int[] platformBuilds = {0};
            var task = new NetherPortalCastingTask(world.player,
                    new PortalPreparationTaskRecord("casting", 6000, "minecraft:the_nether", 32, true,
                            new PortalPreparationPolicy(true, false, false, 128, MaterialPolicy.INVENTORY_ONLY,
                                    List.of(), List.of(), PortalPreparationPolicy.Method.LAVA_CAST)),
                    (player, record) -> new Task() {
                        public String name() { return "casting_fallthrough_fixture"; }
                        public void stop(LocalPlayer player, StopReason why) {}
                        public TaskState tick(LocalPlayer player) {
                            if (record instanceof BuildTaskRecord build && !build.targets.isEmpty()) {
                                // 第一次站台施工导航失败且未放置任何方块；之后的管理一次建成。
                                platformBuilds[0]++;
                                if (platformBuilds[0] == 1) return TaskState.FAILED;
                                build.targets.forEach(t -> world.set(t.pos(), t.desiredState()));
                                return TaskState.SUCCESS;
                            }
                            // 夹具无法真实发生流体反应：倒水一步失败，任务以真实卡点收尾。
                            if (record instanceof FluidPlacementTaskRecord) return TaskState.FAILED;
                            return TaskState.SUCCESS;
                        }
                        public TaskResult result(TaskState state) {
                            if (state == TaskState.FAILED)
                                return TaskResult.fail("navigation did not reach stance", Map.of("native_effect_verified", false, "failure_type", "no_path"));
                            return TaskResult.ok("fixture build", Map.of("native_effect_verified", true));
                        }
                    });
            task.start(world.player);
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 1500 && state == TaskState.RUNNING; tick++) {
                world.nextTick(); TargetIndex.clientTick(world.level); state = task.tick(world.player);
            }
            check(state == TaskState.FAILED, "the harness cannot pour real fluids, so the task ends failed; state=" + state
                    + " issue=" + task.result(state).data().get("issue_code")
                    + " operation=" + task.result(state).data().get("operation"));
            check(platformBuilds[0] >= 2, "the failed platform build was followed by a successful fallback build");
            var data = task.result(state).data();
            var resources = (java.util.Map<?, ?>) data.get("resource_preparation");
            var search = (java.util.Map<?, ?>) resources.get("search");
            check(((Number) search.get("candidates_total")).intValue() >= 2,
                    "both pools' candidates are tallied in the receipt");
            check(((Number) search.get("candidates_tried")).intValue() >= 1,
                    "the tried counter records the consumed fallback");
            check("fallback_selected".equals(search.get("candidates_status")),
                    "candidates_status records that a fallback candidate was selected");
        }
    }

    /** B1：全部候选站台不可达时，逐个尝试后以独立失败码收尾，并交付完整统计而不是宣称附近没有池岸。 */
    private static void b1AllUnreachableReportsTally(boolean noSpace) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            seedPool(world, new BlockPos(2, 1, 4), false);
            seedPool(world, new BlockPos(7, 1, 4), false);
            seedPool(world, new BlockPos(12, 1, 4), false);
            world.inventory.setItem(0, new ItemStack(Items.WATER_BUCKET));
            world.inventory.setItem(1, new ItemStack(Items.COBBLESTONE, 64));
            world.inventory.setItem(2, new ItemStack(Items.STONE_PICKAXE));
            // 预检不可达的候选仍可尝试施工，但先备齐点火用品。
            world.inventory.setItem(3, new ItemStack(Items.FLINT_AND_STEEL));
            var task = new NetherPortalCastingTask(world.player,
                    new PortalPreparationTaskRecord("casting", 6000, "minecraft:the_nether", 32, true,
                            new PortalPreparationPolicy(true, false, false, 128, MaterialPolicy.INVENTORY_ONLY,
                                    List.of(), List.of(), PortalPreparationPolicy.Method.LAVA_CAST)),
                    (player, record) -> new Task() {
                        public String name() { return "casting_unreachable_fixture"; }
                        public void stop(LocalPlayer player, StopReason why) {}
                        public TaskState tick(LocalPlayer player) {
                            if (record instanceof BuildTaskRecord build && !build.targets.isEmpty())
                                return TaskState.FAILED;
                            if (record instanceof FluidPlacementTaskRecord) return TaskState.FAILED;
                            return TaskState.SUCCESS;
                        }
                        public TaskResult result(TaskState state) {
                            if (state == TaskState.FAILED)
                                return TaskResult.fail(noSpace ? "inventory has no room" : "stance not reachable",
                                        Map.of("native_effect_verified", false, "failure_type", noSpace ? "no_space" : "no_path"));
                            return TaskResult.ok("fixture", Map.of("native_effect_verified", true));
                        }
                    });
            task.start(world.player);
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 1500 && state == TaskState.RUNNING; tick++) {
                world.nextTick(); TargetIndex.clientTick(world.level); state = task.tick(world.player);
            }
            var data = task.result(state).data();
            check(state == TaskState.FAILED, "all unreachable candidates end as a reported failure; state=" + state
                    + " issue=" + data.get("issue_code"));
            // 满包导致的失败没有证明岸线走不到，不能换池，也不能把容量问题交成路径问题。
            if (noSpace) {
                check("casting_prepare_bank_platform_failed".equals(data.get("issue_code"))
                        && "no_space".equals(data.get("failure_type")), "storage failure retains its actual cause");
                var resourceFacts = (Map<?, ?>) data.get("resource_preparation");
                check(((Number) ((Map<?, ?>) resourceFacts.get("search")).get("candidates_tried")).intValue() == 0,
                        "inventory pressure cannot consume geometric fallback candidates");
                return;
            }
            check("casting_no_reachable_candidate".equals(data.get("issue_code")),
                    "failure code distinguishes all-unreachable from zero-source-lava");
            var resources = (java.util.Map<?, ?>) data.get("resource_preparation");
            var search = (java.util.Map<?, ?>) resources.get("search");
            check(((Number) search.get("candidates_total")).intValue() >= 3,
                    "all three pools' candidates are tallied in the receipt");
            check(((Number) search.get("candidates_tried")).intValue() >= 2,
                    "the tried counter reflects the consumed fallbacks");
            check("observed".equals(search.get("source_lava_observation")),
                    "source lava was observed, so this is not a zero-source report");
        }
    }

    /** B2：atShoreDetailed 把每条拒绝归类到现有分支；布尔包装层与旧判定逐格一致。 */
    private static void b2ShoreCheckReasons() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var layout = new NetherPortalCastingLayout(new BlockPos(7, 1, 7), Direction.NORTH);
            for (int x = -1; x <= 2; x++) {
                world.set(layout.cell(x, 0, 0), Blocks.LAVA.defaultBlockState());
                world.set(layout.cell(x, 0, 1), Blocks.STONE.defaultBlockState());
            }
            world.set(layout.cell(0, 0, 0), Blocks.WATER.defaultBlockState());
            var poolWater = PortalCastingSurvey.atShoreDetailed(world.level, layout);
            check(!poolWater.accepted() && poolWater.reason() == PortalCastingSurvey.ShoreCheck.Reason.POOL_NOT_LAVA_SOURCE,
                    "B2 reports a pool row that is not still-source lava");
            check(!PortalCastingSurvey.atShore(world.level, layout),
                    "the boolean wrapper rejects the same layout");
            world.set(layout.cell(0, 0, 0), Blocks.LAVA.defaultBlockState());
            // 方块实体放进模具格（脚印成员、不在池岸行上），才会命中 FOOTPRINT_BLOCK_ENTITY 分支。
            world.set(layout.mold().get(0), Blocks.CHEST.defaultBlockState());
            var blockEntity = PortalCastingSurvey.atShoreDetailed(world.level, layout);
            check(!blockEntity.accepted() && blockEntity.reason() == PortalCastingSurvey.ShoreCheck.Reason.FOOTPRINT_BLOCK_ENTITY,
                    "B2 reports footprint cells carrying block entities");
            world.set(layout.mold().get(0), Blocks.AIR.defaultBlockState());
            // 流动岩浆不能冒充静源：仍归入池岸行不合格这一类。
            world.set(layout.cell(0, 0, 0), Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 1));
            var flowing = PortalCastingSurvey.atShoreDetailed(world.level, layout);
            check(!flowing.accepted() && flowing.reason() == PortalCastingSurvey.ShoreCheck.Reason.POOL_NOT_LAVA_SOURCE,
                    "flowing lava stays under the pool-row rejection category");
            world.set(layout.cell(0, 0, 0), Blocks.LAVA.defaultBlockState());
            check(PortalCastingSurvey.atShore(world.level, layout),
                    "the previously accepted shallow pool is still accepted");
        }
    }

    /** B4：零源岩浆、有源无合规池、有合规池但全部不可达，各自落在独立字段里。 */
    private static void b4ThreeFailureStates() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var origin = world.player.blockPosition();
            try (var survey = new PortalCastingSurvey(world.level, origin, 32)) {
                NetherPortalCastingLayout picked = null;
                for (int i = 0; i < 50 && picked == null && !survey.complete(); i++) {
                    TargetIndex.clientTick(world.level); picked = survey.tick();
                }
                check(picked == null && survey.complete(), "no source lava finishes the scan");
                var obs = survey.observations();
                check("not_observed".equals(obs.get("source_lava_observation"))
                        && "zero_source_lava_observed".equals(obs.get("candidates_status")),
                        "zero-source-lava uses its own fields");
                check("not_observed_in_loaded_scope".equals(obs.get("lava_pool_status")),
                        "the existing lava_pool_status enum keeps its meaning");
            }
        }
        try (var world = new InteractionWorldTestHarness()) {
            world.set(new BlockPos(6, 1, 6), Blocks.LAVA.defaultBlockState());
            world.set(new BlockPos(2, 1, 2), Blocks.LAVA.defaultBlockState());
            var origin = world.player.blockPosition();
            try (var survey = new PortalCastingSurvey(world.level, origin, 32)) {
                NetherPortalCastingLayout picked = null;
                for (int i = 0; i < 50 && picked == null && !survey.complete(); i++) {
                    TargetIndex.clientTick(world.level); picked = survey.tick();
                }
                check(picked == null && survey.complete(), "isolated sources finish the scan without a pool");
                var obs = survey.observations();
                check("observed".equals(obs.get("source_lava_observation"))
                        && "source_lava_but_no_compliant_pool".equals(obs.get("candidates_status")),
                        "source-lava-but-no-compliant-pool is distinct from zero-source");
            }
        }
        try (var world = new InteractionWorldTestHarness()) {
            seedPool(world, new BlockPos(8, 1, 7), false);
            var origin = world.player.blockPosition();
            try (var survey = new PortalCastingSurvey(world.level, origin, 32)) {
                NetherPortalCastingLayout picked = null;
                for (int i = 0; i < 50 && picked == null; i++) {
                    TargetIndex.clientTick(world.level); picked = survey.tick();
                }
                check(picked != null, "an all-unreachable pool is still handed to PREPARE_SITE for the real verdict");
                var obs = survey.observations();
                check("observed".equals(obs.get("source_lava_observation"))
                        && "all_unreachable_at_selection".equals(obs.get("candidates_status")),
                        "all-unreachable is distinct from zero-source and no-compliant-pool");
            }
        }
    }

    /** 反例守卫：泥土路、楼梯这类真实可站面不得被探针误判为不可达。 */
    private static void antiBlindnessReachableStance() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var layout = new NetherPortalCastingLayout(new BlockPos(7, 1, 7), Direction.NORTH);
            for (int x = -1; x <= 2; x++) {
                world.set(layout.cell(x, 0, 0), Blocks.LAVA.defaultBlockState());
                world.set(layout.cell(x, 0, 1), Blocks.STONE.defaultBlockState());
            }
            for (BlockPos floor : PortalCastingTerrain.platform(layout)) world.set(floor, Blocks.DIRT_PATH.defaultBlockState());
            check(PortalCastingSurvey.reachableAt(world.level, layout).accepted(),
                    "a dirt-path platform is genuinely standable");
            for (BlockPos floor : PortalCastingTerrain.platform(layout)) world.set(floor, Blocks.OAK_STAIRS.defaultBlockState());
            check(PortalCastingSurvey.reachableAt(world.level, layout).accepted(),
                    "a stair-top platform is genuinely standable");
            for (BlockPos floor : PortalCastingTerrain.platform(layout)) world.set(floor, Blocks.WATER.defaultBlockState());
            var flooded = PortalCastingSurvey.reachableAt(world.level, layout);
            check(!flooded.accepted(), "a fully flooded platform is the genuine unreachable case");
        }
    }

    /** B3：契约写明岩浆池既是材料来源也是门址，桶装岩浆不能顶替。 */
    private static void contractStatesPoolIsSourceAndSite() {
        JsonObject desc = SemanticAbilityCatalog.describe("maicraft:prepare_portal");
        String text = flatten(desc);
        check(text.contains("both the material source and the portal site"),
                "B3 contract states the pool is both material source and portal site");
        check(text.contains("carried lava bucket does not substitute"),
                "B3 contract forbids substituting a carried lava bucket for the pool");
    }

    /**
     * 造一片 4×8 的静源岩浆池。reachable=true 时池周（含两侧岸）铺石头，探针与真实身体都站得上去；
     * reachable=false 时把池周边整片灌水、不留岸——水面不可站、空气无支撑，探针找不到任何干燥站立格。
     * 灌水矩形覆盖候选站台的全部包络（跨 -2..3、后 1..4），且不超出单一区块（0..15）。
     */
    private static void seedPool(InteractionWorldTestHarness world, BlockPos seed, boolean reachable) {
        for (int z = 0; z < 8; z++) for (int x = -1; x <= 2; x++)
            world.set(seed.offset(x, 0, z), Blocks.LAVA.defaultBlockState());
        int x0 = Math.max(0, seed.getX() - 2), x1 = Math.min(15, seed.getX() + 5);
        int z0 = Math.max(0, seed.getZ() - 1), z1 = Math.min(15, seed.getZ() + 9);
        for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
            BlockPos at = new BlockPos(x, seed.getY(), z);
            if (world.level.getBlockState(at).is(Blocks.LAVA)) continue;
            world.set(at, reachable ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState());
        }
    }

    private static String flatten(JsonObject json) {
        StringBuilder sb = new StringBuilder();
        collect(json, sb);
        return sb.toString();
    }

    private static void collect(com.google.gson.JsonElement element, StringBuilder sb) {
        if (element.isJsonObject()) {
            for (var member : element.getAsJsonObject().entrySet()) collect(member.getValue(), sb);
        } else if (element.isJsonArray()) {
            for (var item : element.getAsJsonArray()) collect(item, sb);
        } else if (element.isJsonPrimitive()) {
            sb.append(element.getAsString()).append('\n');
        }
    }
}
