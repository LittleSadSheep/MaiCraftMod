// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.outcome;

import java.util.Objects;

/**
 * 一条已经在世界里发生的效果：拿到了什么、用掉了什么、放下或拆掉了哪一格、存进或丢掉了什么。
 *
 * <p>只记录确认过的事实；已提交但还没确认的操作记在回执的 {@code uncertain} 里，不能混进来。
 *
 * @param kind    效果种类
 * @param subject 涉及的东西，通常是物品、方块或实体的注册 ID
 * @param count   数量；方块与移动类效果一般为 1
 * @param detail  补充说明，例如"存进了家里的箱子"；可以为 null
 */
public record Effect(Kind kind, String subject, int count, String detail) {

    /** 效果种类。 */
    public enum Kind {
        ITEM_GAINED,
        ITEM_CONSUMED,
        ITEM_STORED,
        ITEM_DROPPED,
        BLOCK_PLACED,
        BLOCK_BROKEN,
        ENTITY_AFFECTED,
        MOVED,
        OTHER
    }

    public Effect {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subject, "subject");
        if (count < 0) throw new IllegalArgumentException("效果数量不能为负：" + count);
    }

    public static Effect of(Kind kind, String subject, int count) {
        return new Effect(kind, subject, count, null);
    }
}
