// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.maiwithu.maicraft.kernel.task.TickContext;

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
}
