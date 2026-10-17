// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 贴边处境的生产读取：脚下的方块四周哪一边是深落差、身体离边沿多近、动量指没指着它。
 *
 * <p>只读不写：逐个看水平四邻，邻格脚下悬空就算一边崖；边沿距离按身体到那一面的横向距离算。
 * 退回的落点先试背对崖边两格、一格，再试另外两侧；落点要脚下踩得住、不是另一道深落差、
 * 身体放得进去，一个都没有就给 null（四面都是崖的独柱上不退）。
 */
public final class LiveEdgeView implements EdgeProximityNeed.ReadsEdge {

    /** 多深算深落差的判定与需求共用同一常量。 */
    private static final int DROP_SCAN = 6;

    @Override
    public EdgeProximityNeed.Facts read(TickContext context) {
        var player = context.player();
        ClientLevel level = player == null ? null : player.level();
        var self = player == null ? null : player.localPlayer();
        if (level == null || self == null) {
            return null;
        }
        Vec3 center = self.position();
        BlockPos foot = self.blockPosition();
        double nearestEdge = Double.MAX_VALUE;
        double deepestDrop = 0;
        Vec3 nearestEdgeCenter = null;
        for (Direction direction : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos neighbour = foot.relative(direction);
            int drop = dropBelow(level, neighbour);
            if (drop >= (int) EdgeProximityNeed.DEEP_DROP) {
                Vec3 faceCenter = Vec3.atCenterOf(neighbour)
                        .subtract(new Vec3(direction.getStepX(), 0, direction.getStepZ()).scale(0.5));
                double toEdge = Math.abs(
                        (center.x - faceCenter.x) * direction.getStepX()
                                + (center.z - faceCenter.z) * direction.getStepZ());
                if (toEdge < nearestEdge) {
                    nearestEdge = toEdge;
                    deepestDrop = drop;
                    nearestEdgeCenter = faceCenter;
                }
            }
        }
        if (nearestEdgeCenter == null) {
            return null;
        }
        Vec3 toward = nearestEdgeCenter.subtract(center).normalize();
        return new EdgeProximityNeed.Facts(nearestEdge, deepestDrop, safeSpot(level, foot, toward));
    }

    // 找退回的落点：背对崖边两格、一格，再试左右两侧；脚下踩得住、不是另一道深落差、身体放得进去才算。
    private static double[] safeSpot(ClientLevel level, BlockPos foot, Vec3 toward) {
        Vec3 away = toward.scale(-1.0);
        Vec3 side = new Vec3(-away.z, 0, away.x);
        Vec3[] tries = {away.scale(2.0), away, side, side.scale(-1.0)};
        for (Vec3 offset : tries) {
            BlockPos cell = BlockPos.containing(foot.getX() + 0.5 + offset.x, foot.getY(), foot.getZ() + 0.5 + offset.z);
            if (standable(level, cell)) {
                return new double[] {cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5};
            }
        }
        return null;
    }

    // 站得住：脚下那格有碰撞、脚和头两格空着、而且不是深落差的边外。
    private static boolean standable(ClientLevel level, BlockPos cell) {
        if (!level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()) return false;
        if (!level.getBlockState(cell.above()).getCollisionShape(level, cell.above()).isEmpty()) return false;
        return dropBelow(level, cell) == 1;
    }

    /** 从一格往下数多少格是空的；碰到能站的方块就停。 */
    private static int dropBelow(ClientLevel level, BlockPos cell) {
        int depth = 0;
        BlockPos cursor = cell;
        while (depth < DROP_SCAN) {
            cursor = cursor.below();
            depth++;
            if (!level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty()) {
                return depth;
            }
        }
        return DROP_SCAN;
    }
}
