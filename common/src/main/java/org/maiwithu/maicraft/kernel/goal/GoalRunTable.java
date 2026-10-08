// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.task.CloseReason;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 目标运行表：LLM 下达过的目标都在这里按编号找到，查看、暂停、解除暂停、取消、回答都经过它。
 *
 * <p>新目标成为控制循环的主任务，原来的主任务按"被替换"收尾——角色同一时间只做 LLM 给的一件事。
 * 带同一个请求键的下达只算一次：网络重试时角色不会把同一件事做两遍。
 *
 * <p>结束了的目标只保留最近 {@value #KEPT_FINISHED} 条供查询；还没结束的一条都不丢。只在客户端线程使用。
 */
public final class GoalRunTable {
    static final int KEPT_FINISHED = 64;
    static final int KEPT_REQUEST_KEYS = 256;

    private final AbilityRegistry registry;
    private final GoalRunStore store;
    private final RemembersPlaces remembers;
    private final ControlLoop loop;
    /** 按下达顺序；结束了的超出保留条数时从最早的开始丢。 */
    private final Map<Long, GoalRunner> runners = new LinkedHashMap<>();
    private final Map<String, Long> requestKeys = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > KEPT_REQUEST_KEYS;
        }
    };
    /** 现在作为主任务交给控制循环的目标；没有时为 -1。 */
    private long mainRunId = -1;

    public GoalRunTable(AbilityRegistry registry, GoalRunStore store, RemembersPlaces remembers, ControlLoop loop) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.store = Objects.requireNonNull(store, "store");
        this.remembers = Objects.requireNonNull(remembers, "remembers");
        this.loop = Objects.requireNonNull(loop, "loop");
    }

    /**
     * 下达一个目标。请求键已经下达过、那个目标还查得到时，不再新开，返回原来那个。
     *
     * @param requestKey 网络重试用的幂等键；可以为 null
     */
    public Launch launch(Goal goal, String requestKey) {
        if (requestKey != null) {
            Long existing = requestKeys.get(requestKey);
            if (existing != null && runners.containsKey(existing)) {
                return new Launch(runners.get(existing), true);
            }
        }
        GoalRunner runner = GoalRunner.launch(goal, registry, store, remembers);
        long id = runner.run().id();
        runners.put(id, runner);
        if (requestKey != null) {
            requestKeys.put(requestKey, id);
        }
        makeMain(runner);
        dropOldFinished();
        return new Launch(runner, false);
    }

    /**
     * 重启后读回还没结束的目标，全部恢复为暂停，等 LLM 明确解除暂停才接着做。
     * sequence 里某一步自己的记录不单独恢复：整个 sequence 解除暂停后会找到它接着用。
     */
    public void restore() {
        for (GoalRun run : store.unfinished()) {
            if (run.parentRunId() < 0 && !runners.containsKey(run.id())) {
                runners.put(run.id(), GoalRunner.resume(run, registry, store, remembers));
            }
        }
    }

    /** 按编号查目标运行；不在表里（编号不存在，或结束太久已经不保留）时为空。 */
    public Optional<GoalRun> find(long id) {
        GoalRunner runner = runners.get(id);
        return runner == null ? Optional.empty() : Optional.of(runner.run());
    }

    /** 这个目标此刻在等的问题（sequence 时是正在跑的那一步的问题）；不在等回答时为空。 */
    public Optional<Question> pendingQuestion(long id) {
        return Optional.ofNullable(require(id).pendingQuestion());
    }

    /** 表里的目标运行，最近下达的在前。 */
    public List<GoalRun> recent() {
        List<GoalRun> runs = new ArrayList<>();
        for (GoalRunner runner : runners.values()) {
            runs.add(runner.run());
        }
        Collections.reverse(runs);
        return runs;
    }

    /** 暂停：手上的动作停下、松开按键，目标原地不动；生存需求照常处理（被打了照样还手）。 */
    public void pause(long id) {
        GoalRunner runner = require(id);
        GoalRunState state = runner.run().state();
        if (state != GoalRunState.RUNNING && state != GoalRunState.AWAITING_ANSWER) {
            throw new WrongGoalRunState("目标 " + id + " 现在是 " + state + "，不能暂停");
        }
        runner.pause();
        runner.run().pause();
        store.save(runner.run());
    }

    /** 解除暂停；它不是当前的主任务时（例如重启后恢复的），重新成为主任务，原来的主任务按"被替换"收尾。 */
    public void resume(long id) {
        GoalRunner runner = require(id);
        if (runner.run().state() != GoalRunState.PAUSED) {
            throw new WrongGoalRunState("目标 " + id + " 现在是 " + runner.run().state() + "，不在暂停");
        }
        runner.unpause();
        if (mainRunId != id) {
            makeMain(runner);
        }
    }

    /** 取消：正在跑的任务按"被取消"收尾，已经发生的变化如实记进结果；控制循环下一刻把它移走。 */
    public void cancel(long id) {
        GoalRunner runner = require(id);
        if (!runner.run().unfinished()) {
            throw new WrongGoalRunState("目标 " + id + " 已经结束");
        }
        runner.close(CloseReason.CANCELLED);
    }

    /**
     * 回答目标挂起的问题。
     *
     * @param optionId 所选回答的编号，必须是问题给出的选项之一
     */
    public void answer(long id, String optionId) {
        GoalRunner runner = require(id);
        if (runner.run().state() == GoalRunState.PAUSED) {
            throw new WrongGoalRunState("目标 " + id + " 暂停中，先解除暂停再回答");
        }
        Question question = runner.pendingQuestion();
        if (question == null) {
            throw new WrongGoalRunState("目标 " + id + " 没有在等回答");
        }
        if (question.options().stream().noneMatch(option -> option.id().equals(optionId))) {
            throw new WrongGoalRunState("问题没有编号为 " + optionId + " 的回答，可选："
                    + String.join("、", question.options().stream().map(Question.Option::id).toList()));
        }
        runner.answer(optionId);
    }

    private void makeMain(GoalRunner runner) {
        loop.setMainTask(runner);
        mainRunId = runner.run().id();
    }

    private GoalRunner require(long id) {
        GoalRunner runner = runners.get(id);
        if (runner == null) throw new UnknownGoalRun(id);
        return runner;
    }

    private void dropOldFinished() {
        long finished = runners.values().stream().filter(runner -> !runner.run().unfinished()).count();
        Iterator<GoalRunner> iterator = runners.values().iterator();
        while (finished > KEPT_FINISHED && iterator.hasNext()) {
            if (!iterator.next().run().unfinished()) {
                iterator.remove();
                finished--;
            }
        }
    }

    /**
     * 一次下达的结果。
     *
     * @param runner   推进这个目标的目标推进器
     * @param repeated 同一个请求键之前已经下达过，这次没有新开，返回的是原来那个
     */
    public record Launch(GoalRunner runner, boolean repeated) {}

    /** 编号不在表里：不存在，或者结束太久已经不保留。 */
    public static final class UnknownGoalRun extends RuntimeException {
        public UnknownGoalRun(long id) {
            super("没有编号为 " + id + " 的目标");
        }
    }

    /** 这个操作不适用于目标现在的处境，例如对已经结束的目标取消、对没在等回答的目标回答。 */
    public static final class WrongGoalRunState extends RuntimeException {
        public WrongGoalRunState(String message) {
            super(message);
        }
    }
}
