// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

/**
 * 有计划地往下跳之前判断：这一路掉下去，留不留得出对着地面倒水的那一刻。
 * 按出发时取的重力、下落初速、眼高与够得着的距离，原版先移动再算重力和阻力的顺序逐刻推。
 *
 * @param gravity              每刻重力
 * @param reach                够得着方块的距离
 * @param eyeHeight            眼睛离脚多高
 * @param initialDownwardSpeed 出发时已有的下落速度
 */
public record WaterLandingWindow(double gravity, double reach, double eyeHeight, double initialDownwardSpeed) {

    /** 从脚下离地面还有 drop 格时开始掉，能不能留出倒水的那一刻。 */
    public boolean permits(double drop) {
        if (!Double.isFinite(drop) || drop <= 0 || !Double.isFinite(gravity) || gravity <= 0
                || !Double.isFinite(reach) || reach <= 0 || !Double.isFinite(eyeHeight) || eyeHeight <= 0
                || !Double.isFinite(initialDownwardSpeed) || initialDownwardSpeed < 0) return false;
        // 够得着的距离先扣掉最远的水平偏移、再扣眼高：脚下离地面还剩这么高时才点得到地面。
        double window = Math.sqrt(Math.max(0, reach * reach - 0.5)) - eyeHeight;
        if (window <= 0) return false;
        double remaining = drop;
        double speed = initialDownwardSpeed;
        for (int tick = 0; tick < 4096; tick++) {
            // 每一刻掉的距离都要比够得着的那一段短：起跳早一刻晚一刻都还留得出倒水的机会，不靠碰巧的高度。
            if (speed >= window) return false;
            remaining -= speed;
            if (remaining <= 0) return true;
            speed = (speed + gravity) * (double) 0.98F;
        }
        return false;
    }
}
