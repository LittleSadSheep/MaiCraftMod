// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 几步按顺序做的一个动作：上一步做完才建下一步（下一步要按那一刻的现场建，例如点完第一根轴再看手里的东西），
 * 哪一步失败整个动作就按它的问题失败，后面的不做。装传送带"拿传送带 → 点第一根轴 → 点第二根轴"就是这样。
 */
final class StepsInOrder implements Action {

    /** 一步：给描述用的名字，和到时候才建的动作；建出 null 表示这一步此刻不用做。 */
    record Step(String what, Supplier<Action> build) {
        Step {
            Objects.requireNonNull(what, "what");
            Objects.requireNonNull(build, "build");
        }
    }

    private final String what;
    private final Deque<Step> left;
    private Step step;
    private Action current;

    StepsInOrder(String what, List<Step> steps) {
        this.what = Objects.requireNonNull(what, "what");
        this.left = new ArrayDeque<>(steps);
    }

    @Override public ActionStatus tick(TickContext context) {
        while (current == null) {
            step = left.poll();
            if (step == null) return ActionStatus.done();
            current = step.build().get();
        }
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Done) {
            current.close();
            current = null;
            return left.isEmpty() ? ActionStatus.done() : ActionStatus.progressed();
        }
        return status;
    }

    @Override public void pause() {
        if (current != null) current.pause();
    }

    @Override public void close() {
        if (current != null) current.close();
        current = null;
    }

    @Override public Interruptibility interruptibility() {
        return current == null ? Interruptibility.WORKING : current.interruptibility();
    }

    @Override public String describe() {
        return step == null ? what : what + "：" + step.what();
    }
}
