// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Set;

/**
 * 读物品标签的只读接缝：一个物品在游戏里挂着哪些标签。真正的标签数据在游戏的注册表里，
 * 实现在游戏接口层；测试里用替身摆标签。想要"标签下的任一物品"时，匹配全靠它。
 */
public interface ReadsItemTags {

    /** 这个物品挂着的全部标签，例如 minecraft:oak_log 挂着 minecraft:logs。 */
    Set<String> tagsOf(String itemId);
}
