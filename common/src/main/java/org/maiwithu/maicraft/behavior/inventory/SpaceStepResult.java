// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.result.Change;

/**
 * 腾挪一步这一刻的结局：做成了、还在做、做不了，三者必须分开。
 *
 * <p>"还在做"（界面正开着、点击还在等确认）和"做不了"（容器不在了、随身背包满了）是两回事：
 * 还在做时腾地方要等它，不能转头去做下一步；不然存箱子还没确认，背包里的东西已经被丢到地上了。
 */
public sealed interface SpaceStepResult {

    /** 还在做：下一刻接着看，这一刻不再做别的腾挪。 */
    SpaceStepResult WORKING = new Working();

    /** 这一步做成了，并且游戏确认了；变化记进结果。 */
    record Done(Change change) implements SpaceStepResult {
        public Done {
            Objects.requireNonNull(change, "change");
        }
    }

    /** 还在做。 */
    record Working() implements SpaceStepResult {}

    /** 这一步做不了，原因写明；腾地方换计划里的下一步。 */
    record CannotDo(String why) implements SpaceStepResult {}

    static SpaceStepResult done(Change change) {
        return new Done(change);
    }

    static SpaceStepResult cannotDo(String why) {
        return new CannotDo(why);
    }
}
