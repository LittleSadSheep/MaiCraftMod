// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 按顺序做的几步动作：先把前一步做完再做下一步，一步做砸了整串就停在那里。
 * 来源的执行都是一串：备齐原料 → 走到设施前 → 动手做；引擎逐刻推进这一串。
 */
public final class StepwiseActions implements Action {

    private final String description;
    private final Action[] steps;
    private int current;
    private ActionStatus lastFailure;

    /** @param description 给日志的一句话，例如"备齐原料后去工作台合成"；steps 至少一步。 */
    public StepwiseActions(String description, Action... steps) {
        if (steps.length == 0) throw new IllegalArgumentException("一串动作至少要有一步：" + description);
        this.description = description;
        this.steps = steps;
    }

    /** 做砸了的那一步留下的失败；整串还没做完或做成了时为 null。 */
    public ActionStatus lastFailure() {
        return lastFailure;
    }

    @Override public ActionStatus tick(TickContext context) {
        while (current < steps.length) {
            ActionStatus status = steps[current].tick(context);
            if (status instanceof ActionStatus.Running running) {
                return running.progressed() ? ActionStatus.progressed() : ActionStatus.running();
            }
            if (status instanceof ActionStatus.Failed failed) {
                lastFailure = failed;
                return failed;
            }
            // 这一步做成了：换下一步。同刻还有富余就继续推进，不空等一刻。
            current++;
        }
        return ActionStatus.done();
    }

    @Override public void pause() {
        if (current < steps.length) steps[current].pause();
    }

    @Override public void close() {
        for (Action step : steps) step.close();
    }

    @Override public Interruptibility interruptibility() {
        return current < steps.length ? steps[current].interruptibility() : Interruptibility.BETWEEN_ACTIONS;
    }

    @Override public String describe() {
        return current < steps.length
                ? description + "（第 " + (current + 1) + "/" + steps.length + " 步：" + steps[current].describe() + "）"
                : description;
    }
}
