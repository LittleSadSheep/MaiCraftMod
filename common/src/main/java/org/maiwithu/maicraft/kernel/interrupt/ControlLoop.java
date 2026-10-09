// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

import org.maiwithu.maicraft.kernel.child.ChildTaskRunner;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 控制循环：每刻决定角色听谁的，并推进那个任务一刻。
 *
 * <p>角色同一时刻只听一个任务。栈底是主任务，上面是生存需求插进来的临时任务；临时任务做完就弹出，
 * 被打断的任务从原地接着做（不会再 start），它的进度跟踪也因为没有被推进而天然不计时。
 * 每一层都经子任务运行器推进：任务抛异常变成程序错误的结果，不会把控制循环一起带崩。
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
 * 不再为它创建新的临时任务——它的事已经有人在办了。临时任务没做成而处境没有更急时，
 * 这个需求先缓一阵再插：自救没成功就每刻重来一遍，只会让主任务永远动不了，也解决不了问题。
 * 主任务被临时任务停在了半路时只等生存需求先救命，活不接着干，等 LLM 明确换活或解除暂停。
 */
public final class ControlLoop {
    private static final Logger LOG = LoggerFactory.getLogger(ControlLoop.class);

    /**
     * 临时任务没做成后，同一个需求在同样急（或更缓）时先等多少刻再插进来：五秒。
     * 类别：玩家常识——自救没成功就先缓一缓，处境变了再试；处境变得更急则马上再试。
     */
    static final long RETRY_AFTER_FAILED_TICKS = 100;

    /** 接连没做成时等待逐次加倍，最长五分钟：弄不到吃的这类一时解决不了的事，不每隔几秒就打断一次手上的活。 */
    static final long MAX_RETRY_AFTER_FAILED_TICKS = 20L * 60 * 5;

    private final List<SurvivalNeed> needs;
    /** 主任务被停在半路时的一句话说明；没被停过为 null。 */
    private String parkedWhere;
    /** 从底到顶的运行栈；最底是主任务，上面是插进来的临时任务。只在客户端线程读写。 */
    private final List<Frame> stack = new ArrayList<>();
    /** 临时任务刚没做成的需求：在这一刻之前、且不比当时更急，就先不插。 */
    private final Map<SurvivalNeed, Backoff> backoffs = new HashMap<>();
    /** 各需求接连没做成的次数；处境解除或做成一次就清零。 */
    private final Map<SurvivalNeed, Integer> failureStreaks = new HashMap<>();
    /** 判断时抛过异常的需求；同一个需求的异常只记一次日志，免得每刻刷屏。 */
    private final Set<SurvivalNeed> brokenNeeds = new HashSet<>();

    /** 各生存需求在启动时登记一次；顺序决定同样急时谁先插进来。 */
    public ControlLoop(List<SurvivalNeed> needs) {
        this.needs = List.copyOf(needs);
    }

    /**
     * 换主任务：LLM 派了新活时调用。旧的主任务按"被替换"收尾，返回它交代的结果（没有旧主任务时为 null），
     * 调用方把它记进任务存储，已经发生的事不会因为换活而消失。已经插进来的临时任务不受影响，做完仍会弹出。
     * 新的主任务在第一次被推进时才 start。
     */
    public TaskResult setMainTask(Task task) {
        Objects.requireNonNull(task, "task");
        TaskResult replaced = endMain(CloseReason.REPLACED);
        stack.add(0, new Frame(task, null, null));
        return replaced;
    }

    /**
     * 结束主任务（LLM 取消、角色离开世界）：按给定原因收尾并返回它交代的结果；没有主任务时为 null。
     * 已经插进来的临时任务不受影响。
     */
    public TaskResult endMainTask(CloseReason reason) {
        return endMain(reason);
    }

    /**
     * 角色离开世界：插着的临时任务按"角色不在了"收尾弹出；主任务只从循环里撤下、不替它收尾——
     * 目标运行表已经让它停手并存成暂停，下次进这个世界时恢复。之前缓着的需求与停在半路的说明一并清掉。
     */
    public void leaveWorld() {
        for (int i = stack.size() - 1; i >= 0; i--) {
            Frame frame = stack.remove(i);
            if (frame.from != null) {
                frame.close(CloseReason.PLAYER_GONE);
            }
        }
        backoffs.clear();
        brokenNeeds.clear();
        parkedWhere = null;
    }

    /** 本刻正在被推进（或被压着等待恢复）的任务里最上面的那个；没有任务时为 null。 */
    public Task currentTask() {
        return stack.isEmpty() ? null : top().task;
    }

