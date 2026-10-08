// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.maiwithu.maicraft.kernel.outcome.Outcome;

/** 执行器推进一刻后的状态：还在做，或已经有了结局。 */
public sealed interface TaskStatus {

    /** 还在做，下一刻继续。 */
    TaskStatus RUNNING = new Running();

    /** 已有结局；内核随后会调用 {@link Task#close} 做收尾。 */
    static TaskStatus finished(Outcome outcome) {
        return new Finished(outcome);
    }

    /** 还在做。 */
    record Running() implements TaskStatus {}

    /** 已有结局。 */
    record Finished(Outcome outcome) implements TaskStatus {}
}
