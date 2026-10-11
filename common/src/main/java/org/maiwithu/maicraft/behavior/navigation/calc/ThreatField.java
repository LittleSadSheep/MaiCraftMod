// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.calc;

import java.util.List;

/**
 * 寻路用的威胁场：一组威胁各自"靠多近就多不划算"与"离多远才算脱身"。走位、撤离、绕怪群接近目标都用它，
 * 让寻路自己绕开怪群、侧移、后退着上台阶，而不是只盯着一只背对它直线退。
 *
 * <p>脱身看水平距离（上下楼层的高度差不算脱身），且要离每一只都够远，不能只躲开最近的一只；
 * 比较往哪里走时把所有威胁的远近惩罚相加。威胁位置是建目标那一刻的快照，怪走动了由调用方重开一次寻路跟上——
 * 搜索途中估价跟着变会让搜索失去依据。
 *
 * <p>惩罚只进搜索估价、不进边成本：估价往怪堆里走会反向增长，强度大到盖过路程量级，搜索会烧光节点预算返回无路，
 * 所以强度按"绕开一只贴在危险半径上的怪值一格半路"标定（{@link #DEFAULT_PENALTY}）。这是搜索倾向，
 * 不是验证过的安全程度，也不检查视线与墙。
 */
public final class ThreatField {

    /**
     * 一只威胁：位置与两种距离。
     *
     * @param radius    靠近它有多不划算的尺度：惩罚按"距离是它的几倍"算
     * @param clearance 离它这么远（水平）才算脱身；短暂退开接着打与彻底逃离可以给不同的数，
     *                  不必为了逃得远把附近每一格的惩罚都放大
     */
    public record Threat(double x, double y, double z, double radius, double clearance) {
        public Threat {
            if (radius < 0.0 || clearance < 0.0) {
                throw new IllegalArgumentException("距离不能为负：" + radius + " / " + clearance);
            }
        }

        /** 走位：退出危险半径就算脱身，两者同一个数。 */
        public Threat(double x, double y, double z, double radius) {
            this(x, y, z, radius, radius);
        }

        /** 惩罚尺度不变，改成要拉开到这么远才算脱身（逃跑、拉弓时与每一只都保持距离）。 */
        public Threat withClearance(double newClearance) {
            return new Threat(x, y, z, radius, newClearance);
        }
    }

    /**
     * 默认强度：把"贴着一只怪走"折成"多走几格路"的汇率。从危险半径处退开一格惩罚约降四成半，乘 15 约合 7 点成本，
     * 而走一格约 4.6——绕开一只贴在半径上的怪值一格半路。再大搜索会算不出两格的退路。
     */
    public static final double DEFAULT_PENALTY = 15.0;

    /** 候选格正好落在威胁身上时的惩罚，免得除以零。 */
    static final double ON_TOP_OF_THREAT = 1000.0;

    private final List<Threat> threats;
    private final double penaltyFactor;

    /** 至少一只威胁；强度越大越不愿贴近。 */
    public ThreatField(double penaltyFactor, List<Threat> threats) {
        if (threats.isEmpty()) {
            throw new IllegalArgumentException("威胁场至少要有一只威胁");
        }
        this.threats = List.copyOf(threats);
        this.penaltyFactor = penaltyFactor;
    }

    public List<Threat> threats() {
        return threats;
    }

    public double penaltyFactor() {
        return penaltyFactor;
    }

    /** 两个水平位置相隔有没有到这么远；高度差不算脱身。 */
    public static boolean clearOf(double x, double z, double threatX, double threatZ, double radius) {
        double dx = x - threatX;
        double dz = z - threatZ;
        return dx * dx + dz * dz >= radius * radius;
    }

    /** 这一格（按格心）是不是在每一只的脱身圈外。 */
    public boolean clearAt(int x, int y, int z) {
        for (Threat threat : threats) {
            if (!clearOf(x + 0.5, z + 0.5, threat.x(), threat.z(), threat.clearance())) {
                return false;
            }
        }
        return true;
    }

    /** 这一格的远近惩罚：三维距离越近越大，按各自尺度折算后相加。 */
    public double penaltyAt(int x, int y, int z) {
        double sum = 0.0;
        for (Threat threat : threats) {
            double dx = x + 0.5 - threat.x();
            double dy = y - threat.y();
            double dz = z + 0.5 - threat.z();
            double span = Math.max(1.0, threat.radius());
            double cost = (dx * dx + dy * dy + dz * dz) / (span * span);
            sum += cost <= 0.0 ? ON_TOP_OF_THREAT : 1.0 / cost;
        }
        return sum * penaltyFactor;
    }

    /** 这一格离"每一只都脱身"还差多远（水平，取最差的那只）；已经脱身为 0。 */
    public double remainingClearance(int x, int z) {
        double remaining = 0;
        for (Threat threat : threats) {
            remaining = Math.max(remaining,
                    threat.clearance() - Math.hypot(x + 0.5 - threat.x(), z + 0.5 - threat.z()));
        }
        return Math.max(0, remaining);
    }

    /** 比较两个目标是否相同：威胁不分先后，重复的照算。 */
    List<Threat> sortedThreats() {
        return threats.stream()
                .sorted((a, b) -> {
                    int byX = Double.compare(a.x(), b.x());
                    if (byX != 0) return byX;
                    int byZ = Double.compare(a.z(), b.z());
                    if (byZ != 0) return byZ;
                    int byY = Double.compare(a.y(), b.y());
                    if (byY != 0) return byY;
                    int byRadius = Double.compare(a.radius(), b.radius());
                    return byRadius != 0 ? byRadius : Double.compare(a.clearance(), b.clearance());
                })
                .toList();
    }
}
