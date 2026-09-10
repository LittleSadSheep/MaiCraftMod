// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

/**
 * 按读取到的推力、重力和阻力估计竖直速度与惯性上升高度，帮助决定是否继续上升；这里计算数值，不直接改变玩家速度。
 */
public final class JetpackDynamics {
    private static final double AIR_DRAG = 0.98;
    private JetpackDynamics() {}

    /**
     * 估计这一刻实际向上或向下移动多少：上升受推力和最高速度限制，松开上升则至少保持悬停下降速度。
     */
    public static double nextVertical(double rawVy, boolean up, JetpackNativeAdapter.Snapshot power) {
        requireModel(rawVy, power);
        return up ? Math.min(rawVy + power.acceleration(), power.vertical()) : Math.max(rawVy, power.hoverDescent());
    }

    /**
     * 移动后再扣重力并乘阻力，得到下一刻开始时保留的速度；它与本刻实际位移不是同一个值。
     */
    public static double rawAfterStep(double displacement, JetpackNativeAdapter.Snapshot power) {
        requireModel(displacement, power);
        return (displacement - power.gravity()) * AIR_DRAG;
    }

    /**
     * 松开推力后仍可能继续上升；最多逐刻算五百一十二次，剩下的上升用偏保守的阻力上界补足。
     */
    public static double coastRise(double rawVy, JetpackNativeAdapter.Snapshot power) {
        requireModel(rawVy, power);
        double rise = 0;
        for (int tick = 0; tick < 512 && rawVy > 0; tick++) {
            rise += rawVy;
            rawVy = rawAfterStep(rawVy, power);
        }
        // Extremely small configured gravity may leave a tail: ignoring gravity bounds it above.
        return rise + Math.max(0, rawVy) / (1 - AIR_DRAG);
    }

    /** Peak rise of this input pulse and subsequent released-UP coasting, relative to current feet. */
    public static double riseEnvelope(double rawVy, boolean up, JetpackNativeAdapter.Snapshot power) {
        double step = nextVertical(rawVy, up, power);
        return Math.max(0, step) + coastRise(rawAfterStep(step, power), power);
    }

    /**
     * 已有向上惯性够到目标高度就先不加推力；避免每次看到脚下略低就继续上冲。
     */
    public static boolean shouldRise(double height, double rawVy, double minimumHeight, JetpackNativeAdapter.Snapshot power) {
        if (!Double.isFinite(height) || !Double.isFinite(minimumHeight)) throw new IllegalArgumentException("finite flight heights required");
        double releasedStep = nextVertical(rawVy, false, power);
        // During an externally induced fast fall, hover clamps descent immediately; UP may not.
        if (nextVertical(rawVy, true, power) <= releasedStep) return false;
        double releasedReach = releasedStep > 0 ? coastRise(rawVy, power) : releasedStep;
        return height + releasedReach < minimumHeight;
    }

    private static void requireModel(double value, JetpackNativeAdapter.Snapshot power) {
        if (!Double.isFinite(value) || power == null || !Double.isFinite(power.gravity()) || power.gravity() <= 0
                || !Double.isFinite(power.acceleration()) || power.acceleration() <= 0
                || !Double.isFinite(power.vertical()) || power.vertical() <= 0
                || !Double.isFinite(power.hoverDescent()) || power.hoverDescent() > 0) {
            throw new IllegalArgumentException("finite native upright flight parameters required");
        }
    }
}
