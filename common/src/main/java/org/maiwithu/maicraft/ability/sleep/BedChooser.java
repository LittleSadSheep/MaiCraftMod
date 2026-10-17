// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import java.util.ArrayList;
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

    /**
     * 一次筛选的分类：扫到的床各有几张开不出去、还剩几张能用。
     * 每张床只归一个主因，按"试过 → 被占用 → 床边有怪 → 受保护 → 太远 → 出了床区"的先后归类，各类相加等于扫到的总数。
     */
    public record Screening(int scanned, int tried, int occupied, int hostileNear, int protectedLand,
            int tooFar, int outOfArea, int usable) {

        /** 给人看的一句话：附近有没有床、开不出去的开不在哪，找床失败的说明带上它才分得清"没床"和"有床睡不上"。 */
        public String describe() {
            if (scanned == 0) {
                return "附近扫过了，没有看到床块";
            }
            if (usable > 0) {
                return "扫到 " + scanned + " 张床，其中 " + usable + " 张能睡";
            }
            StringBuilder why = new StringBuilder("扫到 ").append(scanned).append(" 张床，都睡不上");
            List<String> reasons = new ArrayList<>();
            if (tried > 0) reasons.add(tried + " 张试过睡不上");
            if (occupied > 0) reasons.add(occupied + " 张被占用");
            if (hostileNear > 0) reasons.add(hostileNear + " 张床边有怪");
            if (protectedLand > 0) reasons.add(protectedLand + " 张在受保护的地标里");
            if (tooFar > 0) reasons.add(tooFar + " 张超出这次肯走的距离");
            if (outOfArea > 0) reasons.add(outOfArea + " 张在指定床区之外");
            for (String reason : reasons) {
                why.append("：").append(reason);
            }
            return why.toString();
        }
    }

    /**
     * 只筛不选：把同一批候选按选床的规矩分类数一遍。
     * 找床失败时拿它说清"范围内确实没有床"还是"有床但都开不出去、开不在哪"，下游不会把两种情况混成一句"没有床"。
     */
    public static Screening screen(List<BedCandidate> candidates, Set<BlockPos> excluded,
            WorldPosition bedArea, double maxDistance) {
        int tried = 0;
        int occupied = 0;
        int hostileNear = 0;
        int protectedLand = 0;
        int tooFar = 0;
        int outOfArea = 0;
        int usable = 0;
        for (BedCandidate bed : candidates) {
            if (excluded.contains(bed.cell())) {
                tried++;
            } else if (bed.occupied()) {
                occupied++;
            } else if (bed.hostileNear()) {
                hostileNear++;
            } else if (bed.protectedLand()) {
                protectedLand++;
            } else if (bed.distance() > maxDistance) {
                tooFar++;
            } else if (bedArea != null && !withinArea(bed.cell(), bedArea, BED_AREA_RADIUS_BLOCKS)) {
                outOfArea++;
            } else {
                usable++;
            }
        }
        return new Screening(candidates.size(), tried, occupied, hostileNear, protectedLand, tooFar, outOfArea, usable);
    }

    /** 一张床在不在床区半径内：按水平距离算，高度差是玩家常识里"这一片"的一部分，也计入。 */
    private static boolean withinArea(BlockPos cell, WorldPosition area, double radius) {
        double dx = cell.getX() - area.x();
        double dy = cell.getY() - area.y();
        double dz = cell.getZ() - area.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= radius;
    }
}