    /** 推进一刻。 */
    public Decision tick(TickContext context) {
        if (stack.isEmpty()) {
            // 手上没有任务，没有东西要保护：任何生存需求此刻都能插进来，找空当的也不例外。
            Choice choice = choose(context, Interruptibility.BETWEEN_ACTIONS, null);
            return choice.chosen == null ? Decision.IDLE : takeOver(choice, context);
        }
        Frame current = top();
        // 主任务被停在了半路：只等生存需求先救命，活不接着干；建不出救命任务就继续停着。
        if (current.paused) {
            for (SurvivalNeed need : needs) {
                if (hasLiveTask(need)) continue;
                Urgency urgency = urgencyOf(need, context, null);
                // 停着时也按没做成的等待来：救不成的需求不每刻重建一遍。
                if (urgency == null || heldBack(need, urgency, context)) continue;
                Task rescue;
                try {
                    rescue = need.createTask(context);
                } catch (RuntimeException exception) {
                    LOG.warn("生存需求「{}」创建临时任务时出错", need.name(), exception);
                    holdBack(need, urgency, context);
                    continue;
                }
                stack.add(new Frame(rescue, need, urgency));
                return advance(top(), context, null);
            }
            return new Decision.Parked(current.task, parkedWhere == null ? "位置不明" : parkedWhere);
        }
        Choice choice = choose(context, current.interruptibility(context), current.task);
        if (choice.chosen != null) {
            // 能走到这里，说明打断规则已经放行：NOW 本来就不管停不停得下，SOON 与 LATER 只在停下安全时才选得上。
            LOG.info("{}被{}打断（{}），插进临时任务", current.task.describe(), choice.chosen.name(), choice.urgency);
            current.pause();
            return takeOver(choice, context);
        }
        // 没有人能插进来：忍住，手上的任务继续；被按住的需求交给调用方提醒。
        return advance(current, context, choice.deferred);
    }

    /** 按打断规则挑出这一刻最急又能插进来的需求，同时记下最急的那个被按住的需求。 */
    private Choice choose(TickContext context, Interruptibility interruptibility, Task currentTask) {
        Choice choice = new Choice();
        for (SurvivalNeed need : needs) {
            if (hasLiveTask(need)) continue;
            // 需求可以看到此刻被推进的任务：要不要让位给主任务由需求自己结合处境判断。
            Urgency urgency = urgencyOf(need, context, currentTask);
            if (urgency == null) {
                // 处境已经不需要处理：之前没做成留下的等待一并作废，下次需要时马上能插。
                backoffs.remove(need);
                failureStreaks.remove(need);
                continue;
            }
            boolean waiting = heldBack(need, urgency, context);
            if (!waiting && InterruptRule.canInterrupt(urgency, interruptibility)) {
                // 同样急的先来先得：只有真的更急才顶掉前面选中的。
                if (choice.chosen == null || urgency.ordinal() > choice.urgency.ordinal()) {
                    choice.chosen = need;
                    choice.urgency = urgency;
                }
            } else if (choice.deferred == null || urgency.ordinal() > choice.deferredUrgency.ordinal()) {
                choice.deferred = need;
                choice.deferredUrgency = urgency;
            }
        }
        return choice;
    }

    /** 创建这个需求的临时任务压到栈顶并推进它；创建就出错时这个需求先缓一阵，本刻照常推进手上的任务。 */
    private Decision takeOver(Choice choice, TickContext context) {
        Task task;
        try {
            task = choice.chosen.createTask(context);
        } catch (RuntimeException exception) {
            LOG.warn("生存需求「{}」创建临时任务时出错", choice.chosen.name(), exception);
            holdBack(choice.chosen, choice.urgency, context);
            return stack.isEmpty() ? Decision.IDLE : advance(top(), context, choice.chosen);
        }
        stack.add(new Frame(task, choice.chosen, choice.urgency));
        return advance(top(), context, null);
    }

    /** 推进这一层一刻（第一次推进时先 start）；它若走到结局就弹出，临时任务没做成时它的需求先缓一阵。 */
    private Decision advance(Frame frame, TickContext context, SurvivalNeed deferred) {
        TickResult result;
        if (!frame.started) {
            frame.started = true;
            frame.runner.begin(frame.task, context);
            // 启动就出错的任务当场有了结果，不再推进它。
            result = frame.runner.finished() ? TickResult.finished(frame.runner.result()) : frame.runner.tick(context);
        } else {
            result = frame.runner.tick(context);
        }
        if (!(result instanceof TickResult.Finished finished)) {
            return new Decision.Advanced(frame.task, frame.from, deferred, null);
        }
        LOG.info("任务结束：{}（{}）", frame.task.describe(), finished.result().summary());
        stack.remove(frame);
        if (frame.from != null && finished.result().status() != TaskResult.Status.DONE) {
            // 自救没做成：处境不更急就先缓一阵再插，让主任务有机会动；做没做成都不影响主任务。
            holdBack(frame.from, frame.urgency, context);
        } else if (frame.from != null) {
            // 做成了一次：之前接连没做成的次数清零，下次再出事从五秒等起。
            failureStreaks.remove(frame.from);
        }
        // 临时任务回不到原来的岗位时不让主任务在新地点悄悄续上：停住等 LLM 决定。
        if (frame.task instanceof DisplacedTask displaced && displaced.cannotResumeInPlace()
                && !stack.isEmpty() && stack.getFirst().from == null) {
            stack.getFirst().paused = true;
            parkedWhere = displaced.stoppedWhere();
            return new Decision.Parked(stack.getFirst().task, parkedWhere);
        }
        return new Decision.Advanced(frame.task, frame.from, deferred, finished.result());
    }

