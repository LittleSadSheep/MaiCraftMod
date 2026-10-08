// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.child;

import java.util.List;

import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TaskInput;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 子任务运行器：父任务（目标推进、组合任务、控制循环）要用另一个任务做事时，统一在这里创建、启动、
 * 逐刻推进、检查父任务给的时限、把异常转成 INTERNAL_ERROR 问题、转发暂停、收结果、收尾子任务。
 *
 * <p>一次运行只用一个实例：{@code begin} 一次，之后每刻 {@code tick}，直到返回
 * {@link TickResult.Finished}，或父任务提前结束时调用 {@code close}。启动就出错时子任务当场结算，
 * 调用方在 {@code begin} 之后看 {@link #finished()} 就能拿到结果。子任务结算后本实例不可再用，
 * 重复结算直接抛异常——那说明调用方写错了，不是游戏里发生了什么。
 *
 * <p>子任务卡住不在这里判定：分阶段任务自己带进度跟踪，多久没进展算卡住由它按自己的节奏判断，
 * 判出的失败结果原样向上传播。这里只检查父任务交给子任务的时限，超时按卡住收尾。
 * 父任务被暂停的时间不计时：暂停期间 {@code tick} 本来就不会被调用。
 *
 * <p>无论怎样结束（超时、异常、被提前收尾），子任务收尾时交代的变化、没能确认的交互与试过的办法
 * 都保留在最终结果里：挖了 40 个铁再被截停，结果里仍有这 40 个铁。
 */
public final class ChildTaskRunner {
    private static final Logger LOG = LoggerFactory.getLogger(ChildTaskRunner.class);

    /** 不限时：子任务多久结束完全由它自己的进度跟踪决定。目标推进与控制循环用它。 */
    public static final long NO_LIMIT = Long.MAX_VALUE;

    /** 父任务交给子任务的时限，按子任务实际被推进的刻数算。 */
    private final long maxTicks;
    private long activeTicks;
    /** 正在运行的子任务；结算后保留到本实例被丢弃，供描述。 */
    private Task child;
    /** 结算后的最终结果；没结算时为 null。 */
    private TaskResult result;

    /**
     * @param maxTicks 父任务交给子任务的时限（刻）；组合任务给"拿床这件事有预算"这类限制时用，
     *                 不需要时传 {@link #NO_LIMIT}
     */
    public ChildTaskRunner(long maxTicks) {
        if (maxTicks <= 0) {
            throw new IllegalArgumentException("子任务的时限必须为正");
        }
        this.maxTicks = maxTicks;
    }

    /** 从任务工厂表创建子任务并启动；任务输入类型没有登记工厂时直接抛异常，那是启动时该发现的错误。 */
    public void begin(TaskInput input, TaskFactories factories, TickContext context) {
        begin(factories.create(input), context);
    }

    /** 运行一个已经建好的子任务；组合任务自己拼装子任务（服务经闭包传入）时用这个入口。 */
    public void begin(Task task, TickContext context) {
        if (result != null) {
            throw new IllegalStateException("子任务已结算，不能再次开始");
        }
        if (child != null) {
            throw new IllegalStateException("子任务已经在运行，不能同时运行另一个");
        }
        child = task;
        try {
            child.start(context);
        } catch (RuntimeException exception) {
            // 启动就出错：按程序错误当场结算，调用方在 begin 之后看 finished() 拿结果，决定下一步。
            LOG.warn("子任务启动失败：{}", child.describe(), exception);
            settleWithProblem("子任务启动失败", internalError(exception));
        }
    }

    /** 推进子任务一刻；返回还在做或已有结果。结算之后再调用是调用方写错了，直接抛异常。 */
    public TickResult tick(TickContext context) {
        requireUnsettled("推进");
        TickResult tickResult;
        try {
            tickResult = child.tick(context);
        } catch (RuntimeException exception) {
            // 子任务抛异常不连累父任务一起崩：转成 INTERNAL_ERROR 问题，已经发生的事照样交代。
            LOG.warn("子任务推进时出错：{}", child.describe(), exception);
            settleWithProblem("子任务推进时出错", internalError(exception));
            return TickResult.finished(result);
        }
        if (tickResult instanceof TickResult.Finished finished) {
            // 子任务自己走到了结局：补上收尾调用，结论以它在结局里给出的结果为准。
            closeChild(CloseReason.FINISHED);
            result = finished.result();
            LOG.debug("子任务结束：{}", result.summary());
            return tickResult;
        }
        // 子任务还在做：把本刻算进时限，再看父任务给的时间有没有用完。
        activeTicks++;
        if (activeTicks >= maxTicks) {
            settleWithProblem("子任务被父任务的时限截停", Problem.of(Problem.Kind.STUCK,
                    "子任务已推进 " + activeTicks + " 刻还没有结果，超过父任务交给它的时间"));
            return TickResult.finished(result);
        }
        return TickResult.RUNNING;
    }

    /** 父任务被打断：子任务松开按键、停下进行中的动作，保留进度，打断结束后接着推进。 */
    public void pause() {
        if (child != null && result == null) {
            child.pause();
        }
    }

    /**
     * 父任务提前结束（被替换、被取消、角色没了）时调用：让子任务按同样的原因收尾，如实交代已发生的变化。
     * 只允许调用一次；子任务已经自己结算过时再调用是重复结算，直接抛异常。
     */
    public TaskResult close(CloseReason reason) {
        requireUnsettled("收尾");
        TaskResult closed = closeChild(reason);
        result = closed != null ? closed
                : TaskResult.cancelled("子任务没有交代结果就结束了：" + reason);
        return result;
    }

    /** 子任务是否已结算；结算后可用 {@link #result} 取最终结果。 */
    public boolean finished() {
        return result != null;
    }

    /** 结算后的最终结果；还没结算时为 null。 */
    public TaskResult result() {
        return result;
    }

    /** 此刻能不能被打断，看子任务的：正等游戏确认交互结果或悬空时不能插进不急的生存需求。 */
    public Interruptibility interruptibility(TickContext context) {
        if (child == null || result != null) {
            // 手上没有进行中的子任务（还没开始或已结算），不急的事可以趁这个空当插进来。
            return Interruptibility.BETWEEN_ACTIONS;
        }
        return child.interruptibility(context);
    }

    /** 给调试面板和日志的一句话：子任务此刻在做什么。 */
    public String describe() {
        if (result != null) {
            return "子任务已结束：" + result.summary();
        }
        return child == null ? "还没有子任务" : child.describe();
    }

    // 子任务没能自己给出结局（异常、超时）：先让它按取消收尾交代已发生的事，再把这些事实并进失败结果。
    private void settleWithProblem(String summary, Problem problem) {
        TaskResult closed = closeChild(CloseReason.CANCELLED);
        TaskResult failure = TaskResult.failed(summary, problem);
        result = closed == null ? failure : failure.withFactsBefore(List.of(closed));
        LOG.debug("子任务结算：{}", result.summary());
    }

    // 收尾子任务并拿它交代的结果；收尾本身再出错也不能挡住父任务拿到一个明确的结果。
    private TaskResult closeChild(CloseReason reason) {
        try {
            return child.close(reason);
        } catch (RuntimeException exception) {
            LOG.warn("子任务收尾时出错：{}", child.describe(), exception);
            return null;
        }
    }

    private static Problem internalError(RuntimeException exception) {
        return Problem.of(Problem.Kind.INTERNAL_ERROR, exception.getClass().getSimpleName()
                + (exception.getMessage() == null ? "" : "：" + exception.getMessage()));
    }

    private void requireUnsettled(String doing) {
        if (result != null) {
            throw new IllegalStateException("子任务已结算，不能再" + doing);
        }
        if (child == null) {
            throw new IllegalStateException("还没有开始子任务，不能" + doing);
        }
    }
}
