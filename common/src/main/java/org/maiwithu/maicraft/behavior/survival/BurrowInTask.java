// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 极端自保的临时任务：露天又打不过的夜里挖三填一——往脚下挖三格、站进坑底、把坑口封上，
 * 熬到天亮再挖开坑口、垫着方块爬回原来的地面。
 *
 * <p>挖之前先看脚下这一列（{@link BurrowPlan}）：挖不成一个封得住的坑（下面是基岩、液体、洞穴，
 * 坑壁有缺口或液体，或者是别人的东西）就不挖，站定硬熬，事件里说明原因。身上没有能封口的方块时
 * 挖好坑站在坑底等。哪一步没做成都不判主任务失败：如实记下发生了什么，回去继续干活。
 */
final class BurrowInTask extends PhasedTask<BurrowInTask.Phase> {

    /** 自保的阶段：看脚下 → 往下挖 → 封坑口 → 等天亮 → 挖开坑口 → 爬回地面。 */
    enum Phase { CHECK, DIG, SEAL, WAIT, OPEN, CLIMB }

    /** 一分钟既没挖开、没落地、没封上也没在等天亮，才算卡住。 */
    private static final long STUCK_AFTER_TICKS = 20L * 60;

    /** 看脚下要看满这么多刻：方块归属问服务端要等几刻才有回音，回音里说是别人的就不挖。 */
    static final int CHECK_TICKS = 10;

    /** 挖坑要用到的现场与动作；生产实现读世界、走原生交互，测试换替身。 */
    interface Moves {
        /** 此刻还是不是该熬的夜（能睡的时段）。 */
        boolean stillNight(TickContext context);

        /** 角色脚下所在的格子；没有角色时为 null。 */
        BlockPos feet(TickContext context);

        /** 角色是不是落地站稳了。 */
        boolean onGround(TickContext context);

        /** 从脚下这格往下看一列与坑壁。 */
        BurrowPlan.Site site(TickContext context, BlockPos feet);

        /** 挖一格的动作，每挖一格新建一份。 */
        BlockBreaking digging();

        /** 换一块能封口的方块到手上并对着坑壁点一下，把这一格封上；身上没有能封口的方块时为空。 */
        Optional<Action> sealing(BlockPos cell);

        /** 从坑底垫着方块爬回原来地面这一格的走到。 */
        Action climbingTo(BlockPos surface);
    }

    private final Moves moves;
    private final TaskEventSink events;

    /** 挖坑前站的地面格：脚下第一格就是坑口，天亮后爬回这里。 */
    private BlockPos surface;
    /** 已经看了几刻脚下。 */
    private int checked;
    /** 已经挖开几格。 */
    private int dug;
    /** 刚挖开一格，等身体落到坑里站稳再挖下一格。 */
    private boolean landing;
    private boolean sealed;
    /** 挖不成坑或封不上口的原因；挖成封好时为 null。 */
    private String shortfall;

    BurrowInTask(Moves moves, TaskEventSink events) {
        super("极端自保", Phase.CHECK, new ProgressTracker(STUCK_AFTER_TICKS, Long.MAX_VALUE));
        this.moves = Objects.requireNonNull(moves, "moves");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    protected Action enter(Phase phase) {
        return switch (phase) {
            case DIG -> aimed(surface.below(dug + 1));
            case SEAL -> moves.sealing(surface.below()).orElse(null);
            case OPEN -> aimed(surface.below());
            case CLIMB -> moves.climbingTo(surface);
            case CHECK, WAIT -> null;
        };
    }

    private BlockBreaking aimed(BlockPos cell) {
        BlockBreaking digging = moves.digging();
        digging.aimAt(cell);
        return digging;
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case CHECK -> check(context);
            case DIG -> dig(context);
            case SEAL -> seal(context);
            case WAIT -> waitForDay(context);
            case OPEN -> open(context);
            case CLIMB -> climb(context);
        };
    }

    // 看脚下：挖得成一个封得住的坑才挖，不然站定硬熬并说明原因。
    private Next<Phase> check(TickContext context) {
        surface = moves.feet(context);
        if (surface == null) {
            return Next.done(TaskResult.done("这一刻掌握不到角色，没有自保"));
        }
        Optional<String> refusal = BurrowPlan.refusal(moves.site(context, surface));
        if (refusal.isPresent()) {
            shortfall = "挖不成坑：" + refusal.get();
            events.publish(TaskEvent.Kind.NEED_UNHANDLED, shortfall + "；站定硬熬到天亮");
            return Next.go(Phase.WAIT, shortfall);
        }
        if (++checked < CHECK_TICKS) {
            recordProgress("看脚下能不能挖");
            return Next.stay();
        }
        events.publish(TaskEvent.Kind.TEMPORARY_TASK_STARTED, "露天又打不过，挖三填一躲到天亮");
        return Next.go(Phase.DIG, "往脚下挖坑");
    }

