// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.maiwithu.maicraft.game.player.DeathFacts;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>目标成为主任务的那一刻（新下达，或暂停的目标被恢复），经控制权交接接缝向输入层请求角色
 * 的控制权；没拿到之前不推进，查询端如实说在等交接。人按 F8 收回角色后不会被抢回来，
 * 等下一个目标下达或恢复时才再次请求。
 *
 * <p>结束了的目标只保留最近 {@value #KEPT_FINISHED} 条供查询；还没结束的一条都不丢。只在客户端线程使用。
 */
public final class GoalRunTable {
    private static final Logger LOG = LoggerFactory.getLogger(GoalRunTable.class);
    static final int KEPT_FINISHED = 64;
    static final int KEPT_REQUEST_KEYS = 256;
    /** 不控制角色的目标最多当场推进几刻：记地点、出报告都是一两刻的事。 */
    static final int ASIDE_TICK_LIMIT = 20;

    private final AbilityRegistry registry;
    /** 死亡恢复决策：角色死后给 LLM 的选择，挂在这里，回答也先到这里。 */
    private final DeathRecovery deathRecovery;
    /** 当期的目标运行存储：进世界时换成那个世界的存盘，下达、推进、恢复都落在它上面。 */
    private GoalRunStore store;
    private final ControlLoop loop;
    /** 控制权交接：目标成为主任务时经它请求控制权，查询端经它看此刻谁拥有控制权。 */
    private final PlayerControlHandover handover;
    /** 当期的记地点入口：进世界时接上，退世界恢复占位。推进器都经 relay 读它，换世界不用换推进器。 */
    private RemembersPlaces places;
    /** 记地点的固定转发：目标推进器拿着它，每次记地点都落到当期的入口上。 */
    private final RemembersPlaces relay = (name, position) -> places.remember(name, position);
    /** 退世界后恢复的占位：没有当期记忆时记地点是程序错误，不悄悄丢掉。 */
    private final RemembersPlaces detachedPlaceholder;
    /** 按下达顺序；结束了的超出保留条数时从最早的开始丢。 */
    private final Map<Long, GoalRunner> runners = new LinkedHashMap<>();
    private final Map<String, Long> requestKeys = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > KEPT_REQUEST_KEYS;
        }
    };
    /** 现在作为主任务交给控制循环的目标；没有时为 -1。 */
    private long mainRunId = -1;

    public GoalRunTable(AbilityRegistry registry, GoalRunStore store, RemembersPlaces remembers, ControlLoop loop,
                        PlayerControlHandover handover) {
        this(registry, store, remembers, loop, handover,
                new DeathRecovery(new TaskEventLog()), DeathRecoveryActions.NONE);
    }

    public GoalRunTable(AbilityRegistry registry, GoalRunStore store, RemembersPlaces remembers, ControlLoop loop,
                        PlayerControlHandover handover, DeathRecovery deathRecovery,
                        DeathRecoveryActions deathActions) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.store = Objects.requireNonNull(store, "store");
        this.detachedPlaceholder = Objects.requireNonNull(remembers, "remembers");
        this.places = remembers;
        this.loop = Objects.requireNonNull(loop, "loop");
        this.handover = Objects.requireNonNull(handover, "handover");
        this.deathRecovery = Objects.requireNonNull(deathRecovery, "deathRecovery");
        this.deathActions = Objects.requireNonNull(deathActions, "deathActions");
    }

    /** 控制循环看到角色死亡的第一刻把现场事实交到这里：挂一次死亡恢复决策，同一个死亡过程只挂一次。 */
    public void characterDied(DeathFacts facts, boolean connectionAlive) {
        deathRecovery.onDeath(facts, connectionAlive);
    }

    /** 死亡过程结束（重生或以别的方式回到活体）：本轮死亡决策了结，下次死亡再挂新的。 */
    public void characterAliveAgain() {
        deathRecovery.backAlive();
    }

    /**
     * 进一个世界：记地点指到这个世界的世界记忆，存储换成这个世界的存盘，再把上次没结束的目标读回来，
     * 全部恢复为暂停，等 LLM 明确解除暂停才接着做。换世界是整份现场丢弃重建，进之前先离开上一个世界。
     */
    public void enterWorld(RemembersPlaces worldPlaces, GoalRunStore worldStore) {
        this.places = Objects.requireNonNull(worldPlaces, "worldPlaces");
        this.store = Objects.requireNonNull(worldStore, "worldStore");
        restore();
    }

    /**
     * 离开世界（退出到标题、断线、换世界）：每个没结束的目标停手并存成暂停，控制循环撤下主任务、
     * 临时任务按"角色不在了"收尾；表清空，记地点摘回占位。下次进同一个世界时从存盘恢复，和重启游戏是同一回事。
     */
    public void leaveWorld() {
        for (GoalRunner runner : runners.values()) {
            if (!runner.run().unfinished()) continue;
            try {
                runner.leaveWorld();
            } catch (RuntimeException failure) {
                // 一个目标停手时出错不能拦住其余目标存盘：记下来，接着处理下一个。
                LOG.warn("目标 {} 随角色离开世界停手时出错", runner.run().id(), failure);
            }
        }
        loop.leaveWorld();
        runners.clear();
        requestKeys.clear();
        mainRunId = -1;
        deathRecovery.forget();
        places = detachedPlaceholder;
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
        GoalRunner runner = GoalRunner.launch(goal, registry, store, relay);
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
     * 不控制角色的目标（只读分析、只改记忆）：当场推进到结果，不交给控制循环，也不打断手上的主任务——
     * 角色在盖房子时让它记住一个地点，房子照盖。这类能力的决定只该是直接给结果或记地点；
     * 几刻之内还没有结果（例如提了问题），按取消收尾，不让它挂着没人推进。
     */
    public GoalRun runAside(Goal goal, TickContext context) {
        GoalRunner runner = GoalRunner.launch(goal, registry, store, relay);
        runners.put(runner.run().id(), runner);
        runner.start(context);
        for (int i = 0; i < ASIDE_TICK_LIMIT && runner.run().state() == GoalRunState.RUNNING; i++) {
            if (runner.tick(context) instanceof TickResult.Finished) {
                runner.close(CloseReason.FINISHED);
            }
        }
        if (runner.run().unfinished()) {
            runner.close(CloseReason.CANCELLED);
        }
        dropOldFinished();
        return runner.run();
    }

    /** 这个目标此刻在做什么的一句话；已经结束时为空。
     *  自动化还没拿到控制权时如实说明在等交接，不把"卡在第 0 步"呈现成正在推进。 */
    public Optional<String> doing(long id) {
        if (deathRecovery.owns(id)) {
            // 死亡恢复决策的编号不在 runners 表里：和按编号查目标一样先认领，返回决策的说明。
            // answer 成功后同一次工具调用还会回来查视图，这里漏特判会把已经成功的回答污染成"编号不存在"。
            return Optional.of(deathRecovery.describe());
        }
        GoalRunner runner = require(id);
        if (!runner.run().unfinished()) return Optional.empty();
        String describe = runner.describe();
        if (runner.run().state() == GoalRunState.RUNNING && !handover.automationOwnsControls()) {
            // 人按 F8 收回了角色：只能等人交回，重新下达也抢不回来，如实说清，免得 LLM 反复下达。
            if (handover.humanTookOver()) {
                return Optional.of("角色在玩家手上（按过 F8），玩家再按 F8 交回之前不会推进：" + describe);
            }
            return Optional.of("等待控制权交接（下一刻生效）：" + describe);
        }
        return Optional.of(describe);
    }

    /**
     * 重启后读回还没结束的目标，全部恢复为暂停，等 LLM 明确解除暂停才接着做。
     * sequence 里某一步自己的记录不单独恢复：整个 sequence 解除暂停后会找到它接着用。
     */
    public void restore() {
        for (GoalRun run : store.unfinished()) {
            if (run.parentRunId() == GoalRun.NO_PARENT && !runners.containsKey(run.id())) {
                runners.put(run.id(), GoalRunner.restore(run, registry, store, relay));
            }
        }
    }

    /** 现在作为主任务的目标；没有主任务，或者它已经结束时为空。observe(self) 用它说"手上在做什么"。 */
    public Optional<GoalRun> mainGoal() {
        GoalRunner runner = runners.get(mainRunId);
        return runner == null || !runner.run().unfinished() ? Optional.empty() : Optional.of(runner.run());
    }

    /** 按编号查目标运行；不在表里（编号不存在，或结束太久已经不保留）时为空。挂着的死亡恢复决策也按编号查得到。 */
    public Optional<GoalRun> find(long id) {
        if (deathRecovery.owns(id)) {
            return deathRecovery.decisionRun();
        }
        GoalRunner runner = runners.get(id);
        return runner == null ? Optional.empty() : Optional.of(runner.run());
    }

    /** 这个目标此刻在等的问题（sequence 时是正在跑的那一步的问题）；不在等回答时为空。死亡恢复的问题也从这里看。 */
    public Optional<Question> pendingQuestion(long id) {
        if (deathRecovery.owns(id)) {
            return Optional.ofNullable(deathRecovery.pendingQuestion());
        }
        return Optional.ofNullable(require(id).pendingQuestion());
    }

    /** 表里的目标运行，最近下达的在前；挂着死亡恢复决策时它排在最前面。 */
    public List<GoalRun> recent() {
        List<GoalRun> runs = new ArrayList<>();
        for (GoalRunner runner : runners.values()) {
            runs.add(runner.run());
        }
        Collections.reverse(runs);
        // 挂着的死亡恢复决策排在最前面：它是此刻最要紧、最该回答的一条。
        deathRecovery.decisionRun().ifPresent(first -> runs.add(0, first));
        return runs;
    }

    /** 暂停：手上的动作停下、松开按键，目标原地不动；生存需求照常处理（被打了照样还手）。 */
    public void pause(long id) {
        requireNotDeathDecision(id);
        GoalRunner runner = require(id);
        GoalRunState state = runner.run().state();
        if (state != GoalRunState.RUNNING && state != GoalRunState.AWAITING_ANSWER) {
            throw new WrongGoalRunState("目标 " + id + " 现在是 " + state + "，不能暂停");
        }
        runner.pauseGoal();
    }

    /** 解除暂停；它不是当前的主任务时（例如重启后恢复的），重新成为主任务，原来的主任务按"被替换"收尾。 */
    public void resume(long id) {
        requireNotDeathDecision(id);
        GoalRunner runner = require(id);
        if (runner.run().state() != GoalRunState.PAUSED) {
            throw new WrongGoalRunState("目标 " + id + " 现在是 " + runner.run().state() + "，不在暂停");
        }
        runner.resumeGoal();
        if (mainRunId != id) {
            makeMain(runner);
        } else {
            // 已经是主任务也要请求：恢复是一次明确的下达；人按 F8 收回了角色时请求不生效，由输入层把关。
            handover.requestControl();
        }
    }

    /** 取消：正在跑的任务按"被取消"收尾，已经发生的变化如实记进结果。 */
    public void cancel(long id) {
        requireNotDeathDecision(id);
        GoalRunner runner = require(id);
        if (!runner.run().unfinished()) {
            throw new WrongGoalRunState("目标 " + id + " 已经结束");
        }
        if (mainRunId == id) {
            // 是主任务：经控制循环收尾，它会连同压在上面等它的位置一起清掉。
            loop.endMainTask(CloseReason.CANCELLED);
            mainRunId = -1;
        } else {
            runner.close(CloseReason.CANCELLED);
        }
    }

    /**
     * 回答目标挂起的问题。
     *
     * @param optionId 所选回答的编号，必须是问题给出的选项之一
     */
    public void answer(long id, String optionId) {
        if (id < 0) {
            // 负数编号是死亡恢复决策的数域：不管本轮决策还挂着没有，回答都先走死亡恢复这边，
            // 决策已了结的迟到答复才能得到"本轮已了结"的明确回话，而不是被当成编号不存在。
            applyDeathChoice(deathRecovery.answer(id, optionId));
            return;
        }
        GoalRunner runner = require(id);
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

    /**
     * 执行死亡恢复的回答：重生与观战经原生动作发请求，发不出去就把同一个问题重新挂上，不吞答复；
     * 取消目标就地收尾主任务（当前目标），死亡屏幕留给人。发出去或取消了，本轮决策就地了结。
     */
    private void applyDeathChoice(DeathRecovery.Choice choice) {
        switch (choice) {
            case RESPAWN -> deathRecovery.applied(choice, deathActions.requestRespawn());
            case SPECTATE -> deathRecovery.applied(choice, deathActions.requestSpectate());
            case CANCEL_GOAL -> {
                if (mainRunId != -1) {
                    // 是主任务：经控制循环收尾，它会连同压在上面等它的位置一起清掉。
                    loop.endMainTask(CloseReason.CANCELLED);
                    mainRunId = -1;
                }
                deathRecovery.applied(choice, true);
            }
        }
    }

    /** 死亡恢复的记录只能用 answer 回答：暂停、恢复、取消对它都不适用，如实说清。 */
    private void requireNotDeathDecision(long id) {
        if (deathRecovery.owns(id)) {
            throw new WrongGoalRunState("死亡恢复决策只用 answer 回答");
        }
    }

    private void makeMain(GoalRunner runner) {
        loop.setMainTask(runner);
        mainRunId = runner.run().id();
        // 成为决定角色在做什么的那一方，就向输入层请求控制权；人类 F8 抢回后不立刻抢回，
        // 这里就是"下一个目标下达或恢复时才再次请求"的那个时机。
        handover.requestControl();
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

    /** 死亡恢复的原生动作：重生与观战的请求从这里发出去；启动时接上，测试给替身。 */
    private final DeathRecoveryActions deathActions;

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
