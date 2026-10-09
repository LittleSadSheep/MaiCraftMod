// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 刨出临时任务：角色的头卡在实心方块里窒息时，把埋住头的那一格挖开，脱身即结束。
 *
 * <p>挖哪一格每刻按处境重新判断（先眼睛所在格、后头顶格）；怎么瞄准、选工具与等确认交给原生挖掘。
 * 结束的当刻必须结清挖掘占着的交互，不留一份还在等确认的旧操作挡住后面的动作。
 */
public final class DigOutTask extends PhasedTask<DigOutTask.Phase> {

    /** 触发条件：头所在的格子被会窒息的实心方块占据，多待一刻就多掉一次血。 */
    public enum Phase { DIG }

    /**
     * 连续多久一格都没挖开才算刨不出去：三十秒。空手挖石头要七秒多，留足余量；
     * 挖不动的（基岩、黑曜石空手）照样会在这之后如实收场。类别：游戏事实（挖掘耗时）加玩家常识。
     */
    private static final long DIG_STALL_TICKS = 20L * 30;
    /** 整次刨出最多一分钟；超过多半是越挖越埋，按卡住收场。 */
    private static final long MAX_TICKS = 20L * 60;

    private final SurvivalSituation.SituationReader reader;
    private final BlockBreaking digging;

    public DigOutTask(SurvivalSituation.SituationReader reader, BlockBreaking digging) {
        super("刨出", Phase.DIG, new ProgressTracker(DIG_STALL_TICKS, MAX_TICKS));
        this.reader = reader;
        this.digging = digging;
    }

    // 挖掘是这个阶段的动作：任务无论怎样结束都会收尾它，挖到一半的挖掘不会悬着。
    @Override protected Action enter(Phase phase) { return digging; }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        SurvivalSituation situation = reader.read(context);
        if (situation == null) {
            return Next.stay();
        }
        // 完成的证据来自真实世界：头不再卡在方块里才算脱身。
        if (!situation.stuckInSolidBlock()) {
            return Next.done(TaskResult.done("刨出：已经从方块里脱身"));
        }
        if (situation.buriedCell() == null) {
            // 明明判了被埋却指不出要挖的格子，是读取一侧的缺陷，如实按内部错误收场。
            return Next.fail(Problem.of(Problem.Kind.INTERNAL_ERROR, "被埋了却指不出要刨开的格子"));
        }
        digging.aimAt(situation.buriedCell());
        // 挖开一格才算真实进展，由动作报告；多久没进展算卡住由进度跟踪统一判断。
        var status = runAction(context);
        if (status instanceof ActionStatus.Running running && running.progressed()) {
            recordChange(new Change(Change.Kind.BLOCK_BROKEN, "埋住头的方块", 1,
                    "位置 " + situation.buriedCell().toShortString()));
        }
        if (status instanceof ActionStatus.Failed failed) {
            return Next.fail(failed.problem());
        }
        return Next.stay();
    }

    @Override
    protected String describePhase(Phase value) {
        return "挖开埋住头的那一格";
    }

    @Override
    public Interruptibility interruptibility(TickContext context) {
        // 窒息时半路停手只会继续掉血；只有更急的（同时往虚空掉）才打断得了。
        return Interruptibility.UNSAFE_TO_STOP;
    }
}
