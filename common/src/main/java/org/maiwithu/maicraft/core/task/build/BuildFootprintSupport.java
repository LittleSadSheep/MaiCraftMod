// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.TreeSet;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 区分完整地面与只有部分脚底接触的边缘；按实际碰撞顶面覆盖检查，不按方块名或离格心距离决定是否潜行。 */
final class BuildFootprintSupport {
    private static final double EPS = 1e-6;
    private BuildFootprintSupport() {}

    static boolean complete(BlockGetter world, Predicate<BlockPos> loaded, double width, Vec3 from, Vec3 to, Vec3 drift) {
        try { return inspect(world, loaded, width, from, to, drift); }
        catch (RuntimeException | LinkageError unavailable) { return false; }
    }

    private static boolean inspect(BlockGetter world, Predicate<BlockPos> loaded, double width, Vec3 from, Vec3 to, Vec3 drift) {
        if (!Double.isFinite(width) || width <= 0 || width > 2 || from.distanceToSqr(to) > 2.25
                || Math.abs(from.y - to.y) > .5 + EPS) return false;
        double half = width / 2;
        AABB footprint = new AABB(Math.min(from.x, Math.min(to.x, drift.x)) - half + EPS, 0,
                Math.min(from.z, Math.min(to.z, drift.z)) - half + EPS,
                Math.max(from.x, Math.max(to.x, drift.x)) + half - EPS, 1,
                Math.max(from.z, Math.max(to.z, drift.z)) + half - EPS);
        var supports = new ArrayList<AABB>(); int cells = 0;
        // 半阶允许上下两个真实台面共同覆盖；稍后逐个检查脚底扫过的空隙，斜向移动不借包围矩形多余的角落误判临边。
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(footprint.minX - 1, Math.min(from.y, to.y) - 2, footprint.minZ - 1),
                BlockPos.containing(footprint.maxX + 1, Math.max(from.y, to.y), footprint.maxZ + 1))) {
            if (++cells > 512 || !loaded.test(pos)) return false;
            for (AABB local : world.getBlockState(pos).getCollisionShape(world, pos).toAabbs()) {
                AABB box = local.move(pos);
                if ((Math.abs(box.maxY - from.y) <= EPS || Math.abs(box.maxY - to.y) <= EPS)
                        && box.maxX > footprint.minX && box.minX < footprint.maxX && box.maxZ > footprint.minZ && box.minZ < footprint.maxZ)
                    supports.add(box);
                if (supports.size() > 512) return false;
            }
        }
        var cuts = new TreeSet<Double>(); cuts.add(footprint.minX); cuts.add(footprint.maxX);
        for (AABB box : supports) { cuts.add(Math.clamp(box.minX, footprint.minX, footprint.maxX)); cuts.add(Math.clamp(box.maxX, footprint.minX, footprint.maxX)); }
        var xs = new ArrayList<>(cuts);
        for (int i = 1; i < xs.size(); i++) {
            double x = (xs.get(i - 1) + xs.get(i)) / 2, covered = footprint.minZ;
            var spans = supports.stream().filter(box -> box.minX <= x && box.maxX >= x).sorted(Comparator.comparingDouble(box -> box.minZ)).toList();
            for (AABB span : spans) {
                if (span.minZ > covered + EPS && gap(xs.get(i - 1), xs.get(i), covered, Math.min(span.minZ, footprint.maxZ), half, from, to, drift)) return false;
                covered = Math.max(covered, span.maxZ);
                if (covered >= footprint.maxZ) break;
            }
            if (covered < footprint.maxZ - EPS && gap(xs.get(i - 1), xs.get(i), covered, footprint.maxZ, half, from, to, drift)) return false;
        }
        return !supports.isEmpty();
    }

    private static boolean gap(double left, double right, double near, double far, double half, Vec3 from, Vec3 to, Vec3 drift) {
        if (far <= near + EPS) return false;
        AABB missing = new AABB(left - half + EPS, 0, near - half + EPS, right + half - EPS, 1, far + half - EPS);
        Vec3 start = new Vec3(from.x, .5, from.z), end = new Vec3(to.x, .5, to.z), momentum = new Vec3(drift.x, .5, drift.z);
        return missing.contains(start) || missing.contains(end) || missing.contains(momentum)
                || missing.clip(start, end).isPresent() || missing.clip(start, momentum).isPresent();
    }
}
