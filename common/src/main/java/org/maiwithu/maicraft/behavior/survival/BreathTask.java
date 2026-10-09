// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
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
 * 换气临时任务：头在水里憋得受不了时，抬头加按住跳跃一路上游；头出了水先踩着水停在水面，
 * 等氧气补满再结束。刚露头就结束会被手上的活立刻带回水下，氧气永远补不满，只能反复上来换气。
 *
 * <p>水面被实心方块压着（冰面、石头盖子）时，往上顶是顶不出去的：先把头顶那一格挖开再往上游。
 * 淹水是马上要命的事，那一格是别人的东西也挖（命比方块要紧），结果里写明；挖不动就如实失败。
 * 上浮多久都没有真实进展（卡在什么东西下面）才判卡住。
 */
public final class BreathTask extends PhasedTask<BreathTask.Phase> {

    /** 触发条件：氧气告急或已经开始掉血。先上游到头露出水面（头顶被盖住就先挖开），再踩水等氧气补满。 */
    public enum Phase { SWIM_UP, DIG_CEILING, CATCH_BREATH }

    /** 水面被盖住时怎么挖开头顶那一格：挖得动给一份对准那格的挖掘，挖不动给空。 */
    @FunctionalInterface
    public interface DigsCeiling {
        /** 没有挖开盖子的办法：只会往上游。 */
        DigsCeiling NONE = (context, cell) -> Optional.empty();

        Optional<BlockBreaking> dig(TickContext context, BlockPos ceiling);

        /** 这一格是不是别人的东西（受保护）：挖了要在结果里写明。 */
        default boolean belongsToSomeone(TickContext context, BlockPos ceiling) {
            return false;
        }
    }

    /** 连续十秒既没往上游、氧气也没涨（被东西挡住、卡在泡泡柱外……）就算换不上气。 */
    private static final long STALL_TICKS = 200;

    private final SurvivalSituation.SituationReader reader;
    private final DigsCeiling ceilings;
    private double lastFeetY = Double.NEGATIVE_INFINITY;
    private int lastAir = Integer.MIN_VALUE;
    /** 下一次进"挖开头顶"时用的挖掘。 */
    private BlockBreaking ceilingDigging;
    /** 要挖的那一格是不是别人的东西。 */
    private boolean ceilingOwned;

    public BreathTask(SurvivalSituation.SituationReader reader) {
        this(reader, DigsCeiling.NONE);
    }

    public BreathTask(SurvivalSituation.SituationReader reader, DigsCeiling ceilings) {
        super("换气", Phase.SWIM_UP, new ProgressTracker(STALL_TICKS, 20L * 60 * 2));
        this.reader = reader;
        this.ceilings = ceilings;
    }

    @Override protected Action enter(Phase phase) {
        return phase == Phase.DIG_CEILING ? ceilingDigging : null;
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        SurvivalSituation situation = reader.read(context);
        if (situation == null) {
            // 没有当刻的处境（角色上下文短暂缺失）就这一刻先不动，下一刻再判断。
            return Next.stay();
        }
        // 完成的证据来自真实世界：头在水面上、氧气补满了才算换好气。
        if (!situation.headInWater() && situation.airTicks() >= situation.maxAirTicks()) {
            return Next.done(TaskResult.done("换气：浮出水面，氧气补满了"));
        }
        if (phase == Phase.DIG_CEILING) {
            return digCeiling(context, situation);
        }
        // 头顶被盖住：往上顶出不去，先挖开那一格；挖不得就如实失败。
        if (situation.headInWater() && situation.waterCeiling() != null) {
            Optional<BlockBreaking> digging = ceilings.dig(context, situation.waterCeiling());
            if (digging.isEmpty()) {
                return Next.fail(Problem.of(Problem.Kind.STUCK,
                        "水面被盖住，头顶那一格挖不动，浮不上去换气"));
            }
            ceilingDigging = digging.get();
            ceilingOwned = ceilings.belongsToSomeone(context, situation.waterCeiling());
            return Next.go(Phase.DIG_CEILING, "水面被盖住，先挖开头顶那一格");
        }
        PlayerContext player = context.player();
        PlayerInput input = player.input();
        long tick = player.clientTick();
        // 按住跳跃：水下就往上游，到了水面就踩着水不沉下去。指令每刻续发，被打断的那几刻没续，输入自动松开。
        input.applyMovement(new PlayerInput.Movement(0.0f, 0.0f, true, false, false), tick);
        if (situation.headInWater()) {
            // 抬头上游：保持原水平朝向，只把镜头压到向上。
            input.requestLook(situation.facingYaw(), -90.0f, tick);
        }
        // 往上游了或者氧气涨了都是真实进展；卡住判定由进度跟踪按这个信号统一收口。
        if (situation.feetY() > lastFeetY + 1.0e-3) {
            recordProgress("上浮了一点");
        } else if (situation.airTicks() > lastAir) {
            recordProgress("氧气在恢复");
        }
        lastFeetY = situation.feetY();
        lastAir = situation.airTicks();
        Phase now = situation.headInWater() ? Phase.SWIM_UP : Phase.CATCH_BREATH;
        return now == phase ? Next.stay() : Next.go(now, now == Phase.SWIM_UP ? "又沉到水下" : "头露出了水面");
    }

    // 挖开头顶那一格：挖开了接着往上游，挖不开如实失败。
    private Next<Phase> digCeiling(TickContext context, SurvivalSituation situation) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                recordChange(new Change(Change.Kind.BLOCK_BROKEN, "压在水面上的方块", 1,
                        ceilingOwned ? "那一格是别人的东西，为了不淹死挖开的" : null));
                yield Next.go(Phase.SWIM_UP, "头顶挖开了，接着往上游");
            }
            case ActionStatus.Failed failed -> Next.fail(Problem.of(Problem.Kind.STUCK,
                    "水面被盖住，头顶那一格没挖开：" + failed.problem().message()));
        };
    }

    @Override
    protected String describePhase(Phase value) {
        return switch (value) {
            case SWIM_UP -> "向上游";
            case DIG_CEILING -> "挖开压在水面上的方块";
            case CATCH_BREATH -> "在水面踩水换气";
        };
    }

    @Override
    public Interruptibility interruptibility(TickContext context) {
        // 憋气上浮的半路停下来只会更糟；只有必须立刻处理的需求（比如同时被埋）才打断得了。
        return Interruptibility.UNSAFE_TO_STOP;
    }
}
