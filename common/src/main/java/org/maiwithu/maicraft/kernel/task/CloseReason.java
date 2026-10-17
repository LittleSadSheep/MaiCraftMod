// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

/** 任务为什么结束；任务收尾时据此写结果。 */
public enum CloseReason {
    /** 任务自己走到了结局（完成、部分完成或失败）。 */
    FINISHED,
    /** 被新的任务替换，例如 LLM 派了新活。 */
    REPLACED,
    /** 被 LLM 或玩家取消。 */
    CANCELLED,
    /** 角色没了：死亡、断线、换世界。 */
    PLAYER_GONE
}
