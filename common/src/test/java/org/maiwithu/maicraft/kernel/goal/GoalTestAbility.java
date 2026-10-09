// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TaskInput;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 替身能力：决定按脚本逐个给出，任务做完就按约定结局交结果。用来离线走通
 * "下达 → 推进 → 结果 → 存盘 → 重启后恢复为暂停"这条链。
 *
 * <p>同一种任务输入只能登记一次任务工厂，所以几个替身能力共用一张工厂表时，
 * 任务的先后与每个任务的结局都记在同一条时间线（{@link SharedTasks}）上。
 */
final class GoalTestAbility implements AbilityModule {

    /** 替身能力的任务输入：只有一句话标签。 */
    record TestInput(String label) implements TaskInput {
        @Override public String describe() {
            return label;
        }
    }

    /** 替身任务：逐刻返回约定结局；被提前收尾时如实交代被取消；可以约定启动就出错。 */
    static final class ScriptedGoalTask implements Task {
        private final TickResult ending;
        private final RuntimeException startError;
        int closes;
        int pauses;
        CloseReason closedWith;

        ScriptedGoalTask(TickResult ending) {
            this(ending, null);
        }

        ScriptedGoalTask(TickResult ending, RuntimeException startError) {
            this.ending = ending;
            this.startError = startError;
        }

        @Override public void start(TickContext context) {
            if (startError != null) throw startError;
        }

        @Override public TickResult tick(TickContext context) {
            return ending;
        }

        @Override public void pause() {
            pauses++;
        }

        @Override public TaskResult close(CloseReason reason) {
            closes++;
            closedWith = reason;
            return TaskResult.cancelled("替身任务被收尾");
        }

        @Override public String describe() {
            return "替身任务";
        }
    }

    /** 几个替身能力共用的任务时间线：启动了哪些任务、每个任务的结局是什么、建出来的任务对象。 */
    static final class SharedTasks {
        final List<TaskInput> started = new ArrayList<>();
        final Map<String, ScriptedGoalTask> created = new HashMap<>();
        private final Map<String, TaskResult> endings = new HashMap<>();
        private final Map<String, Boolean> stillRunning = new HashMap<>();
        private final Map<String, RuntimeException> startErrors = new HashMap<>();

        /** 指定某个任务的结局；没指定的默认做成了。 */
        void ending(String label, TaskResult result) {
            endings.put(label, result);
        }

        /** 指定某个任务一直在做、不自己结束。 */
        void keepRunning(String label) {
            stillRunning.put(label, true);
        }

        /** 指定某个任务启动就出错。 */
        void failOnStart(String label, RuntimeException error) {
            startErrors.put(label, error);
        }

        private ScriptedGoalTask taskFor(TestInput input) {
            TickResult ending = stillRunning.containsKey(input.label())
                    ? TickResult.RUNNING
                    : TickResult.finished(endings.getOrDefault(input.label(), TaskResult.done("做完了")));
            ScriptedGoalTask task = new ScriptedGoalTask(ending, startErrors.get(input.label()));
            created.put(input.label(), task);
            return task;
        }
    }

    private final String id;
    private final SharedTasks shared;
    /** 依次给出的决定；给过就弹掉，用完后不再给（返回 NOT_READY），测试按刻数驱动。 */
    final Deque<StepDecision> decisions = new ArrayDeque<>();
    /** 能力每次做决定时看到的 LLM 回答，按看到顺序累积。 */
    final List<String> answersSeen = new ArrayList<>();
    /** 能力最近一次做决定时看到的已结束任务的结果。 */
    List<TaskResult> taskResultsSeen = List.of();
    /** 这个替身能力的钩子；默认不做特殊处理。 */
    AbilityHooks hooks = AbilityHooks.NONE;

    GoalTestAbility(String id) {
        this(id, new SharedTasks());
    }

    /** 几个替身能力共用一张任务工厂表时，任务的先后与结局记在同一条时间线上。 */
    GoalTestAbility(String id, SharedTasks shared) {
        this.id = id;
        this.shared = shared;
    }

    @Override public AbilitySpec spec() {
        return new AbilitySpec(id, "替身能力", AbilityDoc.forAbility("test"),
                ParamSpecs.EMPTY, Set.of(), ExecutionMode.CONTROLS_PLAYER, Set.of(), List.of(), Listing.LISTED);
    }

    @Override public StepDecision decide(StepContext step) {
        answersSeen.addAll(step.answers());
        taskResultsSeen = step.taskResults();
        // 决定给过就弹掉；脚本用完后不再给（返回 NOT_READY），测试按刻数驱动。
        return decisions.isEmpty() ? StepDecision.NOT_READY : decisions.poll();
    }

    /** 排入一个决定，下一次问能力时给出。 */
    void next(StepDecision decision) {
        decisions.addLast(decision);
    }

    @Override public void registerTasks(TaskFactories factories) {
        // 同一种任务输入只能登记一次；后来登记的能力共用先登记的工厂，时间线也就共用一条。
        if (factories.supports(TestInput.class)) {
            return;
        }
        factories.register(TestInput.class, input -> {
            shared.started.add(input);
            return shared.taskFor((TestInput) input);
        });
    }

    @Override public AbilityHooks hooks() {
        return hooks;
    }

    /** 已启动的任务输入，按顺序。 */
    List<TaskInput> startedInputs() {
        return shared.started;
    }

    /** 指定某个任务的结局；没指定的默认做成了。 */
    void ending(String label, TaskResult result) {
        shared.ending(label, result);
    }

    /** 共用的任务时间线。 */
    SharedTasks shared() {
        return shared;
    }
}
