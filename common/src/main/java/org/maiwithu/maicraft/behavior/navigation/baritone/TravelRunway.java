// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import baritone.api.pathing.movement.IMovement;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementTraverse;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** 所选路线中剩余的直线路径，与移动分段方式无关；斜着走的锯齿路线取贴着它的那条直线。 */
record TravelRunway(Vec3 start, Vec3 heading, List<IMovement> movements) {
    static boolean accepts(IMovement movement) {
        return (movement instanceof MovementTraverse || movement instanceof MovementDiagonal)
                && movement.getSrc().getY() == movement.getDest().getY();
    }

    /** 路线上的格子离起跳直线这么远（格）以内，仍算同一条直道：斜着走时一格一拐的锯齿也能连成一条跑跳直线。 */
    static final double LATERAL_SLACK = 0.6;

    // 往前收同一层、首尾相接的平地移动，再取最长的一段：段里每一步的落点都贴着"起点到段尾"那条直线。
    // 原先要求每一步方向完全一样，野外路线朝斜方向走时是"南、西南、南、西南"交替，直道只有一两格，
    // 疾跑从来起不了跳（实测 300 格回程只疾跑不跳，平均 3 格/秒）；真人这时是沿斜线一路跑跳。
    // 直线上的落脚、净空、坑洞仍由走廊几何逐格核过，这里只决定沿哪条直线核。
    static TravelRunway capture(List<IMovement> path, int position, Vec3 feet) {
        if (position < 0 || position >= path.size() || !accepts(path.get(position))) return null;
        if (!Double.isFinite(feet.lengthSqr())) return null;
        IMovement first = path.get(position);
        var flat = new ArrayList<IMovement>();
        IMovement previous = null;
        for (int i = position; i < path.size(); i++) {
            IMovement movement = path.get(i);
            if (!accepts(movement) || movement.getSrc().getY() != first.getSrc().getY()) break;
            if (previous != null && !previous.getDest().equals(movement.getSrc())) break;
            flat.add(movement);
            previous = movement;
            if (end(movement).subtract(feet).horizontalDistance() >= GroundCorridor.MAX_LENGTH) break;
        }
        Vec3 origin = Vec3.atBottomCenterOf(first.getSrc());
        Vec3 direction = null;
        int last = -1;
        for (int k = 0; k < flat.size(); k++) {
            Vec3 chord = end(flat.get(k)).subtract(origin).multiply(1, 0, 1);
            if (chord.lengthSqr() < .99) break;
            Vec3 heading = chord.normalize();
            if (!hugs(flat.subList(0, k + 1), origin, heading)) break;
            direction = heading;
            last = k;
        }
        return direction == null ? null : new TravelRunway(feet, direction, List.copyOf(flat.subList(0, last + 1)));
    }

    // 每一步的落点都在起点前方、离直线不超过半格多一点。
    private static boolean hugs(List<IMovement> movements, Vec3 origin, Vec3 heading) {
        for (IMovement movement : movements) {
            Vec3 point = end(movement).subtract(origin);
            double along = point.x * heading.x + point.z * heading.z;
            double side = Math.abs(-heading.z * point.x + heading.x * point.z);
            if (along <= 0 || side > LATERAL_SLACK) return false;
        }
        return true;
    }

    List<IMovement> covering(double distance) {
        if (!Double.isFinite(distance) || distance <= 0) return List.of();
        for (int i = 0; i < movements.size(); i++) {
            if (end(movements.get(i)).subtract(start).dot(heading) >= distance)
                return List.copyOf(movements.subList(0, i + 1));
        }
        return List.of();
    }

    Vec3 point(double distance) { return start.add(heading.scale(distance)); }
    private static Vec3 end(IMovement movement) {
        return Vec3.atBottomCenterOf(movement.getDest());
    }
}
