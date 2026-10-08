// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.function.Supplier;

import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 落地防护临时任务：这一掉会摔死时，把手里拿着的水桶对脚下方块放出去，落进水里就没事了。
 *
 * <p>这是最小版：只用手上现有的水桶，不去翻背包换工具——换物品与快速移动中的瞄准
 * 属于建造域与出行域的事，那些就位之前做不了的就如实说做不了，不发明别的自救。
 */
public final class FallGuardTask extends PhasedTask<FallGuardTask.Phase> {

    /** 触发条件：预计落地伤害够得到当前生命，或正下方是虚空；多犹豫一刻高度就少一段。 */
    public enum Phase { CUSHION }

    private final Supplier<Action> cushion;

    /** @param cushion 造一个"对脚下方块放水缓冲"的动作；手里没有水桶等做不了时返回 null。 */
    public FallGuardTask(Supplier<Action> cushion) {
        super("落地防护", Phase.CUSHION, new ProgressTracker(100, 20L * 30));
        this.cushion = cushion;
    }

    @Override protected Action enter(Phase phase) { return cushion.get(); }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        if (action() == null) {
            // 手上没有水桶，最小版没有任何能拖住这一掉的手段：如实交代，落地伤害由游戏结算。
            return Next.fail(Problem.of(Problem.Kind.DANGER,
                    "这一掉会摔死，但手里没有水桶，放不了水缓冲；空中变向与垫方块还没接上，做不了更多"));
        }
        return runActionThen(context, () -> Next.done(TaskResult.done("落地防护：已经放出了缓冲的水，接下来交给落地")));
    }

    @Override
    protected String describePhase(Phase value) {
        return "对脚下方块放水缓冲";
    }

    @Override
    public Interruptibility interruptibility(TickContext context) {
        // 高速下落的半路停下来等于放弃自救；只有同时被埋这种更急的事才打断得了。
        return Interruptibility.UNSAFE_TO_STOP;
    }
}
