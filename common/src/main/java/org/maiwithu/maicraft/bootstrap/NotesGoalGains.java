// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.behavior.acquire.ItemsInUse;
import org.maiwithu.maicraft.kernel.goal.GoalRun;
import org.maiwithu.maicraft.kernel.goal.GoalRunState;
import org.maiwithu.maicraft.kernel.goal.GoalRunStore;

/**
 * 目标结束时记下它拿到了什么：下一个目标路上垫脚不先动这些（挖来做石镐的圆石，爬上来时不垫下去）。
 * 其余原样交给真正的存储；sequence 的每一步结束也算一个目标结束。
 */
final class NotesGoalGains implements GoalRunStore {

    private final GoalRunStore delegate;
    private final ItemsInUse inUse;

    NotesGoalGains(GoalRunStore delegate, ItemsInUse inUse) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.inUse = Objects.requireNonNull(inUse, "inUse");
    }

    @Override public long nextId() {
        return delegate.nextId();
    }

    @Override public void save(GoalRun run) {
        delegate.save(run);
        if (run.state() == GoalRunState.FINISHED && run.result() != null) {
            inUse.goalFinished(run.result().changes());
        }
    }

    @Override public List<GoalRun> unfinished() {
        return delegate.unfinished();
    }
}
