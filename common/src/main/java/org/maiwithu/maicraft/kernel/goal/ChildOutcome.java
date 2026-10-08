// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.maiwithu.maicraft.kernel.outcome.Outcome;

import java.util.Objects;

/** 能力钩子对一个子任务结果的处理：照常接受（可以补充事实），或给出替代动作。 */
public sealed interface ChildOutcome {

    static ChildOutcome accept(Outcome outcome) {
        return new Accept(outcome);
    }

    static ChildOutcome instead(IntentAction action) {
        return new Instead(action);
    }

    /** 照常接受这个结果。 */
    record Accept(Outcome outcome) implements ChildOutcome {
        public Accept {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /** 不直接上报这个结果，改做另一个动作。 */
    record Instead(IntentAction action) implements ChildOutcome {
        public Instead {
            Objects.requireNonNull(action, "action");
        }
    }
}
