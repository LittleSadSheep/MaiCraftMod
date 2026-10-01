package org.maiwithu.maicraft.core.task.build;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import java.util.Set;

/** 用完整空气房间检验选址：最近偏移仍撞墙时继续搜索，未知区块不能证明更近位置可用。 */
public final class BuildClearanceSurveyTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        replaceableCellsNeedNoExcavation();
        authorizedWaterloggedSolidCanBeCleared();
        var targets = List.of(air(0, 64, 0), air(1, 64, 0));
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        // 相邻两格蓝图的五个方向都撞到砖墙，只有整体向东平移两格才最先完全避开。
        for (var at : List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0), new BlockPos(-1, 64, 0),
                new BlockPos(0, 64, -1), new BlockPos(0, 64, 1), new BlockPos(1, 64, -1), new BlockPos(1, 64, 1),
                new BlockPos(-1, 64, -1), new BlockPos(-1, 64, 1))) blocks.put(at, Blocks.BRICKS.defaultBlockState());
        var reads = new AtomicInteger();
        // 用户明确禁止拆换时才需要寻找空地；普通已授权声明格不再受材料白名单限制。
        var survey = new BuildClearanceSurvey(targets, ReplaceMode.DONT_REPLACE, false, "minecraft:overworld",
                at -> { reads.incrementAndGet(); return seen(blocks.getOrDefault(at, Blocks.AIR.defaultBlockState())); },
                (at, state) -> false);
        int ticks = 0;
        while (true) {
            int before = reads.get(); boolean done = survey.advance(1);
            check(reads.get() - before <= 1, "survey respects each tick's read budget");
            if (done) break;
            check(++ticks < 10000, "bounded survey finishes");
        }
        var report = survey.report();
        check(survey.blocked() && (int) report.get("conflict_count") == 2, "all original obstacles are counted");
        var obstacles = rows(report, "obstacles");
        check(obstacles.getFirst().get("at").equals(List.of(0, 64, 0)), "precise world coordinates are reported");
        var suggestions = rows(report, "suggested_offsets");
        check(suggestions.stream().anyMatch(row -> row.get("offset").equals(List.of(2, 0, 0))),
                "whole footprint is checked, not merely the first obstacle");
        check(suggestions.stream().allMatch(row -> (int) row.get("horizontal_distance_squared") == 4),
                "only shortest proven shifts are suggested");

        // 未加载的近点不会被当作空气；较远候选即使通过，也不能宣称已经证明绝对最小移动。
        var unknown = new BuildClearanceSurvey(List.of(air(0, 64, 0)), ReplaceMode.REPLACE_EMPTY, false,
                "minecraft:overworld", at -> at.getX() < 0 ? null
                        : seen(at.equals(new BlockPos(0, 64, 0)) ? Blocks.CHEST.defaultBlockState() : Blocks.AIR.defaultBlockState()),
                (at, state) -> false);
        while (!unknown.advance(16)) { }
        check(!rows(unknown.report(), "suggested_offsets").isEmpty(), "loaded alternatives still offered");
        check(!Boolean.TRUE.equals(((Map<?, ?>) unknown.report().get("search")).get("minimum_proven_in_radius")),
                "unknown closer sites prevent a minimum proof");

        var retained = new BuildTaskRecord.Target(Blocks.BRICKS.defaultBlockState(), Items.BRICK,
                BlockPos.ZERO, "existing_wall", null, null, null);
        var matching = new BuildClearanceSurvey(List.of(retained), ReplaceMode.REPLACE_EMPTY, false, "minecraft:overworld",
                at -> seen(Blocks.BRICKS.defaultBlockState()), (at, state) -> false);
        check(matching.advance(2) && !matching.blocked(), "already correct artificial walls are preserved");
        var scaffold = new BuildClearanceSurvey(List.of(air(0, 64, 0)), ReplaceMode.REPLACE_EMPTY, false,
                "minecraft:overworld", at -> seen(Blocks.COBBLESTONE.defaultBlockState()), (at, state) -> true);
        check(scaffold.advance(2) && !scaffold.blocked(), "owned temporary scaffolds remain available for cleanup");
        // 原址支撑已被别人换成砖墙时，不能把旧支撑的许可借给附近仅材质相同的方块来生成错误选址建议。
        BlockPos oldSupport = new BlockPos(0, 64, 0);
        var moved = new BuildClearanceSurvey(List.of(air(0, 64, 0)), ReplaceMode.DONT_REPLACE, false, "minecraft:overworld",
                at -> seen(at.equals(oldSupport) ? Blocks.BRICKS.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState()),
                (at, state) -> at.equals(oldSupport) && state.is(Blocks.COBBLESTONE));
        while (!moved.advance(128)) { }
        check(moved.blocked() && rows(moved.report(), "suggested_offsets").isEmpty(), "新址只使用自己的现场归属证据");
        System.out.println("BuildClearanceSurveyTest: passed");
    }

    private static void authorizedWaterloggedSolidCanBeCleared() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            BlockPos at = new BlockPos(2, 1, 2);
            var plan = new BuildTaskRecord("waterlogged", 500, List.of(air(2, 1, 2)), true);
            plan.automaticMachineModification(Set.of(at));
            var task = new FirstPersonBuildCompanionTask(world.player, plan);
            var clearing = FirstPersonBuildCompanionTask.class.getDeclaredField("clearing"); clearing.setAccessible(true); clearing.set(task, at);
            var allowed = FirstPersonBuildCompanionTask.class.getDeclaredMethod("clearingPermitted", BlockState.class); allowed.setAccessible(true);
            var slab = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true); world.set(at, slab);
            // 声明范围内含水半砖可先挖，再由原版水流更新；没有实体的水格与不可破坏方块仍不能用镐强行清空。
            check((boolean) allowed.invoke(task, slab), "已授权含水固体不能因 fluidState 非空被拦截");
            check(!(boolean) allowed.invoke(task, Blocks.WATER.defaultBlockState()), "纯水仍不是挖掘目标");
            check(!(boolean) allowed.invoke(task, Blocks.BEDROCK.defaultBlockState()), "不可破坏格仍如实拒绝");
        }
    }

    private static void replaceableCellsNeedNoExcavation() {
        var partition = new BuildTaskRecord.Target(Blocks.COBBLESTONE, Items.COBBLESTONE,
                BlockPos.ZERO, "channel_partition", null, null, null);
        // 水源、流水、岩浆与草都可被真实方块覆盖；这里仅取消挖掘要求，不代替后续原生放置证明。
        for (var live : List.of(Blocks.WATER.defaultBlockState(),
                Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, 3),
                Blocks.LAVA.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState())) {
            var survey = new BuildClearanceSurvey(List.of(partition), ReplaceMode.DONT_REPLACE, false,
                    "minecraft:overworld", at -> seen(live), (at, state) -> false);
            check(survey.advance(2) && !survey.blocked(), "replaceable cells do not require whitelist excavation");
        }
        check(BuildClearanceSurvey.needsClearance(air(0, 0, 0), Blocks.WATER.defaultBlockState()),
                "explicit air cannot erase water through a block-placement exemption");
        check(BuildClearanceSurvey.needsClearance(partition,
                Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)),
                "waterlogged construction still requires real demolition permission");
    }

    private static BuildTaskRecord.Target air(int x, int y, int z) {
        return new BuildTaskRecord.Target(Blocks.AIR.defaultBlockState(), Items.AIR, new BlockPos(x, y, z), "air", null, null, null);
    }
    private static BuildClearanceSurvey.Observation seen(BlockState state) {
        return new BuildClearanceSurvey.Observation(state, true, true);
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> report, String key) {
        return (List<Map<String, Object>>) report.get(key);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