    // 往下挖：挖开一格先等身体落进坑里站稳，再挖下一格；三格挖完去封坑口。
    private Next<Phase> dig(TickContext context) {
        if (landing) {
            BlockPos feet = moves.feet(context);
            if (feet == null || feet.getY() > surface.getY() - dug || !moves.onGround(context)) {
                return Next.stay();
            }
            landing = false;
            recordProgress("落到坑里第 " + dug + " 格");
            return dug < BurrowPlan.DEPTH ? Next.go(Phase.DIG, "接着往下挖") : Next.go(Phase.SEAL, "坑挖好了，封坑口");
        }
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                dug++;
                recordChange(Change.of(Change.Kind.BLOCK_BROKEN, "挖三填一的坑", 1));
                landing = true;
                yield Next.stay();
            }
            case ActionStatus.Failed failed -> {
                shortfall = "挖到第 " + (dug + 1) + " 格挖不动：" + failed.problem().message();
                events.publish(TaskEvent.Kind.NEED_UNHANDLED, shortfall + "；站在挖开的地方硬熬");
                yield Next.go(Phase.WAIT, shortfall);
            }
        };
    }

    // 封坑口：换上一块方块对着坑壁点一下；没有能封口的方块或没封上，就站在坑底等。
    private Next<Phase> seal(TickContext context) {
        if (action() == null) {
            shortfall = "身上没有能封坑口的方块";
            events.publish(TaskEvent.Kind.NEED_UNHANDLED, shortfall + "；站在坑底硬熬");
            return Next.go(Phase.WAIT, shortfall);
        }
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                sealed = true;
                recordChange(Change.of(Change.Kind.BLOCK_PLACED, "封住坑口", 1));
                yield Next.go(Phase.WAIT, "坑口封上了，等天亮");
            }
            case ActionStatus.Failed failed -> {
                shortfall = "坑口没封上：" + failed.problem().message();
                events.publish(TaskEvent.Kind.NEED_UNHANDLED, shortfall + "；站在坑底硬熬");
                yield Next.go(Phase.WAIT, shortfall);
            }
        };
    }

    // 等天亮：站着不动；天亮了封着口就先挖开，挖过坑就爬回地面，没挖就直接收场。
    private Next<Phase> waitForDay(TickContext context) {
        recordProgress("自保中");
        if (moves.stillNight(context)) {
            return Next.stay();
        }
        if (sealed) return Next.go(Phase.OPEN, "天亮了，挖开坑口");
        if (dug > 0) return Next.go(Phase.CLIMB, "天亮了，爬回地面");
        return finish("站到天亮，回去继续干活");
    }

    // 挖开坑口：挖不开就留在坑里，如实交代。
    private Next<Phase> open(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                recordChange(Change.of(Change.Kind.BLOCK_BROKEN, "挖开坑口", 1));
                yield Next.go(Phase.CLIMB, "坑口挖开了，爬回地面");
            }
            case ActionStatus.Failed failed -> {
                events.publish(TaskEvent.Kind.NEED_UNHANDLED, "天亮了但坑口挖不开：" + failed.problem().message());
                yield finish("天亮了但坑口挖不开，人还在坑里");
            }
        };
    }

    // 爬回地面：垫着方块走回挖坑前站的那一格；走不回去就停在坑里，如实交代。
    private Next<Phase> climb(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> finish("熬过了夜，回到了地面");
            case ActionStatus.Failed failed -> {
                events.publish(TaskEvent.Kind.NEED_UNHANDLED, "天亮了但爬不出坑：" + failed.problem().message());
                yield finish("天亮了但爬不出坑，人还在坑里");
            }
        };
    }

    private Next<Phase> finish(String summary) {
        events.publish(TaskEvent.Kind.TEMPORARY_TASK_FINISHED, summary);
        return Next.done(TaskResult.done(shortfall == null ? summary : summary + "（" + shortfall + "）"));
    }

    @Override
    protected String describePhase(Phase value) {
        return switch (value) {
            case CHECK -> "看脚下能不能挖坑";
            case DIG -> "往下挖坑";
            case SEAL -> "封坑口";
            case WAIT -> "等天亮";
            case OPEN -> "挖开坑口";
            case CLIMB -> "爬回地面";
        };
    }
}
