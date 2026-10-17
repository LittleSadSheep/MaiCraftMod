// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.child;

import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 子任务替身：按脚本逐刻返回推进情况，脚本用完后一直重复最后一个；记录被启动、暂停、收尾的次数与原因。
 * 可指明此刻能不能被打断、收尾时交代什么结果、启动或推进时抛异常，用来测子任务运行器的各种岔路。
 */
final class ScriptedChild implements Task {
    final List<TickResult> script = new ArrayList<>();
    int index;
    Interruptibility interruptibility = Interruptibility.WORKING;
    RuntimeException startError;
    RuntimeException tickError;
    /** 收尾时交代的结果；为 null 表示收尾时什么也不交代，运行器得自己兜底。 */
    TaskResult closeResult;
    int starts;
    int pauses;
    int closes;
    CloseReason closedWith;

    ScriptedChild(TickResult... script) {
        this.script.addAll(List.of(script));
    }

    @Override public void start(TickContext context) {
        starts++;
        if (startError != null) throw startError;
    }

    @Override public TickResult tick(TickContext context) {
        if (tickError != null) throw tickError;
        TickResult tickResult = script.get(Math.min(index, script.size() - 1));
        index++;
        return tickResult;
    }

    @Override public void pause() {
        pauses++;
    }

    @Override public TaskResult close(CloseReason reason) {
        closes++;
        closedWith = reason;
        return closeResult;
    }

    @Override public Interruptibility interruptibility(TickContext context) {
        return interruptibility;
    }

    @Override public String describe() {
        return "替身子任务";
    }

    static TickResult running() {
        return TickResult.RUNNING;
    }

    static TickResult finishedDone(String summary) {
        return TickResult.finished(TaskResult.done(summary));
    }
}
