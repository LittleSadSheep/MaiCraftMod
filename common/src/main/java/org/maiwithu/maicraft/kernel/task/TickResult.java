// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.maiwithu.maicraft.kernel.result.TaskResult;

/** 任务推进一刻后的情况：还在做，或已经有了结果。 */
public sealed interface TickResult {

    /** 还在做，下一刻继续。 */
    TickResult RUNNING = new Running();

    /** 已有结果；内核随后会调用 {@link Task#close} 做收尾。 */
    static TickResult finished(TaskResult result) {
        return new Finished(result);
    }

    /** 还在做。 */
    record Running() implements TickResult {}

    /** 已有结果。 */
    record Finished(TaskResult result) implements TickResult {}
}
