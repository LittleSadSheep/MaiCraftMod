// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

import java.util.Optional;
import java.util.function.Predicate;

import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

import org.maiwithu.maicraft.behavior.navigation.transport.TransportLanding;

/**
 * 飞着去时落在哪，纯函数：看过的、能站的格（不是流体、岩浆、细雪、传送门、滴水石锥、垂滴叶、点着的营火……），
 * 上面还要留出几格净空好垂直落下去。目标附近找一格；目标太远就朝它的方向飞一段，在那附近找一格先落下。
 */
public final class FlightLandings {

    /** 一列里从参考高度往上、往下各找这么多格。 */
    static final int COLUMN_SPAN = 24;

    private FlightLandings() {}

    /** 目标附近（水平 radius 格内）能落的一格，离目标越近越先；同样近的挑高度差小的。 */
    public static Optional<BlockPos> near(BlockGetter view, Predicate<BlockPos> loaded, BlockPos target, int radius,
            int clearance) {
        for (int ring = 0; ring <= radius; ring++) {
            BlockPos best = null;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    BlockPos found = column(view, loaded, target.offset(dx, 0, dz), clearance);
                    if (found != null && (best == null
                            || Math.abs(found.getY() - target.getY()) < Math.abs(best.getY() - target.getY()))) {
                        best = found;
                    }
                }
            }
            if (best != null) return Optional.of(best);
        }
        return Optional.empty();
    }

    /** 目标太远：朝目标方向飞 reach 格左右（最远 reach、最近一半），在那附近找能落的一格。 */
    public static Optional<BlockPos> ahead(BlockGetter view, Predicate<BlockPos> loaded, BlockPos from, BlockPos target,
            int reach, int clearance) {
        double dx = target.getX() - from.getX();
        double dz = target.getZ() - from.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1) return Optional.empty();
        for (int distance = reach; distance >= reach / 2; distance -= 4) {
            BlockPos point = BlockPos.containing(from.getX() + dx / length * distance, from.getY(),
                    from.getZ() + dz / length * distance);
            Optional<BlockPos> found = near(view, loaded, point, 4, clearance);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    /** 这一格能落：站得住，脚上面 clearance 格（至少两格：身体加头顶一格）都是看过的空气。 */
    public static boolean landable(BlockGetter view, Predicate<BlockPos> loaded, BlockPos feet, int clearance) {
        if (!loaded.test(feet) || !loaded.test(feet.below())) return false;
        if (TransportLanding.inspect(view, loaded, feet, 0.6, 1.8, LongSets.emptySet()).destination() == null) return false;
        for (int up = 0; up < Math.max(clearance, 2); up++) {
            if (!FlightRoutes.open(view, loaded, feet.above(up))) return false;
        }
        return true;
    }

    // 一列里离参考高度最近、能落的那一格。
    private static BlockPos column(BlockGetter view, Predicate<BlockPos> loaded, BlockPos reference, int clearance) {
        for (int offset = 0; offset <= COLUMN_SPAN; offset++) {
            BlockPos up = reference.above(offset);
            if (landable(view, loaded, up, clearance)) return up;
            if (offset == 0) continue;
            BlockPos down = reference.below(offset);
            if (landable(view, loaded, down, clearance)) return down;
        }
        return null;
    }
}
