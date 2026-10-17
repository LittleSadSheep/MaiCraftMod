// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;

import java.util.Objects;

/** 分阶段任务每刻的走向：留在本阶段、换到另一个阶段、带着结果完成、或带着问题失败。 */
public sealed interface Next<P extends Enum<P>> {

    static <P extends Enum<P>> Next<P> stay() {
        return new Stay<>();
    }

    /** 换阶段，并写明原因；原因会进日志和任务事件，例如"到床边了"。 */
    static <P extends Enum<P>> Next<P> go(P phase, String why) {
        return new Go<>(phase, why);
    }

    /** 带着结果结束；基类会把运行中记下的变化与尝试补进去。 */
    static <P extends Enum<P>> Next<P> done(TaskResult result) {
        return new Done<>(result);
    }

    /** 以失败结束。 */
    static <P extends Enum<P>> Next<P> fail(Problem problem) {
        return new Fail<>(problem);
    }

    record Stay<P extends Enum<P>>() implements Next<P> {}

    record Go<P extends Enum<P>>(P phase, String why) implements Next<P> {
        public Go {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(why, "why");
        }
    }

    record Done<P extends Enum<P>>(TaskResult result) implements Next<P> {
        public Done {
            Objects.requireNonNull(result, "result");
        }
    }

    record Fail<P extends Enum<P>>(Problem problem) implements Next<P> {
        public Fail {
            Objects.requireNonNull(problem, "problem");
        }
    }
}
