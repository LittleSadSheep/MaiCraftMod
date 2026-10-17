// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Objects;
import java.util.Set;

/**
 * 想要的东西：一种具体的物品，或一个标签下的任一物品。
 * 写法沿用游戏里的注册 ID：{@code minecraft:oak_log} 是具体物品，{@code #minecraft:logs} 是标签。
 * 要不要某个具体物品，问它自己：给一个物品 ID 和它挂着的标签，这里只做匹配。
 */
public record WantedItem(String specifier) {

    /** 标签写法的开头，与游戏里 "/give @s #minecraft:logs" 的习惯一致。 */
    private static final String TAG_PREFIX = "#";

    public WantedItem {
        if (specifier == null || specifier.isBlank()) {
            throw new IllegalArgumentException("想要的东西不能为空");
        }
    }

    /** 一种具体的物品，例如 minecraft:coal。 */
    public static WantedItem ofItem(String itemId) {
        if (itemId.startsWith(TAG_PREFIX)) {
            throw new IllegalArgumentException("物品 ID 不能以 # 开头：" + itemId);
        }
        return new WantedItem(itemId);
    }

    /** 一个标签下的任一物品，例如 #minecraft:logs。 */
    public static WantedItem ofTag(String tagId) {
        return new WantedItem(TAG_PREFIX + tagId);
    }

    public boolean isTag() {
        return specifier.startsWith(TAG_PREFIX);
    }

    /** 具体物品的 ID；标签时没有意义，返回 null。 */
    public String itemId() {
        return isTag() ? null : specifier;
    }

    /** 标签的 ID；具体物品时返回 null。 */
    public String tagId() {
        return isTag() ? specifier.substring(TAG_PREFIX.length()) : null;
    }

    /**
     * 一个物品算不算想要的：具体物品按 ID 相等，标签按这个物品挂着的标签里有没有它。
     * 物品的标签由调用方从游戏读出来传进来，这里只做纯匹配。
     */
    public boolean matches(String candidateItemId, Set<String> candidateTags) {
        Objects.requireNonNull(candidateItemId, "candidateItemId");
        return isTag() ? candidateTags.contains(tagId()) : specifier.equals(candidateItemId);
    }

    /** 给结果与日志用的一句话，例如"minecraft:coal"或"#minecraft:logs 里的任一物品"。 */
    public String describe() {
        return isTag() ? specifier + " 里的任一物品" : specifier;
    }
}
