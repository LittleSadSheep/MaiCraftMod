// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

/** 任务为什么结束；执行器据此决定收尾时怎样写回执。 */
public enum CloseReason {
    /** 执行器自己走到了结局（完成、部分完成或失败）。 */
    FINISHED,
    /** 被新的任务替换，例如 LLM 派了新活。 */
    REPLACED,
    /** 被操作者取消。 */
    CANCELLED,
    /** 身体没了：死亡、断线、换世界。 */
    BODY_GONE
}
