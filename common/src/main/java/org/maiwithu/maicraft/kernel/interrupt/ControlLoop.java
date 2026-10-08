// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.kernel.task.Urgency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 控制循环：每刻决定角色听谁的，并推进那个任务一刻。
 *
 * <p>角色同一时刻只听一个任务。栈底是主任务，上面是生存需求插进来的临时任务；临时任务做完就弹出，
 * 被打断的任务从原地接着做（不会再 start），它的进度跟踪也因为没有被推进而天然不计时。
 *
 * <p>每刻的顺序：
 * <ol>
 *   <li>逐个问各生存需求此刻有多急（不需要处理的返回 null，不参与）；</li>
 *   <li>问手上的任务此刻能不能被打断；</li>
 *   <li>按打断规则挑出最急又能插进来的需求：能插就先暂停手上的任务、创建并推进它的临时任务；
 *       不能插就忍住，手上的任务照常推进，被按住的需求留给调用方去提醒；</li>
 *   <li>被推进的任务这一刻走到了结局，就收尾并弹出，下一刻轮到栈里下面的任务恢复。</li>
 * </ol>
 *
 * <p>同样急的几个需求按登记顺序，先登记的先插。某个需求自己的临时任务还在栈里（正在推进或等着恢复）时，
 * 不再为它创建新的临时任务——它的事已经有人在办了。
 */
public final class ControlLoop {
    private static final Logger LOG = LoggerFactory.getLogger(ControlLoop.class);

    private final List<SurvivalNeed> needs;
    /** 主任务被停在半路时的一句话说明；没被停过为 null。 */
    private String parkedWhere;
    /** 从底到顶的运行栈；最底是主任务，上面是插进来的临时任务。只在客户端线程读写。 */
    private final List<Frame> stack = new ArrayList<>();

    /** 各生存需求在启动时登记一次；顺序决定同样急时谁先插进来。 */
    public ControlLoop(List<SurvivalNeed> needs) {
        this.needs = List.copyOf(needs);
    }

    /**
     * 换主任务：LLM 派了新活时调用。旧的主任务按"被替换"收尾，进度不再保留；
     * 已经插进来的临时任务不受影响，做完仍会弹出。新的主任务在第一次被推进时才 start。
     */
    public void setMainTask(Task task) {
        Objects.requireNonNull(task, "task");
        if (stack.isEmpty()) {
            stack.add(new Frame(task, null));
            return;
        }
        Frame bottom = stack.get(0);
        if (bottom.from == null) {
            // 主任务可能正在推进，也可能被临时任务压在下面等着恢复；两种情况都直接换掉。
            LOG.info("主任务被替换：{}", bottom.task.describe());
            bottom.task.close(CloseReason.REPLACED);
            stack.set(0, new Frame(task, null));
        } else {
            stack.add(0, new Frame(task, null));
        }
    }

