// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.event;

import org.maiwithu.maicraft.kernel.goal.GoalRun;
import org.maiwithu.maicraft.kernel.goal.GoalRunState;
import org.maiwithu.maicraft.kernel.goal.GoalRunStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 会发任务事件的目标运行存储：目标推进每次处境变化都会存盘，这里先交给真正的存储，
 * 再和这条记录上次的处境比较，把变化发成任务事件。目标推进器因此不用在每个分支里记得发事件。
 *
 * <p>sequence 的每一步都有自己的目标运行记录，但 LLM 只认识自己下达的那个编号：
 * 步骤的提问、暂停、结束都记在整个 sequence 的编号上，步骤开始不单独发事件；步骤结束的消息带上第几步与能力。
 *
 * <p>目标或某一步结束时，事件上附结束那一刻角色的一行自身摘要：LLM 读到结束接着决定下一步，
 * 不用先再看一次自己在哪、血够不够、手上拿着什么、界面关没关。
 *
 * <p>停滞提醒在这里看：每刻查一遍还没结束的目标，一条目标太久没有任何事件、
 * 又没有任何终态，就发一条停滞的提醒——受理方不用自己拿表计时干等（实机 10-16 目标挂在
 * 进行中直到进程关闭，对面的任务台账每半小时报一次"无进展"，报了 25 条都没有终态）。
 *
 * <p>只在客户端线程使用。
 */
public final class EventPublishingGoalRunStore implements GoalRunStore {
    private final GoalRunStore delegate;
    private final TaskEventLog log;
    /** 结束那一刻角色的一行自身摘要。 */
    private final ReadsSelfBrief selfBrief;
    /** 各条还没结束的记录上次存盘时的处境；结束后移除，不会越积越多。 */
    private final Map<Long, GoalRunState> lastStates = new HashMap<>();
    /** 单调时钟：停滞按它计时，游戏时间校对、回挡绕不过冷却。 */
    private final LongSupplier clock;
    /** 每条在途目标最后一次发事件的时刻；结束后移除。 */
    private final Map<Long, Long> lastEventNanos = new HashMap<>();
    /** 每条在途目标上一次发停滞提醒的时刻：同一个停滞不重复提醒，来了新事件重新计时。 */
    private final Map<Long, Long> lastStallNoticeNanos = new HashMap<>();

    /** 目标多久没有任何事件算停滞：十分钟。比对面任务台账的半小时早报，留出处置时间。 */
    static final long STALL_AFTER_NANOS = 10L * 60 * 1_000_000_000L;

    /** 不附自身摘要的一份：离线测试与还没接上现场时用。 */
    public EventPublishingGoalRunStore(GoalRunStore delegate, TaskEventLog log) {
        this(delegate, log, ReadsSelfBrief.NONE);
    }

    public EventPublishingGoalRunStore(GoalRunStore delegate, TaskEventLog log, ReadsSelfBrief selfBrief) {
        this(delegate, log, selfBrief, System::nanoTime);
    }

    /** @param clock 单调时钟，测试给定值推进 */
    public EventPublishingGoalRunStore(GoalRunStore delegate, TaskEventLog log, ReadsSelfBrief selfBrief,
            LongSupplier clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.log = Objects.requireNonNull(log, "log");
        this.selfBrief = Objects.requireNonNull(selfBrief, "selfBrief");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public long nextId() {
        return delegate.nextId();
    }

    @Override public void save(GoalRun run) {
        delegate.save(run);
        GoalRunState before = run.state() == GoalRunState.FINISHED
                ? lastStates.remove(run.id())
                : lastStates.put(run.id(), run.state());
        if (run.state() == GoalRunState.FINISHED) {
            // 结束了：停滞的计时与提醒一并清掉，省得同一条编号再被提醒。
            lastEventNanos.remove(run.id());
            lastStallNoticeNanos.remove(run.id());
        } else {
            lastEventNanos.put(run.id(), clock.getAsLong());
            lastStallNoticeNanos.remove(run.id());
        }
        if (before != run.state()) {
            publish(run, before);
        }
    }

    @Override public List<GoalRun> unfinished() {
        return delegate.unfinished();
    }

    /**
     * 每刻看一眼：哪条还没结束的目标太久没有任何事件了，发一条停滞提醒。
     * 只看 LLM 下达的那些（sequence 的步骤记在整个 sequence 的编号上）。
     */
    public void tick() {
        long now = clock.getAsLong();
        for (GoalRun run : delegate.unfinished()) {
            if (run.parentRunId() != GoalRun.NO_PARENT || run.state() == GoalRunState.PAUSED) {
                continue;
            }
            Long last = lastEventNanos.get(run.id());
            if (last == null) {
                // 存盘里恢复出来的、这个会话还没见过事件的：从现在起算，不拿存档前的旧账报停滞。
                lastEventNanos.put(run.id(), now);
                continue;
            }
            Long noticed = lastStallNoticeNanos.get(run.id());
            if (now - last < STALL_AFTER_NANOS || noticed != null && now - noticed < STALL_AFTER_NANOS) {
                continue;
            }
            lastStallNoticeNanos.put(run.id(), now);
            long minutes = (now - last) / 60_000_000_000L;
            log.append(TaskEvent.Kind.STALLED, run.id(), "目标 " + run.id() + "（" + describe(run) + "）已经 "
                    + minutes + " 分钟没有任何新事件，仍在推进中；用 goal 按编号看它此刻在做什么，不会再动了就取消", null);
        }
    }

    /** 处境变了：按"从哪来、到哪去"发一条事件；只是回答了问题、回到推进，不另发事件。 */
    private void publish(GoalRun run, GoalRunState before) {
        boolean step = run.parentRunId() != GoalRun.NO_PARENT;
        long id = step ? run.parentRunId() : run.id();
        switch (run.state()) {
            case RUNNING -> {
                if (before == null && !step) {
                    log.append(TaskEvent.Kind.STARTED, id, "开始：" + describe(run), null);
                } else if (before == GoalRunState.PAUSED) {
                    log.append(TaskEvent.Kind.RESUMED, id, "接着推进：" + describe(run), null);
                }
            }
            case AWAITING_ANSWER -> {
                String question = run.question().text();
                if (before == GoalRunState.PAUSED) {
                    log.append(TaskEvent.Kind.RESUMED, id, "解除暂停，仍在等回答：" + question, null);
                } else {
                    log.append(TaskEvent.Kind.ASKED, id, question, null);
                }
            }
            // 被临时任务带离原地停下的，带上为什么停、停在哪：LLM 不必猜是不是游戏重连了。
            case PAUSED -> log.append(TaskEvent.Kind.PAUSED, id, "暂停：" + describe(run) + run.pauseNote("；"), null);
            // sequence 的一步做完记在整件 sequence 的编号上，结论句前带上第几步、哪个能力：连着几步结论一样
            // （一格一格施工每步都是"按图建好了"）时，读事件的人也分得清做完的是哪一步。
            // 结束这一刻顺手附上角色的一行自身摘要（在哪、血、饱食、主手、开着的界面），读不到就不附。
            case FINISHED -> log.append(step ? TaskEvent.Kind.STEP_FINISHED : TaskEvent.Kind.FINISHED, id,
                    step ? run.goal().stepLabel(run.stepOfParent()) + "：" + run.result().summary() : run.result().summary(),
                    run.result().status(), selfBrief.current().orElse(null));
        }
    }

    private static String describe(GoalRun run) {
        String purpose = run.goal().purpose();
        return purpose == null || purpose.isBlank()
                ? run.goal().ability()
                : run.goal().ability() + "（" + purpose + "）";
    }
}
