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
 * 刨出临时任务：角色卡在实心方块里窒息时，把埋住头或躯干的那一格挖开，脱身即结束。
 *
 * <p>挖哪一格每刻按处境重新判断（先头部、后躯干）；怎么瞄准与等确认交给原生挖掘。
 * 结束的当刻必须结清挖掘占着的交互，不留一份还在等确认的旧操作挡住后面的动作。
 */
public final class DigOutTask extends PhasedTask<DigOutTask.Phase> {

    /** 触发条件：头部或躯干所在的格子被会窒息的实心方块占据，多待一刻就多掉一次血。 */
    public enum Phase { DIG }

    /** 连续三刻连一格都没挖开（被完全挡住视线、够不着命中面）就算刨不出去。 */
    private static final long DIG_STALL_TICKS = 60;
    /** 刨出一格最多一分钟；超过多半是越挖越埋，按卡住收场。 */
    private static final long MAX_TICKS = 20L * 60;

    private final SurvivalSituation.SituationReader reader;
    private final BlockBreaking digging;
    private long ticksSinceProgress;

    public DigOutTask(SurvivalSituation.SituationReader reader, BlockBreaking digging) {
        super("刨出", Phase.DIG, new ProgressTracker(DIG_STALL_TICKS * 4, MAX_TICKS * 4));
        this.reader = reader;
        this.digging = digging;
    }

    @Override protected Action enter(Phase phase) { return null; }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        SurvivalSituation situation = reader.read(context);
        if (situation == null) {
            return Next.stay();
        }
        // 完成的证据来自真实世界：不再卡在方块里才算脱身。
        if (!situation.stuckInSolidBlock()) {
            digging.stop(context);
            return Next.done(TaskResult.done("刨出：已经从方块里脱身"));
        }
        if (situation.buriedCell() == null) {
            // 明明判了被埋却指不出要挖的格子，是读取一侧的缺陷，如实按内部错误收场。
            digging.stop(context);
            return Next.fail(Problem.of(Problem.Kind.INTERNAL_ERROR, "被埋了却指不出要刨开的格子"));
        }
        if (ticksSinceProgress >= DIG_STALL_TICKS) {
            digging.stop(context);
            return Next.fail(Problem.of(Problem.Kind.STUCK,
                    "埋住的那一格挖不开：连续 " + ticksSinceProgress / 20 + " 秒没有挖开任何方块"));
        }
        var status = digging.dig(context, situation.buriedCell());
        if (status instanceof ActionStatus.Running running && running.progressed()) {
            recordProgress("挖开了一格");
            recordChange(Change.of(Change.Kind.BLOCK_BROKEN, situation.buriedCell().toShortString(), 1));
            ticksSinceProgress = 0;
        } else {
            ticksSinceProgress++;
        }
        return Next.stay();
    }

    @Override
    protected String describePhase(Phase value) {
        return "挖开埋住身体的那一格";
    }

    @Override
    public Interruptibility interruptibility(TickContext context) {
        // 窒息时半路停手只会继续掉血；只有更急的（同时往虚空掉）才打断得了。
        return Interruptibility.UNSAFE_TO_STOP;
    }
}
