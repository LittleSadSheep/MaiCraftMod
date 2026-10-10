// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.quest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.maiwithu.maicraft.ability.quest.spi.QuestBookOperations;
import org.maiwithu.maicraft.ability.quest.spi.QuestView;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 任务书动作任务：等本刻的交互机会，发一次原生请求，然后逐刻看任务书同步与背包如实结算。
 *
 * <p>FTB 对提交、勾选、领取都没有专门的回应，成没成只能从两处看：任务书里这一项的进度与领取状态，
 * 和背包里的东西少了（交上去了）或多出来了（领到手了）。所以发出之后绝不补发：
 * 最后一次变化后再等一小会儿没有新变化就收尾，发出后一段时间什么都没变就按"没能确认"如实交代。
 */
final class QuestTask extends PhasedTask<QuestTask.Phase> {

    /** 先等本刻的交互机会把请求发出去，再逐刻看反应。 */
    enum Phase { SEND, OBSERVE }

    /** 一次变化安静多久算告一段落：FTB 的进度同步与背包结算不在同一刻到。F 游戏事实；初值 250 毫秒，实机不合适再调。 */
    private static final long QUIET_TICKS_AFTER_CHANGE = 5;

    /** 发出后多久一点动静都没有就收尾：FTB 没有专门的回应，这只是等待窗口。F 游戏事实；初值 3 秒，实机不合适再调。 */
    private static final long WINDOW_TICKS_AFTER_SEND = 60;

    // 卡住判定比最长的等待窗口长：等不到反应由本任务自己按没能确认收场，轮不到卡住判定。
    private static final long STUCK_AFTER_TICKS = WINDOW_TICKS_AFTER_SEND + QUIET_TICKS_AFTER_CHANGE + 40;

    private final QuestInput input;
    private final QuestBookOperations book;

    /** 请求发出去没有：发出后到收尾之间停下不安全，也不能再发第二次。 */
    private boolean sent;
    private long sentTick;
    /** 最后一次看到变化的刻；还没见过变化时为空。 */
    private Long lastChangeTick;
    /** 背包有没有进出：只有背包动了而任务书没动，是同步晚到，不能冒充做成。 */
    private boolean itemsMoved;
    /** 发出前的条目与背包：结算时对照"动没动"用它，一直不更新。 */
    private Optional<QuestView> baselineView = Optional.empty();
    private Map<String, Integer> baselineCounts = Map.of();
    /** 上一刻读到的条目：判断"有没有新变化"对照的是它，不然安静期永远等不到。 */
    private Optional<QuestView> previousView = Optional.empty();
    /** 最新一次读到的条目：结算与结果细节用它。 */
    private Optional<QuestView> latestView = Optional.empty();

    QuestTask(QuestInput input, QuestBookOperations book) {
        super("任务书" + input.operation().chinese(), Phase.SEND,
                new ProgressTracker(STUCK_AFTER_TICKS, Long.MAX_VALUE));
        this.input = input;
        this.book = book;
    }

