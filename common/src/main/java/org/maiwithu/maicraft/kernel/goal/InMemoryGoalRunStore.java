// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存里的目标运行存储：只存一份引用，进程结束就没了。离线测试用它走通
 * "下达 → 推进 → 结果 → 存盘 → 重启后恢复"这条链；真要跨重启，换成存储移植里的实现。
 */
public final class InMemoryGoalRunStore implements GoalRunStore {
    private long nextId;
    private final Map<Long, GoalRun> runs = new LinkedHashMap<>();

    @Override public long nextId() {
        return ++nextId;
    }

    @Override public void save(GoalRun run) {
        runs.put(run.id(), run);
    }

    @Override public List<GoalRun> unfinished() {
        List<GoalRun> found = new ArrayList<>();
        for (GoalRun run : runs.values()) {
            if (run.unfinished()) {
                found.add(run);
            }
        }
        return List.copyOf(found);
    }
}
