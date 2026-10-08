// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.result;

/**
 * 能力特有的结果细节，例如睡觉的"床从哪来、是不是一觉睡到了天亮"。
 *
 * <p>每个能力用自己的 record 实现它，字段就是要告诉 LLM 的细节；转成 JSON 是 MCP 入口的事。
 * 不用 {@code Map<String, Object>}：靠字符串键传递细节，拼错一个键就会悄悄丢掉一条事实。
 */
public interface ResultDetails {

    /** 没有能力特有细节时使用。 */
    ResultDetails NONE = NoDetails.INSTANCE;
}

/** 空细节的唯一实例。 */
enum NoDetails implements ResultDetails {
    INSTANCE
}
