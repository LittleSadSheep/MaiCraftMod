// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.outcome;

/**
 * 能力特有的回执事实，例如睡觉的"床从哪来、是不是一觉睡到了天亮"。
 *
 * <p>每个能力用自己的 record 实现它，字段即回执里的事实；序列化成 JSON 是入口层的事。
 * 不用 {@code Map<String, Object>}：v1 的回执全靠字符串键通信，拼错一个键就悄悄丢事实。
 */
public interface Facts {

    /** 没有能力特有事实时使用。 */
    Facts NONE = NoFacts.INSTANCE;
}

/** 空事实的唯一实例。 */
enum NoFacts implements Facts {
    INSTANCE
}
