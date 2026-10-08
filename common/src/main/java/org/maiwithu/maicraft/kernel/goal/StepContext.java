// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.maiwithu.maicraft.kernel.task.TickContext;

import java.util.List;

/**
 * 能力决定当前步骤怎么做时能看到的东西：这一步的目标、它是第几步、以及本刻的角色。
 * 只读；做决定必须便宜、没有副作用，因为每刻都可能被重新调用。
 */
public interface StepContext {

    /** 当前步骤的目标。 */
    Goal goal();

    /** 当前是第几步（从 0 开始）。 */
    int stepIndex();

    /** 本刻的上下文。 */
    TickContext tick();

    /**
     * 这次推进里 LLM 对自己被问过的问题已经给出的回答，按先后顺序。
     * 能力重新决定时用它处理自己问过的事；没问过就是空。
     */
    default List<String> answers() {
        return List.of();
    }
}
