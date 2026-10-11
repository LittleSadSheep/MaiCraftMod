// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.spi;

import java.util.Objects;

import com.google.gson.JsonObject;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 观察里的一样模组的东西。
 *
 * @param category 种类，observe 按它分组列：elevator、station、train、structure……
 * @param identity 按什么领观察编号：方块（b#，按位置）、实体（e#，按实体编号）、物理结构（s#，按结构自己的身份）
 * @param key      身份：实体编号或结构身份；方块不用（按 position）
 * @param position 此刻在哪（结构给中心）
 * @param summary  场景里列的那几项（当前层名、车名、动没动……）
 * @param detail   observe(detail) 时给全的（全部楼层、座位……）；没有更多可给时为空对象
 */
public record ModThing(String category, Identity identity, String key, WorldPosition position, JsonObject summary,
                       JsonObject detail) {

    /** 按什么领观察编号。 */
    public enum Identity { BLOCK, ENTITY, STRUCTURE }

    public ModThing {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(identity, "identity");
        key = key == null ? "" : key;
        Objects.requireNonNull(position, "position");
        summary = summary == null ? new JsonObject() : summary.deepCopy();
        detail = detail == null ? new JsonObject() : detail.deepCopy();
    }

    @Override public JsonObject summary() {
        return summary.deepCopy();
    }

    @Override public JsonObject detail() {
        return detail.deepCopy();
    }
}
