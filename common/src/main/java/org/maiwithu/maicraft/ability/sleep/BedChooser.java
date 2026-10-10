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

    private BedChooser() {}

    /**
     * 从候选里选出一张能用的床：排除试过不行的、被占用的、受保护的、旁边有怪的；
     * 限定床区时只留床区半径内的，最后按离角色近的排序。
     */
    public static Optional<BedCandidate> choose(List<BedCandidate> candidates, Set<BlockPos> excluded,
            WorldPosition bedArea) {
        List<BedCandidate> usable = usable(candidates, excluded, bedArea);
        return usable.isEmpty() ? Optional.empty() : Optional.of(usable.getFirst());
    }

    /** 能用的床按近到远排好；一张都没有时返回空列表。 */
    public static List<BedCandidate> usable(List<BedCandidate> candidates, Set<BlockPos> excluded,
            WorldPosition bedArea) {
        return candidates.stream()
                .filter(bed -> !excluded.contains(bed.head()))
                .filter(bed -> !bed.occupied() && !bed.protectedLand() && !bed.hostileNear())
                .filter(bed -> bedArea == null
                        || withinArea(bed.head(), bedArea, BED_AREA_RADIUS_BLOCKS))
                .sorted(Comparator.comparingDouble(BedCandidate::distance))
                .collect(Collectors.toList());
    }

    /** 一张床在不在床区半径内：按水平距离算，高度差是玩家常识里"这一片"的一部分，也计入。 */
    private static boolean withinArea(BlockPos head, WorldPosition area, double radius) {
        double dx = head.getX() - area.x();
        double dy = head.getY() - area.y();
        double dz = head.getZ() - area.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= radius;
    }
}
