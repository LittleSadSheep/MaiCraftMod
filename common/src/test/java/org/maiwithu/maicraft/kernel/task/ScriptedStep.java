// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import java.util.ArrayList;
import java.util.List;

/**
 * 子步骤替身：按脚本逐刻返回状态，脚本用完后一直重复最后一个状态；记录被暂停、被关闭了几次。
 * 用它测试执行器的阶段走向，不需要启动游戏。
 */
final class ScriptedStep implements Step {
    private final String name;
    private final List<StepStatus> script;
    private int index;
    int pauses;
    int closes;

    ScriptedStep(String name, StepStatus... script) {
        this.name = name;
        this.script = new ArrayList<>(List.of(script));
    }

    @Override public StepStatus tick(TickContext context) {
        StepStatus status = script.get(Math.min(index, script.size() - 1));
        index++;
        return status;
    }

    @Override public void pause() {
        pauses++;
    }

    @Override public void close() {
        closes++;
    }

    @Override public String describe() {
        return name;
    }
}
