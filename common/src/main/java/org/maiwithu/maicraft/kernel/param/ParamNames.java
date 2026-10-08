// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.param;

import java.util.Map;
import java.util.Set;

/**
 * 参数名表：同一个概念全接口只有一个名字，一个名字也只有一种意思。
 *
 * <p>能力声明参数时，参数名必须先登记在这里，否则创建参数规格时直接报错。
 * 这样 LLM 学会一个名字就能在所有能力里通用，不用逐个能力去猜"范围"叫 radius 还是 search_radius。
 *
 * <p>确实需要新概念时：先确认现有名字表达不了，在这里加一行写清含义，同步更新对外接口文档，再在能力里使用。
 */
public final class ParamNames {
    private static final Map<String, String> MEANINGS = Map.ofEntries(
            Map.entry("item", "物品 ID，或 # 开头的物品标签"),
            Map.entry("items", "多个物品 ID 或物品标签"),
            Map.entry("block", "方块 ID，或 # 开头的方块标签"),
            Map.entry("blocks", "多个方块 ID 或方块标签"),
            Map.entry("entity", "实体类型 ID"),
            Map.entry("count", "这次要多做多少：拿东西就是再多拿几件（不是背包里的总数），做动作就是做几次"),
            Map.entry("radius", "工作或搜索范围，单位格"),
            Map.entry("max_distance", "出行或搜索的最远距离，单位格"),
            Map.entry("max_seconds", "最多花多少秒"),
            Map.entry("operation", "一个能力内部的几种操作选哪一种，例如 enable、disable、status"),
            Map.entry("via", "指定途径，例如拿东西时指定合成、烧炼或采掘"),
            Map.entry("text", "要发送或书写的文字"),
            Map.entry("message", "要发到游戏聊天的一句话，对全体玩家可见"),
            Map.entry("name", "名字，例如要记住的地点名"));

    private ParamNames() {}

    public static boolean contains(String name) {
        return MEANINGS.containsKey(name);
    }

    /** 参数名的含义；没登记时返回 null。 */
    public static String meaning(String name) {
        return MEANINGS.get(name);
    }

    public static Set<String> names() {
        return MEANINGS.keySet();
    }
}
