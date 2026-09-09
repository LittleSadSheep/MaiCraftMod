package org.maiwithu.maicraft.core.pathing.baritone.landing;

/**
 * 按一次规划时固定的重力、下落速度、眼高和交互距离，判断下落途中是否留得出放水的操作时机。
 */
public record WaterLandingWindow(double gravity, double reach, double eyeHeight, double initialDownwardSpeed) {
    public boolean permits(double drop) {
        if (!Double.isFinite(drop) || drop <= 0 || !Double.isFinite(gravity) || gravity <= 0
                || !Double.isFinite(reach) || reach <= 0 || !Double.isFinite(eyeHeight) || eyeHeight <= 0
                || !Double.isFinite(initialDownwardSpeed) || initialDownwardSpeed < 0) return false;
        // 先从总交互距离中扣除最远水平偏移，再减眼高，得到脚下还有多高时能点到落点。
        double window = Math.sqrt(Math.max(0, reach * reach - 0.5)) - eyeHeight;
        if (window <= 0) return false;
        double remaining = drop, speed = initialDownwardSpeed;
        for (int tick = 0; tick < 4096; tick++) {
            // 按原版顺序先移动，再计算重力和阻力。要求每一步下落距离都小于可点击窗口，
            // 表示起跳时机有些偏差时也应留得出操作机会；这比只找到一个碰巧合适的高度更保守。
            if (speed >= window) return false;
            remaining -= speed;
            if (remaining <= 0) return true;
            speed = (speed + gravity) * (double) 0.98F;
        }
        return false;
    }
}
