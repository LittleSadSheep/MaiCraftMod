// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.ability;

/** 能力怎样执行。由能力自己声明，MCP 入口与内核据此决定要不要控制角色，不在各处另写能力名单。 */
public enum ExecutionMode {
    /** 要控制角色在世界里动手：成为角色当前的任务。 */
    CONTROLS_PLAYER,
    /** 只读的分析或设计：当场返回报告，不控制角色，也不产生后台任务。 */
    READ_ONLY,
    /** 只改角色自己的记忆（例如记住一个地点）：当场完成，不控制角色。 */
    MEMORY_ONLY
}
