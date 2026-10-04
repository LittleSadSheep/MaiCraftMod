package org.maiwithu.maicraft.core.task.explore;

import java.util.HashSet;
import net.minecraft.core.BlockPos;

/** 地形只在侧前方可见时仍允许扇区推进；不能用反方向已加载地形替代用户方向。 */
public final class ExplorationFrontiersTest {
    public static void main(String[] args) {
        var area = ExplorationSector.of("north", 90, null, 256).at(0, 0, 0);
        var attempted = new HashSet<Long>();
        BlockPos first = ExplorationFrontiers.next(BlockPos.ZERO, area, 256, attempted, p -> p.getX() > 20);
        check(first != null && area.contains(first.getX(), first.getZ()), "find a side-front route without a straight route");
        BlockPos second = ExplorationFrontiers.next(BlockPos.ZERO, area, 256, attempted, p -> p.getX() > 20);
        check(second == null || Math.hypot(second.getX() - first.getX(), second.getZ() - first.getZ()) >= 16,
                "failed waypoint neighborhoods are not repeatedly selected");
        check(ExplorationFrontiers.next(BlockPos.ZERO, area, 256, new HashSet<>(), p -> p.getZ() > 0) == null,
                "do not search the opposite direction when only it is loaded");
        var narrow = ExplorationSector.of("forward", 1, 0, 256).at(0, 0, -163);
        BlockPos narrowPoint = ExplorationFrontiers.next(BlockPos.ZERO, narrow, 256, new HashSet<>(), p -> true);
        check(narrowPoint != null && narrow.contains(narrowPoint.getX(), narrowPoint.getZ()), "non-cardinal narrow sector can advance");
        // 穿水候选即便得分更高（离搜索原点更远）也让位给干地候选；全线皆水仍选出最优者，岛屿环境不卡死。
        var lakeside = ExplorationSector.of("north", 90, null, 256).at(0, 200, 0);
        BlockPos dryPick = ExplorationFrontiers.next(BlockPos.ZERO, lakeside, 256, new HashSet<>(),
                p -> true, p -> p.getZ() > -48);
        check(dryPick != null && dryPick.getZ() <= -48,
                "dry candidate preferred even when water candidates score higher");
        var island = ExplorationSector.of("north", 90, null, 256).at(0, 0, 0);
        BlockPos wetPick = ExplorationFrontiers.next(BlockPos.ZERO, island, 256, new HashSet<>(),
                p -> true, p -> true);
        check(wetPick != null && island.contains(wetPick.getX(), wetPick.getZ()),
                "all-water environment still selects the best candidate");
        System.out.println("ExplorationFrontiersTest: passed");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
