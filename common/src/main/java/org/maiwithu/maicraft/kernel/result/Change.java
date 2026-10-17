// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.result;

import java.util.Objects;

/**
 * 一条已经在世界里发生的变化：拿到了什么、用掉了什么、放下或拆掉了哪一格、存进或丢掉了什么。
 *
 * <p>只记录确认过的事实；已提交但还没确认的交互记在结果的 {@code unconfirmed} 里，不能混进来。
 *
 * @param kind  变化种类
 * @param what  涉及的东西，通常是物品、方块或实体的注册 ID
 * @param count 数量；方块与移动类变化一般为 1
 * @param note  补充说明，例如"存进了家里的箱子"；可以为 null
 */
public record Change(Kind kind, String what, int count, String note) {

    /** 变化种类。 */
    public enum Kind {
        ITEM_GAINED,
        ITEM_CONSUMED,
        ITEM_STORED,
        ITEM_DROPPED,
        BLOCK_PLACED,
        BLOCK_BROKEN,
        /** 方块还是那一格，但状态或种类变了：门开了、锄成了耕地、火点着了。 */
        BLOCK_CHANGED,
        ENTITY_AFFECTED,
        MOVED,
        OTHER
    }

    public Change {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(what, "what");
        if (count < 0) throw new IllegalArgumentException("变化的数量不能为负：" + count);
    }

    public static Change of(Kind kind, String what, int count) {
        return new Change(kind, what, count, null);
    }
}
