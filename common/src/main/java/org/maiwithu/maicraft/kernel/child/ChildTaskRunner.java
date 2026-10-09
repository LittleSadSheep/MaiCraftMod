// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.child;

import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Standing;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TaskInput;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 子任务运行器：父任务（目标推进或组合任务）要用另一个任务做事时，统一在这里创建、启动、
 * 逐刻推进、检查继承来的时限、把异常转成 INTERNAL_ERROR 问题、转发暂停、收结果、收尾子任务。
 *
 * <p>一次运行只用一个实例：{@code begin} 一次，之后每刻 {@code tick}，直到返回
 * {@link TickResult.Finished}，或父任务提前结束时调用 {@code close}。子任务结算后本实例不可再用，
 * 重复结算直接抛异常——那说明调用方写错了，不是游戏里发生了什么。
 *
 * <p>子任务卡住不在这里判定：分阶段任务自己带进度跟踪，多久没进展算卡住由它按自己的节奏判断，
 * 判出的失败结果原样向上传播；这里只检查父任务交给子任务的时限（预算），超时按进度跟踪的
 * 超时判定收尾。父任务被暂停的时间不推进预算：暂停期间 {@code tick} 本来就不会被调用。
 */
public final class ChildTaskRunner {
    private static final Logger LOG = LoggerFactory.getLogger(ChildTaskRunner.class);

    /** 父任务交给子任务的时限预算；只按子任务实际被推进的刻数计时。 */
    private final ProgressTracker budget;
    /** 正在运行的子任务；结算后保留到本实例被丢弃，供结果查询与描述。 */
    private Task child;
    /** 结算后的最终结果；没结算时为 null。 */
    private TaskResult result;

    /**
     * @param budget 父任务交给子任务的时限预算；只有超时判定起作用，卡住阈值不会被读到，传任意正数即可
     */
    public ChildTaskRunner(ProgressTracker budget) {
        this.budget = budget;
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
            // 启动就出错：子任务没有可保留的进度，按程序错误收场并交回失败，让父任务决定下一步。
            LOG.warn("子任务启动失败", exception);
            settle(failedResult("子任务启动失败", exception), CloseReason.CANCELLED);
        }
    }

    /** 推进子任务一刻；返回还在做或已有结果。结算之后再调用是调用方写错了，直接抛异常。 */
    public TickResult tick(TickContext context) {
        requireUnsettled("推进");
        // 子任务抛异常不连累父任务一起崩：转成 INTERNAL_ERROR 问题，如实告诉上层程序出了错。
        TickResult tickResult;
        try {
            tickResult = child.tick(context);
        } catch (RuntimeException exception) {
            LOG.warn("子任务推进时出错", exception);
            settle(failedResult("子任务推进时出错", exception), CloseReason.CANCELLED);
            return TickResult.finished(result);
        }
        if (tickResult instanceof TickResult.Finished finished) {
            // 子任务自己走到了结局：按任务接口的约定补上收尾调用，拿它声明的最终结果。
            settle(finished.result(), CloseReason.FINISHED);
            return TickResult.finished(result);
        }
        // 子任务还在做：先让它把本刻算进预算，再看继承的时限有没有用完。
        // 常驻任务（等待、跟随）等的就是时间本身，兜底时限对它豁免；卡没卡住由它自己的进度跟踪负责。
        budget.tick();
        if (budget.status() instanceof ProgressTracker.Status.TimedOut timedOut && !(child instanceof Standing)) {
            settle(TaskResult.failed("子任务被父任务的时限截停",
                            Problem.of(Problem.Kind.STUCK, "子任务已推进 " + timedOut.activeTicks()
                                    + " 刻还没有结果，超过父任务交给它的时间")),
                    CloseReason.CANCELLED);
            return TickResult.finished(result);
        }
        return TickResult.RUNNING;
    }

    /** 父任务被生存需求打断：子任务松开按键、停下进行中的动作，保留进度，轮回后接着推进。 */
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
        settle(null, reason);
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
        return child == null ? "还没有子任务" : "子任务：" + child.describe();
    }

    // 结算是唯一写结果的地方：先收子任务（它会把已发生的变化写进结果），再定下最终结果，之后本实例封存。
    private void settle(TaskResult declared, CloseReason reason) {
        TaskResult closedByChild = null;
        try {
            // 没有子任务声明的结果（提前收尾或异常），就取子任务按收尾原因交代的那个。
            closedByChild = child == null ? null : child.close(reason);
        } catch (RuntimeException exception) {
            // 子任务收尾再出错也不能挡住父任务拿到一个明确的结果。
            LOG.warn("子任务收尾时出错", exception);
        }
        result = declared != null ? declared
                : closedByChild != null ? closedByChild
                : TaskResult.cancelled("子任务没有交代结果就结束了：" + reason);
        LOG.debug("子任务结算：{}", result.summary());
    }

    private TaskResult failedResult(String summary, RuntimeException exception) {
        return TaskResult.failed(summary,
                Problem.of(Problem.Kind.INTERNAL_ERROR, exception.getClass().getSimpleName()
                        + (exception.getMessage() == null ? "" : "：" + exception.getMessage())));
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
