// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.ability;

import org.maiwithu.maicraft.kernel.goal.IntentAction;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepHooks;
import org.maiwithu.maicraft.kernel.task.ExecutorRegistry;

/**
 * 能力模块：一个能力的全部知识都从这里进入；内核和入口只通过它认识能力（docs/design/02 第 5.1 节）。
 *
 * <p>每个能力包只有这一个类对外公开（{@code public}），其余类都包内可见。
 * 启动装配用显式清单登记模块，新增能力时在清单里加一行。
 */
public interface AbilityModule {

    /** 关于这个能力的静态知识：ID、用途、契约、参数、目标种类、执行方式、可用前提。 */
    AbilityDescriptor descriptor();

    /**
     * 翻译：看现场，决定当前步骤的下一个动作。
     * 必须便宜、没有副作用，每刻都可能被重复调用；信息不全时返回 {@link IntentAction#PENDING}。
     */
    IntentAction plan(StepContext step);

    /** 登记本能力的任务单与执行器。 */
    default void executors(ExecutorRegistry registry) {}

    /** 本能力对内核扩展点的实现；绝大多数能力用不到。 */
    default StepHooks hooks() {
        return StepHooks.NONE;
    }
}
