// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.ability;

/** 能力怎样执行。由能力自己声明，入口与内核据此决定是否要接管身体，不再在各处写能力名单。 */
public enum ExecutionMode {
    /** 要用身体在世界里动手：进入任务槽，申请接管身体。 */
    BODY,
    /** 只读的分析或设计：当场返回报告，不占身体，也不产生后台任务。 */
    READ_ONLY,
    /** 只改伙伴自己的记忆（例如记住一个地点）：当场完成，不占身体。 */
    LOCAL_MEMORY
}
