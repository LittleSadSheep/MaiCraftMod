// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import java.util.Locale;

/**
 * MCP 工具调用失败的种类：小而稳定，LLM 和宿主按它决定怎么改请求再试。
 *
 * <p>这是"工具调用失败"，不是"任务失败"：目标已经下达、做着做着没做成，走任务结果，不走这里。
 */
public enum ErrorCode {
    /** 参数不对：缺了、类型不对、取值超出范围、不认识的字段；fields 里逐条写明。 */
    INVALID_PARAMETER,
    /** 没有这个能力；message 里附上相近的能力名。 */
    UNKNOWN_ABILITY,
    /** 编号不存在：任务编号、计划编号、资料地址，或者结束太久已经不保留。 */
    UNKNOWN_ID,
    /** 角色不在世界里（在主菜单、正在进入世界、已断线），这时不能看也不能做。 */
    NOT_IN_WORLD,
    /** 游戏这会儿腾不出手处理这次请求（客户端线程迟迟没轮到它），稍后再试。 */
    BUSY,
    /** 程序出错，不是请求写错了，也不是游戏里发生了什么。 */
    INTERNAL_ERROR;

    /** 返回给调用方的写法，例如 {@code invalid_parameter}。 */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
