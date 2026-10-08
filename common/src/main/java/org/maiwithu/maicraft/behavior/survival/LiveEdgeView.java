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
 * <p>只读不写：逐个看水平四邻，邻格脚下悬空就算一边崖；边沿距离按身体到那一面的横向距离算，
 * 动量按这刻的水平速度与指向崖边的方向点积算。
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
        Vec3 momentum = self.getDeltaMovement();
        Vec3 toward = nearestEdgeCenter.subtract(center).normalize();
        boolean momentumToward = momentum.horizontalDistance() > 0.05 && momentum.normalize().dot(toward) > 0.5;
        // 退回安全处：背对崖边一格，站回自己站的这侧。
        Vec3 away = toward.scale(-2.0);
        double[] safeSpot = {center.x + away.x, center.y, center.z + away.z};
        return new EdgeProximityNeed.Facts(nearestEdge, deepestDrop, momentumToward, safeSpot);
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
