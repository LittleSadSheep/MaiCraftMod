// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;

import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 就地摆工作站的执行接缝：附近没有要用的设施（工作台、熔炉）时，在角色身边放一个。
 * 手上的活不因为附近没台子就断掉——真人也会掏出一个工作台放下再用。
 * 现场动作（挑空格、走近、放下方块、把新设施的位置和方块类型记进世界记忆）由游戏接口层实现，
 * 测试用替身。放不了（周围没有能放的空格、游戏拒绝）时动作以问题失败，来源换别的路。
 */
public interface SetsUpWorkstation {

    /**
     * 为放一个工作站生成动作：做完时设施已放下、位置已记进世界记忆。
     *
     * @param blockType 要放的设施方块类型，例如 minecraft:crafting_table、minecraft:furnace
     */
    Optional<Action> placeNearby(String blockType);
}
