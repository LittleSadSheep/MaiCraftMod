// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.maiwithu.maicraft.kernel.interrupt.SurvivalNeed;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 贴边这项生存需求：站在深落差的边缘、重心贴着边或动量指向边缘时，退回安全处。
 *
 * <p>贴边永远只是找空当处理：在岗任务把身体留在边缘可以是正当姿态（建筑贴墙、钓鱼、
 * 寻路贴崖），所以它不打断正常干活，只在两个动作之间插进来。
 */
public final class EdgeProximityNeed implements SurvivalNeed {

    /** 重心离边沿多远算贴着边（格）。 */
    public static final double EDGE_DISTANCE = 0.35;

    /** 多深算深落差（格）；一格两格的台阶不值得打断。 */
    public static final double DEEP_DROP = 3.0;

    /** 贴边的处境：离边沿的距离、边外的落差、动量是否指向边缘、退回安全处的落点。 */
    public record Facts(double edgeDistance, double dropDepth, boolean momentumTowardEdge, double[] safeSpot) {}

    /** 贴边判断，纯函数：贴着深落差的边才要退。 */
    public static boolean atRisk(Facts facts) {
        return facts.edgeDistance() <= EDGE_DISTANCE && facts.dropDepth() >= DEEP_DROP;
    }

    /** 读贴边处境的接缝：生产实现读脚下的方块与动量，测试给固定值。 */
    @FunctionalInterface
    public interface ReadsEdge {
        Facts read(TickContext context);
    }

    /** 退怎么落地：生产用走到，测试换替身。 */
    @FunctionalInterface
    public interface RetreatMoves {
        Task stepBack(double[] safeSpot);
    }

    private final ReadsEdge reader;
    private final RetreatMoves moves;

    public EdgeProximityNeed(ReadsEdge reader, RetreatMoves moves) {
        this.reader = reader;
        this.moves = moves;
    }

    @Override public String name() { return "贴边"; }

    @Override
    public Urgency urgency(TickContext context) {
        Facts facts = reader.read(context);
        return facts != null && atRisk(facts) ? Urgency.LATER : null;
    }

    @Override
    public Task createTask(TickContext context) {
        Facts facts = reader.read(context);
        return moves.stepBack(facts == null ? new double[] {0, 0, 0} : facts.safeSpot());
    }
}
