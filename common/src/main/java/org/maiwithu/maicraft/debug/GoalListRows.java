// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import java.util.ArrayList;
import java.util.List;
import org.maiwithu.maicraft.kernel.goal.GoalRunState;
import org.maiwithu.maicraft.kernel.result.TaskResult;

/**
 * 详细档的"最近的目标"页：这段时间都干了什么，每个顶层目标一行，最近的在上。
 *
 * <p>这一页是用来扫一眼的：编号、处境、能力、步骤、时间这些短字段先排，purpose 或结论放在最后，
 * 放不下的部分截断，完整的结果用 task(get) 看。sequence 里的步骤不单列，只写第几步。
 */
final class GoalListRows {
    /** 最多列几个目标。 */
    static final int MAX_GOALS = 12;

    private GoalListRows() {}

    static List<Row> rows(Moment moment) {
        List<StatusSnapshot.GoalLine> goals = moment.status().goals().recent();
        List<Row> rows = new ArrayList<>();
        String total = "最近的目标 · 共 " + goals.size() + " 个" + (goals.size() > MAX_GOALS ? "，列出最近 " + MAX_GOALS + " 个" : "");
        rows.add(Row.of(null, 1, total + " · F9+H 返回", PanelColor.GOOD));
        if (goals.isEmpty()) {
            rows.add(Row.of(null, 1, "还没有目标", PanelColor.MINOR));
            return rows;
        }
        for (StatusSnapshot.GoalLine goal : goals.subList(0, Math.min(MAX_GOALS, goals.size()))) {
            rows.add(Row.of(null, 1, line(moment, goal), color(goal)));
        }
        return rows;
    }

    private static String line(Moment moment, StatusSnapshot.GoalLine goal) {
        StringBuilder text = new StringBuilder("#" + goal.id() + " " + PanelWords.goalState(goal.state(), goal.result())
                + " · " + PanelWords.ability(goal.ability()));
        if (goal.steps() > 0) text.append(" · 第 ").append(goal.step() + 1).append('/').append(goal.steps()).append(" 步");
        if (goal.state() == GoalRunState.FINISHED) {
            if (goal.startedTick() >= 0 && goal.finishedTick() >= 0) {
                text.append(" · 用时 ").append(PanelWords.ticks(goal.finishedTick() - goal.startedTick()));
            }
            text.append(" · ").append(PanelWords.duration(moment.ageMillis(FirstSeenTimes.goalState(goal)))).append(" 前");
            TaskResult result = goal.result();
            if (result != null) {
                text.append(" · ").append(result.status() == TaskResult.Status.FAILED ? StatusSentences.why(result)
                        : result.summary());
            }
        } else {
            if (goal.startedTick() >= 0) {
                text.append(" · ").append(PanelWords.ticks(moment.status().gameTick() - goal.startedTick()));
            }
            String purpose = StatusSentences.purpose(goal);
            if (!purpose.isBlank()) text.append(" · ").append(purpose);
        }
        return text.toString();
    }

    // 颜色：进行中青，在等回答、暂停与部分失败黄，失败红，完成绿，取消灰。
    private static PanelColor color(StatusSnapshot.GoalLine goal) {
        return switch (goal.state()) {
            case RUNNING -> PanelColor.DOING;
            case AWAITING_ANSWER, PAUSED -> PanelColor.WAITING;
            case FINISHED -> goal.result() == null ? PanelColor.MINOR : FullRows.resultColor(goal);
        };
    }
}
