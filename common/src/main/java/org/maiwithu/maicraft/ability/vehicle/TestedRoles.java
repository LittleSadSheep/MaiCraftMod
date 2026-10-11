// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;

/**
 * 从试按的效果认角色（纯函数）：绕竖轴转得最明显是偏航（车是转向），升降最明显是升力或下降，
 * 平着挪得最明显是动力——车头还没定就把这个方向当车头，定了反着走是倒车；车头定了时绕横轴转是俯仰、
 * 绕车头的轴转是横滚。都太小就是看不出动。只认最明显的那一样，混着几样的照最明显的记，依据里写全数。
 */
final class TestedRoles {

    /** 转过这么多度才算转了。 */
    static final double TURN = 3.0;
    /** 挪过这么多格才算挪了。 */
    static final double MOVE = 0.3;

    /**
     * 认出来的。
     *
     * @param role    角色（{@code VehicleControls.ROLES} 之一）
     * @param forward 这一条看出来的车头（只有车头还没定、认成动力的那一条给），否则为空串
     * @param note    依据一句话
     */
    record Verdict(String role, String forward, String note) {}

    private TestedRoles() {}

    /**
     * @param kind    载具种类：车的偏航叫转向
     * @param forward 已经知道的车头（+x / -x / +z / -z），不知道为空串
     */
    static Verdict of(VehicleDriver.Effect effect, String kind, String forward) {
        double yaw = effect.turnY();
        double tilt = Math.max(Math.abs(effect.turnX()), Math.abs(effect.turnZ()));
        double flat = Math.hypot(effect.x(), effect.z());
        // 绕竖轴转（从上往下看逆时针为正，就是往左转）。
        if (Math.abs(yaw) >= TURN && Math.abs(yaw) >= tilt) {
            boolean left = yaw > 0;
            String role = kind.equals("car") ? (left ? "steer_left" : "steer_right") : (left ? "yaw_left" : "yaw_right");
            return new Verdict(role, "", "绕竖轴往" + (left ? "左" : "右") + "转了 " + Math.abs(yaw) + "°");
        }
        if (Math.abs(effect.y()) >= MOVE && Math.abs(effect.y()) >= flat) {
            boolean up = effect.y() > 0;
            return new Verdict(up ? "lift" : "descend", "", (up ? "升了 " : "降了 ") + Math.abs(effect.y()) + " 格");
        }
        if (flat >= MOVE) {
            String axis = Math.abs(effect.x()) >= Math.abs(effect.z()) ? (effect.x() > 0 ? "+x" : "-x")
                    : (effect.z() > 0 ? "+z" : "-z");
            String moved = "沿结构 " + axis + " 挪了 " + Math.round(flat * 10) / 10.0 + " 格";
            if (forward.isEmpty()) return new Verdict("power", axis, moved + "，这头当车头");
            if (axis.equals(forward)) return new Verdict("power", "", moved + "，朝车头");
            if (axis.equals(opposite(forward))) return new Verdict("reverse", "", moved + "，朝车尾");
            return new Verdict("none", "", moved + "，往侧面挪，认不出是什么角色");
        }
        if (tilt >= TURN) {
            if (forward.isEmpty()) return new Verdict("none", "", "斜了 " + tilt + "°，车头没定，认不出是俯仰还是横滚");
            double[] nose = axis(forward);
            // 车头的变化 = 转动 × 车头：竖直分量为正是抬头；转动沿车头的分量为正是向右横滚。
            double noseUp = effect.turnZ() * nose[0] - effect.turnX() * nose[2];
            double rollRight = effect.turnX() * nose[0] + effect.turnZ() * nose[2];
            if (Math.abs(noseUp) >= Math.abs(rollRight)) {
                return new Verdict(noseUp > 0 ? "pitch_up" : "pitch_down", "",
                        (noseUp > 0 ? "抬头 " : "低头 ") + Math.abs(Math.round(noseUp * 10) / 10.0) + "°");
            }
            return new Verdict(rollRight > 0 ? "roll_right" : "roll_left", "",
                    (rollRight > 0 ? "向右横滚 " : "向左横滚 ") + Math.abs(Math.round(rollRight * 10) / 10.0) + "°");
        }
        return new Verdict("none", "", "看不出动（挪不到 " + MOVE + " 格、转不到 " + TURN + "°）");
    }

    static String opposite(String axis) {
        return (axis.startsWith("+") ? "-" : "+") + axis.substring(1);
    }

    private static double[] axis(String axis) {
        double sign = axis.startsWith("-") ? -1 : 1;
        return axis.endsWith("x") ? new double[]{sign, 0, 0} : new double[]{0, 0, sign};
    }
}
