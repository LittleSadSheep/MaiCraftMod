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

/** 用完整空气房间检验选址：最近偏移仍撞墙时继续搜索，未知区块不能证明更近位置可用。 */
public final class BuildClearanceSurveyTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var targets = List.of(air(0, 64, 0), air(1, 64, 0));
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        // 相邻两格蓝图的五个方向都撞到砖墙，只有整体向东平移两格才最先完全避开。
        for (var at : List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0), new BlockPos(-1, 64, 0),
                new BlockPos(0, 64, -1), new BlockPos(0, 64, 1), new BlockPos(1, 64, -1), new BlockPos(1, 64, 1),
                new BlockPos(-1, 64, -1), new BlockPos(-1, 64, 1))) blocks.put(at, Blocks.BRICKS.defaultBlockState());
        var reads = new AtomicInteger();
        var survey = new BuildClearanceSurvey(targets, ReplaceMode.REPLACE_EMPTY, false, "minecraft:overworld",
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
        System.out.println("BuildClearanceSurveyTest: passed");
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
