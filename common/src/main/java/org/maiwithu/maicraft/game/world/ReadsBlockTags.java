// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.Set;

/**
 * 读方块标签的只读接缝：一种方块挂着哪些标签、一个标签下有哪些方块。
 * 真正的标签数据在游戏的方块注册表里，进了世界、标签同步过来之后才读得到；测试里用替身摆标签。
 * 容器、工作设施这类"长得像原版的模组方块"都靠它认，不按方块 ID 名单。
 */
public interface ReadsBlockTags {

    /** 这种方块挂着的全部标签，例如 minecraft:red_shulker_box 挂着 minecraft:shulker_boxes；不认识的方块为空。 */
    Set<String> tagsOf(String blockType);

    /** 挂着这个标签的全部方块类型 ID；没有这个标签、或标签还没同步过来时为空。 */
    Set<String> blocksIn(String tag);
}
