// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.sable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 静态受力（纯函数）：两种情况——开着动力（此刻记到的全部力）与关着动力（去掉推进）——绕重心的力矩换成角加速度，
 * 看往哪边倒、倒得多快；升力比重量；要配重时在能放方块的空格里挑放一块能让两种情况都倒得慢的位置（重心跟着挪也算进去）。
 * 只算静态，不做时域仿真；阻力跟着速度走，停着时可以不管，不算进来。预测不是实飞。
 */
final class ForceBalance {

    /** Sable 的推进与阻力力组。 */
    static final String PROPULSION = "sable:propulsion";
    static final String DRAG = "sable:drag";
    /** 两根水平轴的角加速度都在这以内（度每二次方秒）算不倒。 */
    static final double STEADY = 1.0;
    /** 配重要让最坏的那种情况好过这么多（比例）才建议。 */
    static final double WORTH = 0.05;

    /**
     * 一种情况。
     *
     * @param name     开着动力 / 关着动力
     * @param torque   绕重心的合力矩（结构局部坐标）
     * @param tiltX    绕结构自己的 x 轴的角加速度（度每二次方秒）
     * @param tiltZ    绕结构自己的 z 轴的角加速度
     * @param steady   两根水平轴都在门槛以内
     * @param tendency 一句话：稳着，或往哪头倒、倒得多快
     */
    record Case(String name, Vec3 torque, double tiltX, double tiltZ, boolean steady, String tendency) {}

    /**
     * 一处配重。
     *
     * @param local  放在哪一格（结构局部坐标）
     * @param mass   放上去多重
     * @param before 放之前两种情况里最坏的那个（两根水平轴角加速度的绝对值之和）
     * @param after  放之后的
     */
    record Ballast(BlockPos local, double mass, double before, double after) {}

    private ForceBalance() {}

    /** 此刻记到推进力：动力开着。 */
    static boolean powered(StructureForceReads.Forces forces) {
        return forces.pushes().stream().anyMatch(push -> push.group().equals(PROPULSION) && push.force().length() > 1e-6);
    }

    /** 开着动力（含推进）或关着动力（去掉推进）的情况。 */
    static Case of(StructureForceReads.Forces forces, boolean propulsion) {
        Vec3 torque = torque(forces, propulsion);
        double tiltX = Math.toDegrees(torque.x / Math.max(1e-9, forces.inertia().x));
        double tiltZ = Math.toDegrees(torque.z / Math.max(1e-9, forces.inertia().z));
        boolean steady = Math.abs(tiltX) < STEADY && Math.abs(tiltZ) < STEADY;
        return new Case(propulsion ? "开着动力" : "关着动力", torque, round(tiltX), round(tiltZ), steady,
                tendency(tiltX, tiltZ, steady));
    }

    /** 升力比重量：沿重力反方向的合力（不算阻力与推进）除以重量；没有重力或质量为 0 为 0。 */
    static double liftRatio(StructureForceReads.Forces forces) {
        double weight = forces.mass() * forces.gravity().length();
        if (weight <= 1e-9) return 0;
        Vec3 up = forces.gravity().normalize().scale(-1);
        double lift = 0;
        for (StructureForceReads.Push push : forces.pushes()) {
            if (!push.group().equals(DRAG) && !push.group().equals(PROPULSION)) lift += push.force().dot(up);
        }
        return round(lift / weight);
    }

    /**
     * 配重建议：每个空格放一块试一下（重心跟着挪），按两种情况里最坏的那个好了多少排，给前几处；都没好过 5% 为空。
     *
     * @param massAt 在这一格放一块配重有多重
     */
    static List<Ballast> ballast(StructureForceReads.Forces forces, List<BlockPos> cells, ToDoubleFunction<BlockPos> massAt, int top) {
        double before = worst(forces, null, 0);
        List<Ballast> found = new ArrayList<>();
        for (BlockPos cell : cells) {
            double mass = massAt.applyAsDouble(cell);
            if (mass <= 0) continue;
            double after = worst(forces, Vec3.atCenterOf(cell), mass);
            if (after < before * (1 - WORTH)) found.add(new Ballast(cell, mass, round(before), round(after)));
        }
        found.sort(Comparator.comparingDouble(Ballast::after));
        return found.subList(0, Math.min(top, found.size()));
    }

    // 两种情况里最坏的：两根水平轴角加速度的绝对值之和（度每二次方秒）；给了配重就按放上去以后的重心算。
    private static double worst(StructureForceReads.Forces forces, Vec3 at, double mass) {
        double worst = 0;
        for (boolean propulsion : new boolean[]{true, false}) {
            Vec3 torque = torque(forces, propulsion);
            if (at != null) torque = withBallast(forces, propulsion, torque, at, mass);
            double sum = Math.abs(Math.toDegrees(torque.x / Math.max(1e-9, forces.inertia().x)))
                    + Math.abs(Math.toDegrees(torque.z / Math.max(1e-9, forces.inertia().z)));
            worst = Math.max(worst, sum);
        }
        return worst;
    }

    // 放一块配重后绕新重心的力矩：原来的力矩 + 重心挪动带来的（各力与原重力的力臂变了）+ 配重自己的重力。
    private static Vec3 withBallast(StructureForceReads.Forces forces, boolean propulsion, Vec3 torque, Vec3 at, double mass) {
        double total = forces.mass() + mass;
        Vec3 center = forces.center().scale(forces.mass() / total).add(at.scale(mass / total));
        Vec3 shift = forces.center().subtract(center);
        Vec3 pull = force(forces, propulsion).add(forces.gravity().scale(forces.mass()));
        return torque.add(shift.cross(pull)).add(at.subtract(center).cross(forces.gravity().scale(mass)));
    }

    private static Vec3 torque(StructureForceReads.Forces forces, boolean propulsion) {
        Vec3 sum = Vec3.ZERO;
        for (StructureForceReads.Push push : forces.pushes()) {
            if (included(push, propulsion)) sum = sum.add(push.torque());
        }
        return sum;
    }

    private static Vec3 force(StructureForceReads.Forces forces, boolean propulsion) {
        Vec3 sum = Vec3.ZERO;
        for (StructureForceReads.Push push : forces.pushes()) {
            if (included(push, propulsion)) sum = sum.add(push.force());
        }
        return sum;
    }

    private static boolean included(StructureForceReads.Push push, boolean propulsion) {
        return !push.group().equals(DRAG) && (propulsion || !push.group().equals(PROPULSION));
    }

    // 往哪头倒：绕 +x 转正的是顶部往 +z 那头倒，绕 +z 转正的是顶部往 -x 那头倒。
    private static String tendency(double tiltX, double tiltZ, boolean steady) {
        if (steady) return "稳着（两根水平轴都不到 " + STEADY + "°/s²）";
        List<String> parts = new ArrayList<>();
        if (Math.abs(tiltX) >= STEADY) parts.add("往结构 " + (tiltX > 0 ? "+z" : "-z") + " 那头倒（" + Math.abs(round(tiltX)) + "°/s²）");
        if (Math.abs(tiltZ) >= STEADY) parts.add("往结构 " + (tiltZ > 0 ? "-x" : "+x") + " 那头倒（" + Math.abs(round(tiltZ)) + "°/s²）");
        return String.join("，", parts);
    }

    static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
