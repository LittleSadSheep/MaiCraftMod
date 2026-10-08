// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.ability;

import org.maiwithu.maicraft.kernel.goal.AbilityHooks;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 能力模块：一个能力的全部知识都从这里进入；内核和 MCP 入口只通过它认识能力。
 *
 * <p>每个能力包只有这一个类对外公开（{@code public}），其余类都只在包内可见。
 * 启动时用一份明确的清单登记模块，新增能力时在清单里加一行。
 */
public interface AbilityModule {

    /** 这个能力的规格：ID、用途、能力说明、参数、目标对象种类、执行方式、需要的模组。 */
    AbilitySpec spec();

    /**
     * 看现场，决定当前这一步下一件做什么。
     * 必须便宜、没有副作用，每刻都可能被重复调用；信息不全时返回 {@link StepDecision#NOT_READY}。
     */
    StepDecision decide(StepContext step);

    /** 登记本能力的任务输入类型和对应的任务。 */
    default void registerTasks(TaskFactories factories) {}

    /** 本能力的特殊处理；绝大多数能力用不到。 */
    default AbilityHooks hooks() {
        return AbilityHooks.NONE;
    }

    /**
     * 目标里列的 steps 是否交给这个能力：只有"按顺序做几件事"的能力返回 true，
     * 目标推进器会把每一步当作一个完整的目标逐步跑。其他能力收到 steps 时，MCP 入口直接报参数错误。
     */
    default boolean acceptsSteps() {
        return false;
    }
}