    @Override protected Action enter(Phase phase) {
        // 等机会与看反应都是一刻内的小判断，不需要动作。
        return null;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case SEND -> send(context);
            case OBSERVE -> observe(context);
        };
    }

    /** 等到本刻的交互机会，记下发出前的现场，发一次请求；发出后绝不在这条任务里再发。 */
    private Next<Phase> send(TickContext context) {
        PlayerContext player = context.player();
        if (player == null) {
            return Next.fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "角色不在世界里，任务书请求发不出去"));
        }
        if (!player.canInteractThisTick()) return Next.stay();
        // 发出前记下这一刻的样子：结算时"动没动"对照它，逐刻的新变化对照上一刻。
        baselineView = book.quest(input.questId());
        baselineCounts = snapshot(player.backpack());
        previousView = baselineView;
        latestView = baselineView;
        sentTick = context.gameTick();
        sent = switch (input.operation()) {
            case SUBMIT -> book.submit(input.questId(), input.requirementId());
            case CONFIRM -> book.confirm(input.questId(), input.requirementId());
            case CLAIM -> book.claim(input.questId(), input.rewardId(), input.choiceId());
        };
        if (!sent) {
            return Next.fail(Problem.of(Problem.Kind.UNSUPPORTED,
                    "请求没有发出去：任务书联动现在用不了（停用或条目不在了）"));
        }
        recordProgress("已发出" + input.operation().chinese() + "请求");
        return Next.go(Phase.OBSERVE, "已发出，看任务书与背包有没有反应");
    }

    /** 每刻对照一次任务书与背包；变化停了或一直没动静就按规矩收尾。 */
    private Next<Phase> observe(TickContext context) {
        long now = context.gameTick();
        latestView = book.quest(input.questId());
        if (bookNews(latestView)) lastChangeTick = now;
        previousView = latestView;
        // 角色这刻不在了就不看背包：读不到不能当成"背包空了"，把东西都记成没了。
        if (context.player() != null && recordItemDiffs(snapshot(context.player().backpack()))) {
            lastChangeTick = now;
        }
        if (lastChangeTick != null && now - lastChangeTick >= QUIET_TICKS_AFTER_CHANGE) {
            return Next.done(settle());
        }
        if (lastChangeTick == null && now - sentTick >= WINDOW_TICKS_AFTER_SEND) {
            recordUnconfirmed(new Change(Change.Kind.OTHER, input.describe(), 1,
                    "已发出" + input.operation().chinese() + "请求，任务书与背包都没有反应，做没做成没能确认"));
            return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL,
                            "已发出" + input.operation().chinese() + "请求，任务书没有反应，做没做成没能确认；不重发")
                    .build());
        }
        return Next.stay();
    }

    /** 条目这一刻比上一刻有新变化：完整对照，要求与奖励动了都算。 */
    private boolean bookNews(Optional<QuestView> current) {
        if (previousView.isEmpty()) return current.isPresent();
        if (current.isEmpty()) return false;
        return !current.get().equals(previousView.get());
    }

    /** 结算时对照发出前那份：这次动作要的那一栏动没动。提交与勾选看要求，领奖看领取状态。 */
    private boolean bookMoved(Optional<QuestView> current) {
        if (baselineView.isEmpty()) return current.isPresent();
        if (current.isEmpty()) return false;
        QuestView before = baselineView.get();
        QuestView after = current.get();
        return switch (input.operation()) {
            case SUBMIT, CONFIRM -> !before.requirements().equals(after.requirements());
            case CLAIM -> !before.rewards().equals(after.rewards());
        };
    }

    /** 背包与对照的差记成变化：少了是交上去的，多了是领到的；记过之后对照换成这一刻。 */
    private boolean recordItemDiffs(Map<String, Integer> counts) {
        if (counts.equals(baselineCounts)) return false;
        Map<String, Integer> merged = new TreeMap<>(baselineCounts);
        counts.forEach((itemId, count) -> merged.merge(itemId, count, Integer::sum));
        for (Map.Entry<String, Integer> entry : merged.entrySet()) {
            String itemId = entry.getKey();
            int before = baselineCounts.getOrDefault(itemId, 0);
            int after = counts.getOrDefault(itemId, 0);
            int diff = after - before;
            if (diff < 0) {
                recordChange(new Change(Change.Kind.ITEM_CONSUMED, itemId, -diff,
                        input.operation() == QuestInput.Operation.SUBMIT ? "交给了任务书" : "从背包少了"));
            } else if (diff > 0) {
                recordChange(new Change(Change.Kind.ITEM_GAINED, itemId, diff, "进了背包"));
            }
        }
        baselineCounts = counts;
        itemsMoved = true;
        return true;
    }

    /** 变化停了之后的结算：任务书动了按这次动作的完成标准收；只有背包动了是同步晚到，如实说还没确认。 */
    private TaskResult settle() {
        String title = latestView.map(QuestView::title).orElse(input.questId());
        boolean bookReachedGoal = latestView.isPresent() && bookMoved(latestView);
        if (bookReachedGoal) {
            return switch (input.operation()) {
                case SUBMIT -> TaskResult.done("交上了：「" + title + "」的要求在任务书里有进展"
                        + (completedNow() ? "，已经完成" : ""));
                case CONFIRM -> TaskResult.done("勾选了：「" + title + "」的要求完成了");
                case CLAIM -> TaskResult.done("领了：「" + title + "」的奖励，任务书里领取状态变了");
            };
        }
        if (itemsMoved) {
            return TaskResult.builder(TaskResult.Status.PARTIAL,
                            "背包有进出，但任务书里这一项还没动静：进度或领取状态还没同步过来")
                    .remaining("等任务书同步；要确认就等一会儿再看条目现在的样子")
                    .build();
        }
        return TaskResult.builder(TaskResult.Status.PARTIAL,
                        "看到了动静，但任务书里这一项现在读不到了，做没做成没能确认；不重发")
                .build();
    }

    /** 这次动的那条要求现在完成了没有；领奖与读不到条目时按没完成说。 */
    private boolean completedNow() {
        return latestView.flatMap(view -> view.requirements().stream()
                        .filter(requirement -> requirement.id().equalsIgnoreCase(input.requirementId())).findFirst())
                .map(QuestView.Requirement::completed).orElse(false);
    }

    /** 背包此刻的件数表：按物品 ID 归总；读不到背包时给空表（对照不出变化，不冒充读过）。 */
    private static Map<String, Integer> snapshot(BackpackView backpack) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (backpack == null) return counts;
        for (var stack : backpack.stacks()) {
            counts.merge(stack.itemId(), stack.count(), Integer::sum);
        }
        return counts;
    }

    @Override protected ResultDetails details() {
        String before = baselineView.map(QuestDetails::entry).orElse("发出前读不到条目");
        String after = latestView.map(QuestDetails::entry).orElse("现在读不到条目");
        return new QuestDetails(before + " → " + after, sent);
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case SEND -> "等本刻的交互机会";
            case OBSERVE -> "看任务书与背包的反应";
        };
    }

    // 请求已发出、正在等回音的这几秒里不能停：一次动作，停了也没法撤回，更不能补发。
    @Override public Interruptibility interruptibility(TickContext context) {
        return sent ? Interruptibility.UNSAFE_TO_STOP : super.interruptibility(context);
    }
}