    /** 收尾主任务并移出栈底；主任务还没被推进过时也按同样的原因收尾，让它交代结果。 */
    private TaskResult endMain(CloseReason reason) {
        if (stack.isEmpty() || stack.get(0).from != null) {
            return null;
        }
        Frame bottom = stack.remove(0);
        LOG.info("主任务结束（{}）：{}", reason, bottom.task.describe());
        return bottom.close(reason);
    }

    // 判断出错的需求按"此刻不需要处理"对待：一个需求的程序错误不能拖垮其余需求与主任务。
    private Urgency urgencyOf(SurvivalNeed need, TickContext context, Task currentTask) {
        try {
            Urgency urgency = need.urgency(context, currentTask);
            brokenNeeds.remove(need);
            return urgency;
        } catch (RuntimeException exception) {
            if (brokenNeeds.add(need)) {
                LOG.warn("生存需求「{}」判断急迫程度时出错，先按不需要处理对待", need.name(), exception);
            }
            return null;
        }
    }

    // 记一次没做成：这个需求先缓一阵，接连没做成就缓得更久。
    private void holdBack(SurvivalNeed need, Urgency urgency, TickContext context) {
        int streak = failureStreaks.merge(need, 1, Integer::sum);
        long delay = Math.min(RETRY_AFTER_FAILED_TICKS << Math.min(streak - 1, 12), MAX_RETRY_AFTER_FAILED_TICKS);
        backoffs.put(need, new Backoff(context.gameTick() + delay, urgency));
    }

    // 临时任务刚没做成的需求：没到时间、处境也没更急，就先不插。
    private boolean heldBack(SurvivalNeed need, Urgency urgency, TickContext context) {
        Backoff backoff = backoffs.get(need);
        if (backoff == null) return false;
        if (context.gameTick() >= backoff.untilTick() || urgency.ordinal() > backoff.urgency().ordinal()) {
            backoffs.remove(need);
            return false;
        }
        return true;
    }

    private Frame top() {
        return stack.get(stack.size() - 1);
    }

    /** 这个需求创建的临时任务是否还在栈里：正在推进，或被打断后等着恢复。 */
    private boolean hasLiveTask(SurvivalNeed need) {
        return stack.stream().anyMatch(frame -> frame.from == need);
    }

    /** 一个需求的临时任务没做成后的等待：到哪一刻为止，以及当时有多急。 */
    private record Backoff(long untilTick, Urgency urgency) {}

    /** 一刻的挑选结果：插进来的需求与它多急，以及最急的那个被按住的需求。 */
    private static final class Choice {
        SurvivalNeed chosen;
        Urgency urgency;
        SurvivalNeed deferred;
        Urgency deferredUrgency;
    }

    /** 栈里的一层：一个任务、推进它的子任务运行器、它来自哪个生存需求（主任务为 null）与插进来时多急。 */
    private static final class Frame {
        final Task task;
        final ChildTaskRunner runner = new ChildTaskRunner(ChildTaskRunner.NO_LIMIT);
        final SurvivalNeed from;
        final Urgency urgency;
        boolean started;
        /** 主任务被临时任务留在了半路：不能再推进，等 LLM 明确换任务或解除暂停。 */
        boolean paused;

        Frame(Task task, SurvivalNeed from, Urgency urgency) {
            this.task = Objects.requireNonNull(task, "task");
            this.from = from;
            this.urgency = urgency;
        }

        /** 被打断：推进过的经子任务运行器转发暂停，还没推进过的直接告诉任务。 */
        void pause() {
            if (started) {
                runner.pause();
            } else {
                task.pause();
            }
        }

        /** 此刻能不能打断：推进过的问子任务运行器，还没推进过的直接问任务自己。 */
        Interruptibility interruptibility(TickContext context) {
            return started ? runner.interruptibility(context) : task.interruptibility(context);
        }

        /** 按原因收尾并拿结果：推进过的经子任务运行器收尾，还没推进过的直接让任务交代。 */
        TaskResult close(CloseReason reason) {
            if (started) {
                return runner.finished() ? runner.result() : runner.close(reason);
            }
            try {
                return task.close(reason);
            } catch (RuntimeException exception) {
                LOG.warn("还没开始的任务收尾时出错：{}", task.describe(), exception);
                return TaskResult.cancelled("任务还没开始就结束了：" + reason);
            }
        }
    }

    /** 控制循环本刻的决定：推进了哪个任务、是哪个生存需求插进来的、谁被按住了、有没有任务就此结束。 */
    public sealed interface Decision {

        /** 手上没有任务也没有需要处理的生存需求，本刻什么都没做。 */
        Decision IDLE = new Idle();

        /**
         * 本刻推进了一个任务。
         *
         * @param task         被推进的任务
         * @param interrupting 插进来的生存需求；主任务正常推进时为 null
         * @param deferred     想插进来但被打断规则按住、或刚没做成正在缓一阵的生存需求；没有时为 null
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
