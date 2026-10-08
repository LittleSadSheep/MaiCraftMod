// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 换气临时任务：头在水里憋得受不了时，抬头加按住跳跃一路上游，头一出水就结束。
 *
 * <p>只做上浮这一件事：换不到空气不是因为选错了路线，深度再大也是同一个动作；
 * 上浮多久都没有真实进展才判卡住，交给上层带着事实收场。
 */
public final class BreathTask extends PhasedTask<BreathTask.Phase> {

    /** 触发条件：氧气告急或已经开始掉血。角色在水下憋着，停留不会变好，只有上游一条路。 */
    public enum Phase { SWIM_UP }

    /** 连续十秒高度没有涨过（被东西挡住、卡在泡泡柱外……）就算上浮不上去。 */
    private static final long RISE_STALL_TICKS = 200;

    private final SurvivalSituation.SituationReader reader;
    private double lastFeetY;

    public BreathTask(SurvivalSituation.SituationReader reader) {
        super("换气", Phase.SWIM_UP, new ProgressTracker(RISE_STALL_TICKS, 20L * 60 * 2));
        this.reader = reader;
    }

    @Override protected Action enter(Phase phase) { return null; }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        SurvivalSituation situation = reader.read(context);
        if (situation == null) {
            // 没有当刻的处境（角色上下文短暂缺失）就这一刻先不动，下一刻再判断。
            return Next.stay();
        }
        // 完成的证据来自真实世界：头不在水里了才算换上气。
        if (!situation.headInWater()) {
            return Next.done(TaskResult.done("换气：头已经露出水面"));
        }
        PlayerContext player = context.player();
        PlayerInput input = player.input();
        long tick = player.clientTick();
        // 抬头（保持原水平朝向，把镜头压到向上）并按住跳跃：水里按住跳跃就会上浮。
        // 指令必须每刻续发，被打断的那几刻没续，输入就自动松开，不会带着旧指令乱游。
        input.applyMovement(new PlayerInput.Movement(0.0f, 0.0f, true, false, false), tick);
        input.requestLook(situation.facingYaw(), -90.0f, tick);
        // 高度涨了才算真实进展；卡住判定由进度跟踪按这个信号统一收口。
        if (situation.feetY() > lastFeetY + 1.0e-3) {
            recordProgress("上浮了一点");
        }
        lastFeetY = situation.feetY();
        return Next.stay();
    }

    @Override
    protected String describePhase(Phase value) {
        return "向上游";
    }

    @Override
    public Interruptibility interruptibility(TickContext context) {
        // 憋气上浮的半路停下来只会更糟；只有必须立刻处理的需求（比如同时被埋）才打断得了。
        return Interruptibility.UNSAFE_TO_STOP;
    }
}
