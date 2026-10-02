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
        System.out.println("ExplorationFrontiersTest: passed");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