    /** 本刻正在被推进（或被压着等待恢复）的任务里最上面的那个；没有任务时为 null。 */
    public Task currentTask() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1).task;
    }

    /** 推进一刻。 */
    public Decision tick(TickContext context) {
        if (stack.isEmpty()) {
            // 手上没有任务，没有东西要保护：任何生存需求此刻都能插进来，找空当的也不例外。
            for (SurvivalNeed need : needs) {
                if (need.urgency(context) != null) {
                    return takeOver(need, context);
                }
            }
            return Decision.IDLE;
        }
        Frame current = top();
        // 主任务被停在了半路：只等生存需求先救命，活不接着干。
        if (current.paused) {
            for (SurvivalNeed need : needs) {
                if (need.urgency(context) != null) {
                    return takeOver(need, context);
                }
            }
            return new Decision.Parked(current.task, parkedWhere == null ? "位置不明" : parkedWhere);
        }
        Interruptibility interruptibility = current.task.interruptibility(context);
        SurvivalNeed chosen = null;
        Urgency chosenUrgency = null;
        SurvivalNeed deferred = null;
        Urgency deferredUrgency = null;
        for (SurvivalNeed need : needs) {
            if (hasLiveTask(need)) continue;
            // 需求可以看到此刻被推进的任务：要不要让位给主任务由需求自己结合处境判断。
            Urgency urgency = need.urgency(context, current.task);
            if (urgency == null) continue;
            if (InterruptRule.canInterrupt(urgency, interruptibility)) {
                // 同样急的先来先得：只有真的更急才顶掉前面选中的。
                if (chosen == null || urgency.ordinal() > chosenUrgency.ordinal()) {
                    chosen = need;
                    chosenUrgency = urgency;
                }
            } else if (deferred == null || urgency.ordinal() > deferredUrgency.ordinal()) {
                deferred = need;
                deferredUrgency = urgency;
            }
        }
        if (chosen != null) {
            // 能走到这里，说明打断规则已经放行：NOW 本来就不管停不停得下，SOON 与 LATER 只在停下安全时才选得上。
            LOG.info("{}被{}打断（{}），插进临时任务", current.task.describe(), chosen.name(), chosenUrgency);
            current.task.pause();
            return takeOver(chosen, context);
        }
        // 没有人能插进来：忍住，手上的任务继续；被按住的需求交给调用方提醒。
        return advance(current, context, deferred);
    }

    /** 暂停手上的任务，创建并推进这个需求的临时任务。 */
    private Decision takeOver(SurvivalNeed need, TickContext context) {
        stack.add(new Frame(need.createTask(context), need));
        return advance(top(), context, null);
    }

    /** 推进栈顶的任务一刻；它若走到结局就收尾并弹出。 */
    private Decision advance(Frame frame, TickContext context, SurvivalNeed deferred) {
        if (!frame.started) {
            frame.task.start(context);
            frame.started = true;
        }
        TickResult result = frame.task.tick(context);
        if (result instanceof TickResult.Finished) {
            LOG.info("任务结束：{}", frame.task.describe());
            TaskResult value = frame.task.close(CloseReason.FINISHED);
            stack.remove(stack.size() - 1);
            // 临时任务回不到原来的岗位时不让主任务在新地点悄悄续上：停住等 LLM 决定。
            if (frame.task instanceof DisplacedTask displaced && displaced.cannotResumeInPlace()
                    && !stack.isEmpty() && stack.getFirst().from == null) {
                stack.getFirst().paused = true;
                parkedWhere = displaced.stoppedWhere();
                return new Decision.Parked(stack.getFirst().task, parkedWhere);
            }
            return new Decision.Advanced(frame.task, frame.from, deferred, value);
        }
        return new Decision.Advanced(frame.task, frame.from, deferred, null);
    }

    private Frame top() {
        return stack.get(stack.size() - 1);
    }

    /** 这个需求创建的临时任务是否还在栈里：正在推进，或被打断后等着恢复。 */
    private boolean hasLiveTask(SurvivalNeed need) {
        return stack.stream().anyMatch(frame -> frame.from == need);
    }

    /** 栈里的一层：一个任务、它来自哪个生存需求（主任务为 null）、是否已经 start。 */
    private static final class Frame {
        final Task task;
        final SurvivalNeed from;
        boolean started;
        /** 主任务被临时任务留在了半路：不能再推进，等 LLM 明确换任务或解除暂停。 */
        boolean paused;

        Frame(Task task, SurvivalNeed from) {
            this.task = Objects.requireNonNull(task, "task");
            this.from = from;
        }
    }

    /** 控制循环本刻的决定：推进了哪个任务、是哪个生存需求插进来的、谁被规则按住了、有没有任务就此结束。 */
    public sealed interface Decision {

        /** 手上没有任务也没有需要处理的生存需求，本刻什么都没做。 */
        Decision IDLE = new Idle();

        /**
         * 本刻推进了一个任务。
         *
         * @param task         被推进的任务
         * @param interrupting 插进来的生存需求；主任务正常推进时为 null
         * @param deferred     想插进来但被规则按住的生存需求；没有时为 null
         * @param finished     任务本刻走到结局时的结果；还在做时为 null
         */
        record Advanced(Task task, SurvivalNeed interrupting, SurvivalNeed deferred, TaskResult finished)
                implements Decision {}

        /**
         * 临时任务收尾时把主任务停在了半路：主任务不再推进，事件里带上停在了哪里。
         * LLM 明确派新任务（换掉主任务）之前，角色不再替停住的主任务继续干活。
         */
        record Parked(Task pausedMain, String where) implements Decision {}

        /** 本刻什么都没做。 */
        record Idle() implements Decision {}
    }
}
