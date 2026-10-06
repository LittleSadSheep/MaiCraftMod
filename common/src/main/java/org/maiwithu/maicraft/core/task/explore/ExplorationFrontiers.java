// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.explore;

import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

/** 扇区里按不同角度寻找已加载的新落点；窄扇区也能推进，不要求恰好撞上整数网格。 */
public final class ExplorationFrontiers {
    private ExplorationFrontiers() {}

    /**
     * 到达容差：航点落点上限与跑图 radius_reached 判定带共用的同一份提前量。
     * 身体走到带内航点即已达成请求半径，不会在收尾判定生效前再向硬边界外推进；
     * 余量不超过硬边界的越界容忍（radius+8），航点即使带一点冲过也停在硬边界内。
     */
    public static double arrivalMargin(int radius) {
        return Math.min(8.0, radius / 8.0);
    }

    public static BlockPos next(BlockPos current, ExplorationSector.Area sector, int radius,
            Set<Long> attempted, Predicate<BlockPos> loaded) {
        return next(current, sector, radius, attempted, loaded, null);
    }

    /**
     * {@code waterPenalty} 标记穿水路线的候选：存在干地候选时穿水候选一律让位，
     * 全部候选都穿水（如身处岛屿）时仍选出得分最高者继续推进。只表达优先级，
     * 不构成"某方向不可行"的结论。
     */
    public static BlockPos next(BlockPos current, ExplorationSector.Area sector, int radius,
            Set<Long> attempted, Predicate<BlockPos> loaded, Predicate<BlockPos> waterPenalty) {
        BlockPos best = null;
        double bestScore = -Double.MAX_VALUE;
        boolean bestCrossesWater = true;
        double half = sector.request().angleDegrees() / 2.0;
        // 航点落在到达容差带内即可：身体走到航点就已进入 radius_reached 判定带，
        // 不会在收尾判定生效前沿直线越过请求半径再被硬边界截停。
        double reachMargin = arrivalMargin(radius);
        for (int ray = 0; ray <= 16; ray++) {
            double angle = Math.toRadians(sector.bearing() - half + ray * half / 8);
            for (int distance = 80; distance >= 16; distance -= 16) {
                BlockPos candidate = new BlockPos(current.getX() + (int) Math.round(Math.sin(angle) * distance),
                        current.getY(), current.getZ() - (int) Math.round(Math.cos(angle) * distance));
                double fromOrigin = Math.hypot(candidate.getX() - sector.originX(), candidate.getZ() - sector.originZ());
                if (fromOrigin > radius - reachMargin || !sector.contains(candidate.getX(), candidate.getZ()) || !loaded.test(candidate)) continue;
                // 已走过或已证实走不通的十六格邻域不再反复选；真正可达性交给原生移动核实。
                if (attempted.stream().map(BlockPos::of).anyMatch(previous ->
                        Math.hypot(previous.getX() - candidate.getX(), previous.getZ() - candidate.getZ()) < 16)) continue;
                boolean crossesWater = waterPenalty != null && waterPenalty.test(candidate);
                double score = fromOrigin + distance * 0.2 - Math.abs(ray - 8) * 0.1;
                boolean better;
                if (best == null) better = true;
                else if (crossesWater != bestCrossesWater) better = bestCrossesWater;
                else better = score > bestScore;
                if (better) {
                    best = candidate;
                    bestScore = score;
                    bestCrossesWater = crossesWater;
                }
            }
        }
        if (best != null) attempted.add(BlockPos.asLong(best.getX(), 0, best.getZ()));
        return best;
    }
}
