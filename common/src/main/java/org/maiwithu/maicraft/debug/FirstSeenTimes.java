// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.maiwithu.maicraft.kernel.event.TaskEvent;

/**
 * 面板第一次看到每个状态、每条事件的现实时刻。"在等回答 40s""1m 前"、事件的时:分:秒都从这里算。
 *
 * <p>现状快照每刻都交进来，面板关着也交，所以第一次看到的时刻就是它出现的时刻，误差不超过一刻。
 * 用现实时间是因为 LLM 和宿主的等待按现实时间走，单人游戏按 ESC 暂停时也照实累计。
 * 这一刻快照里已经没有的东西随即忘掉，不越攒越多。
 */
public final class FirstSeenTimes {
    /** 角色这一轮死亡（等重生、挂出死亡恢复的问题）开始的那一刻。 */
    static final String DEATH = "death";

    private final Map<String, Long> firstSeen = new HashMap<>();

    /** 记下这一刻快照里出现的东西：第一次见的记下此刻，见过的保留原来的时刻，已经没有的忘掉。 */
    public void observe(StatusSnapshot snapshot, long nowMillis) {
        Set<String> present = new HashSet<>();
        StatusSnapshot.Goals goals = snapshot.goals();
        if (goals.main() != null) present.add(goalState(goals.main()));
        for (StatusSnapshot.GoalLine goal : goals.recent()) present.add(goalState(goal));
        if (goals.deathQuestion() != null || snapshot.loop().state() == StatusSnapshot.LoopState.WAITING_RESPAWN) {
            present.add(DEATH);
        }
        for (TaskEvent event : snapshot.events()) present.add(event(event));
        for (String key : present) firstSeen.putIfAbsent(key, nowMillis);
        firstSeen.keySet().retainAll(present);
    }

    /** 第一次看到它的现实时刻；没见过时就当是此刻。 */
    public long seenAt(String key, long nowMillis) {
        return firstSeen.getOrDefault(key, nowMillis);
    }

    /** 目标进入当前处境（进行中、在等回答、已暂停、已结束）的那一刻。 */
    static String goalState(StatusSnapshot.GoalLine goal) {
        return "goal:" + goal.id() + ":" + goal.state();
    }

    /** 一条任务事件出现的那一刻；换世界后序号会从头数，所以连种类与消息一起认。 */
    static String event(TaskEvent event) {
        return "event:" + event.cursor() + ":" + event.kind() + ":" + event.message().hashCode();
    }
}
