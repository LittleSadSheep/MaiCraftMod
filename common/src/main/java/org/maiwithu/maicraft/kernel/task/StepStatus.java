// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.maiwithu.maicraft.kernel.outcome.Blocker;

import java.util.Objects;

/** 子步骤推进一刻后的状态：还在做（本刻是否有真实进展）、做完了、或失败（附卡点）。 */
public sealed interface StepStatus {

    /** 还在做，本刻没有可报告的进展（例如在等原生确认）。 */
    static StepStatus running() {
        return Running.IDLE;
    }

    /** 还在做，本刻有真实进展（例如离目标更近、确认放下了一格）。 */
    static StepStatus progressed() {
        return Running.PROGRESSED;
    }

    static StepStatus done() {
        return Done.INSTANCE;
    }

    static StepStatus failed(Blocker blocker) {
        return new Failed(blocker);
    }

    /** 还在做；{@code progressed} 表示本刻是否有真实进展。 */
    record Running(boolean progressed) implements StepStatus {
        static final Running IDLE = new Running(false);
        static final Running PROGRESSED = new Running(true);
    }

    /** 做完了。 */
    record Done() implements StepStatus {
        static final Done INSTANCE = new Done();
    }

    /** 失败，卡点见 blocker。 */
    record Failed(Blocker blocker) implements StepStatus {
        public Failed {
            Objects.requireNonNull(blocker, "blocker");
        }
    }
}
