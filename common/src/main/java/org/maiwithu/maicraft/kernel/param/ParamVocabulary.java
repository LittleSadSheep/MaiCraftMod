// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.param;

import java.util.Map;
import java.util.Set;

/**
 * 参数词汇表：同一个概念全接口只有一个名字（docs/design/07 第 5 节）。
 *
 * <p>能力声明参数时，参数名必须先登记在这里，否则构造参数规格时直接报错。
 * v1 里"范围"一个意思冒出了 radius、search_radius、max_search_radius 等 17 种叫法，
 * LLM 只能逐个能力去猜；在这里登记，就是为了从源头上杜绝。
 *
 * <p>确实需要新概念时：先在这里加一行并写清含义，同步更新 docs/design/07 第 5 节，再在能力里使用。
 */
public final class ParamVocabulary {
    private static final Map<String, String> MEANINGS = Map.ofEntries(
            Map.entry("item", "物品 ID，或 # 开头的物品标签"),
            Map.entry("items", "多个物品 ID 或物品标签"),
            Map.entry("block", "方块 ID，或 # 开头的方块标签"),
            Map.entry("blocks", "多个方块 ID 或方块标签"),
            Map.entry("entity", "实体类型 ID"),
            Map.entry("count", "获取类能力表示再多几件；动作类能力表示做几次；各能力的契约写明是哪一种"),
            Map.entry("radius", "工作或搜索范围，单位格"),
            Map.entry("max_distance", "出行或搜索的最远距离，单位格"),
            Map.entry("max_seconds", "时间上限，单位秒"),
            Map.entry("action", "一个能力内部的少数几种操作之一，例如 enable、disable、status"),
            Map.entry("via", "指定途径，例如获取物品时指定合成、烧炼或采掘"),
            Map.entry("text", "要发送或书写的文字"),
            Map.entry("name", "名字，例如要记住的地点名"));

    private ParamVocabulary() {}

    public static boolean contains(String name) {
        return MEANINGS.containsKey(name);
    }

    /** 参数名的含义；未登记时返回 null。 */
    public static String meaning(String name) {
        return MEANINGS.get(name);
    }

    public static Set<String> names() {
        return MEANINGS.keySet();
    }
}
