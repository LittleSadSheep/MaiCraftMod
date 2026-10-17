// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import org.maiwithu.maicraft.kernel.goal.Target;

/**
 * 扇形：探索候选的范围约束——朝哪个罗盘方位、开口多大、起找与最远多远。
 *
 * <p>方位在出发时按当时的视角换算成罗盘方位角并固定（北 0°、东 90°、南 180°、西 270°，
 * 顺时针增大），之后转头不改变搜索方向；方向词与目标对象 direction 是同一套八向词。
 * 全向探索没有方位，只有距离约束（开口 360°）。
 *
 * @param centerBearing 扇形中线方位角（从北顺时针的度数）；全向时无意义
 * @param angleDegrees  开口角（度），1..360；360 表示全向
 * @param minDistance   起找距离（格）：更近的不算候选
 * @param maxDistance   最远距离（格）：更远的不算候选
 */
public record ExplorationSector(double centerBearing, double angleDegrees,
        double minDistance, double maxDistance) {

    public ExplorationSector {
        if (angleDegrees < 1 || angleDegrees > 360) {
            throw new IllegalArgumentException("扇形开口角应在 1..360：" + angleDegrees);
        }
        if (minDistance < 0 || maxDistance <= 0 || minDistance > maxDistance) {
            throw new IllegalArgumentException("扇形的起找距离与最远距离不合理："
                    + minDistance + ".." + maxDistance);
        }
    }

    /** 全向：没有方位，只要距离在范围内。 */
    public static ExplorationSector allAround(double minDistance, double maxDistance) {
        return new ExplorationSector(0, 360, minDistance, maxDistance);
    }

    /**
     * 出发时定扇形：八向词按当时的视角换算成罗盘方位并固定。
     * 相对的前后左右随当时的朝向折算；东南西北直接用世界方位。
     */
    public static ExplorationSector fix(Target.Toward toward, float facingYawDegrees,
            double angleDegrees, double minDistance, double maxDistance) {
        double facing = bearingOfYaw(facingYawDegrees);
        double bearing = switch (toward) {
            case NORTH -> 0;
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            case FORWARD -> facing;
            case BACKWARD -> facing + 180;
            case RIGHT -> facing + 90;
            case LEFT -> facing - 90;
        };
        return new ExplorationSector(normalize(bearing), angleDegrees, minDistance, maxDistance);
    }

    /** 一个相对探索原点的水平偏移（dx 东正、dz 南正）在不在扇形里。 */
    public boolean contains(double dx, double dz) {
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < minDistance || distance > maxDistance) {
            return false;
        }
        if (angleDegrees >= 360) {
            return true;
        }
        double offset = normalize(bearingOf(dx, dz) - centerBearing);
        if (offset > 180) {
            offset -= 360;
        }
        return Math.abs(offset) <= angleDegrees / 2;
    }

    /** 一个点相对探索原点的水平偏移与扇形中线差多少度（带符号，右偏为正）。 */
    public double eccentricity(double dx, double dz) {
        double offset = normalize(bearingOf(dx, dz) - centerBearing);
        return offset > 180 ? offset - 360 : offset;
    }

    /** 顺时针转一个 45°：换向升级的方位轮换在内部用角度表达。 */
    public ExplorationSector rotatedClockwise() {
        return new ExplorationSector(normalize(centerBearing + 45), angleDegrees, minDistance, maxDistance);
    }

    /** 视角角（0 朝南、顺时针增大）换罗盘方位角。 */
    private static double bearingOfYaw(float facingYawDegrees) {
        // 前方向向量 dx = -sin(yaw)、dz = cos(yaw)，罗盘方位角 = atan2(dx, -dz)。
        return normalize(Math.toDegrees(Math.atan2(-Math.sin(Math.toRadians(facingYawDegrees)),
                -Math.cos(Math.toRadians(facingYawDegrees)))));
    }

    /** 水平偏移换罗盘方位角：北是 -Z，东是 +X。 */
    private static double bearingOf(double dx, double dz) {
        return normalize(Math.toDegrees(Math.atan2(dx, -dz)));
    }

    private static double normalize(double degrees) {
        double value = degrees % 360;
        return value < 0 ? value + 360 : value;
    }
}
