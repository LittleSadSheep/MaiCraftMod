// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 选床：给一批候选床，按"能睡"过滤、按路径代价排序的纯函数。公开睡觉与夜间自动休息共用这一份。
 *
 * <p>候选床的检查结论（占用、保护、怪物）由读端现场判，这里只做筛选与排序：
 * 床区里睡只在床区半径内选，试过不行的床不再试，剩下的按离角色近的优先。
 */
public final class BedChooser {

    /** 床区半径：给了目标对象时只在这片范围里选床，太远的床不算"这片"的。玩家常识。 */
    static final int BED_AREA_RADIUS_BLOCKS = 16;

    /**
     * 夜里自己去睡最远走多远（格，直线）：天黑了路上刷怪，走一百多格去睡上一晚野外那张床，半路被僵尸打掉大半血，
     * 睡完还要再走回来。更远的床夜里不去：身上带着床就就地放下睡，没有就按夜晚的老办法挖坑或找掩体熬夜。
     * 所有者定的数。类别：玩家常识。
     */
    public static final double NIGHT_WALK_LIMIT_BLOCKS = 32;

    private BedChooser() {}

    /**
     * 从候选里选出一张能用的床：排除试过不行的、被占用的、受保护的、旁边有怪的；
     * 限定床区时只留床区半径内的，最后按离角色近的排序。
     */
    public static Optional<BedCandidate> choose(List<BedCandidate> candidates, Set<BlockPos> excluded,
            WorldPosition bedArea) {
        return choose(candidates, excluded, bedArea, Double.MAX_VALUE);
    }

    /** 同上，再只留离角色不超过 {@code maxDistance} 格的床：夜里自己去睡只走近处的床。 */
    public static Optional<BedCandidate> choose(List<BedCandidate> candidates, Set<BlockPos> excluded,
            WorldPosition bedArea, double maxDistance) {
        List<BedCandidate> usable = usable(candidates, excluded, bedArea).stream()
                .filter(bed -> bed.distance() <= maxDistance)
                .toList();
        return usable.isEmpty() ? Optional.empty() : Optional.of(usable.getFirst());
    }

    /** 能用的床按近到远排好；一张都没有时返回空列表。 */
    public static List<BedCandidate> usable(List<BedCandidate> candidates, Set<BlockPos> excluded,
            WorldPosition bedArea) {
        return candidates.stream()
                .filter(bed -> !excluded.contains(bed.cell()))
                .filter(bed -> !bed.occupied() && !bed.protectedLand() && !bed.hostileNear())
                .filter(bed -> bedArea == null
                        || withinArea(bed.cell(), bedArea, BED_AREA_RADIUS_BLOCKS))
                .sorted(Comparator.comparingDouble(BedCandidate::distance))
                .collect(Collectors.toList());
    }

    /** 一张床在不在床区半径内：按水平距离算，高度差是玩家常识里"这一片"的一部分，也计入。 */
    private static boolean withinArea(BlockPos cell, WorldPosition area, double radius) {
        double dx = cell.getX() - area.x();
        double dy = cell.getY() - area.y();
        double dz = cell.getZ() - area.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= radius;
    }
}
