// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.maiwithu.maicraft.kernel.result.Problem;

import java.util.Objects;

/** 动作推进一刻后的状态：还在做（本刻有没有真实进展）、做完了、或失败（附问题）。 */
public sealed interface ActionStatus {

    /** 还在做，本刻没有可报告的进展（例如在等游戏确认）。 */
    static ActionStatus running() {
        return Running.NO_PROGRESS;
    }

    /** 还在做，本刻有真实进展（例如离目标更近了、确认放下了一格）。 */
    static ActionStatus progressed() {
        return Running.PROGRESSED;
    }

    static ActionStatus done() {
        return Done.INSTANCE;
    }

    static ActionStatus failed(Problem problem) {
        return new Failed(problem);
    }

    /** 还在做；{@code progressed} 表示本刻有没有真实进展。 */
    record Running(boolean progressed) implements ActionStatus {
        static final Running NO_PROGRESS = new Running(false);
        static final Running PROGRESSED = new Running(true);
    }

    /** 做完了。 */
    record Done() implements ActionStatus {
        static final Done INSTANCE = new Done();
    }

    /** 失败，原因见 problem。 */
    record Failed(Problem problem) implements ActionStatus {
        public Failed {
            Objects.requireNonNull(problem, "problem");
        }
    }
}
