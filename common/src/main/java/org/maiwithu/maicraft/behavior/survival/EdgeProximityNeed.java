// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.maiwithu.maicraft.kernel.interrupt.SurvivalNeed;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 贴边这项生存需求：站在深落差的边缘、重心贴着边时，退回安全处。
 *
 * <p>只在身体被放开时处理：手上有任务时，贴着边是那件事要的姿态（搭桥、贴崖挖矿、钓鱼），
 * 退一步、任务走回去、再退一步只会来回抖。所以只有角色闲着、没有任务管身体时才退；
 * 附近找不到站得住的地方就不退。
 */
public final class EdgeProximityNeed implements SurvivalNeed {

    /** 重心离边沿多远算贴着边（格）。 */
    public static final double EDGE_DISTANCE = 0.35;

    /** 多深算深落差（格）；一格两格的台阶不值得打断。 */
    public static final double DEEP_DROP = 3.0;

    /** 贴边的处境：离边沿的距离、边外的落差、退回安全处的落点（附近没有站得住的地方时为 null）。 */
    public record Facts(double edgeDistance, double dropDepth, double[] safeSpot) {}

    /** 贴边判断，纯函数：贴着深落差的边、而且附近有站得住的地方可退，才要退。 */
    public static boolean atRisk(Facts facts) {
        return facts.edgeDistance() <= EDGE_DISTANCE && facts.dropDepth() >= DEEP_DROP && facts.safeSpot() != null;
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
    public Urgency urgency(TickContext context, Task currentTask) {
        // 手上有任务：贴着边是它要的姿态，不往回退；只有闲着时才退。
        return currentTask != null ? null : urgency(context);
    }

    @Override
    public Task createTask(TickContext context) {
        Facts facts = reader.read(context);
        if (facts == null || facts.safeSpot() == null) {
            throw new IllegalStateException("贴边需求在没有可退的落点时被要求建任务");
        }
        return moves.stepBack(facts.safeSpot());
    }
}
