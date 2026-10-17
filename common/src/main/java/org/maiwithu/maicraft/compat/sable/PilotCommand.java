// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

/**
 * 飞控与开车这一刻要的输出，连续量；由 {@link KeyMixer} 调成无线红石打字机的开关键。
 *
 * @param power 动力：0～1 往前，负的是倒车（没有倒车键就不管）
 * @param lift  升力：0～1（飞艇的燃烧器开多少）
 * @param turn  转向：-1～1，正的往右（飞艇、船是偏航，车是打方向）
 * @param brake 刹车（有刹车键才按）
 */
record PilotCommand(double power, double lift, double turn, boolean brake) {

    /** 什么都不按。 */
    static final PilotCommand IDLE = new PilotCommand(0, 0, 0, false);

    PilotCommand {
        power = clamp(power, -1, 1);
        lift = clamp(lift, 0, 1);
        turn = clamp(turn, -1, 1);
    }

    static double clamp(double value, double low, double high) {
        return Double.isFinite(value) ? Math.max(low, Math.min(high, value)) : 0;
    }
}
