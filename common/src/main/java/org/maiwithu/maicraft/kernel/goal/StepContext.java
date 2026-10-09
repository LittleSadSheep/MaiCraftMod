// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;

import java.util.List;

/**
 * 能力决定当前步骤怎么做时能看到的东西：这一步的目标、它是第几步、本刻的角色、LLM 的回答与已经结束的任务结果。
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

    /**
     * 这个目标里已经结束的任务的结果，按结束先后。能力据此判断上一个任务做成没有、
     * 失败了换什么办法；结束目标时内核会把这些结果里已经发生的事实并进最终结果，能力不用自己抄。
     */
    default List<TaskResult> taskResults() {
        return List.of();
    }
}
