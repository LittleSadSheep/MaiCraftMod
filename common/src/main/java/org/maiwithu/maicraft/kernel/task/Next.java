// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.maiwithu.maicraft.kernel.outcome.Blocker;
import org.maiwithu.maicraft.kernel.outcome.Outcome;

import java.util.Objects;

/** 阶段式执行器每刻的走向：留在本阶段、换到另一个阶段、带着回执完成、或带着卡点失败。 */
public sealed interface Next<P extends Enum<P>> {

    static <P extends Enum<P>> Next<P> stay() {
        return new Stay<>();
    }

    /** 换阶段，并写明原因；原因会进日志和 Attention 进度事件，例如"到床边了"。 */
    static <P extends Enum<P>> Next<P> go(P phase, String why) {
        return new Go<>(phase, why);
    }

    /** 带着回执结束；基类会把执行过程中累积的效果与尝试补进去。 */
    static <P extends Enum<P>> Next<P> done(Outcome outcome) {
        return new Done<>(outcome);
    }

    /** 以失败结束。 */
    static <P extends Enum<P>> Next<P> fail(Blocker blocker) {
        return new Fail<>(blocker);
    }

    record Stay<P extends Enum<P>>() implements Next<P> {}

    record Go<P extends Enum<P>>(P phase, String why) implements Next<P> {
        public Go {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(why, "why");
        }
    }

    record Done<P extends Enum<P>>(Outcome outcome) implements Next<P> {
        public Done {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    record Fail<P extends Enum<P>>(Blocker blocker) implements Next<P> {
        public Fail {
            Objects.requireNonNull(blocker, "blocker");
        }
    }
}
