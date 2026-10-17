// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;
import java.util.function.Consumer;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 就地摆工作站的执行接缝：附近没有要用的设施（工作台、熔炉）时，在角色身边放一个；用完的台子也从这里收回。
 * 手上的活不因为附近没台子就断掉——真人也会掏出一个工作台放下再用，做完顺手挖起来揣回包里。
 * 现场动作（挑空格、走近、放下方块、把新设施的位置和方块类型记进世界记忆；收回时走过去挖下来捡起掉落物）
 * 由游戏接口层实现，测试用替身。放不了（周围没有能放的空格、游戏拒绝）时动作以问题失败，来源换别的路。
 */
public interface SetsUpWorkstation {

    /**
     * 为放一个工作站生成动作：做完时设施已放下、位置已记进世界记忆。
     *
     * @param blockType 要放的设施方块类型，例如 minecraft:crafting_table、minecraft:furnace
     * @param placedAt  真放下了才收到实际落下的那一格（和记进世界记忆的是同一格）；没放下不会收到
     */
    Optional<Action> placeNearby(String blockType, Consumer<WorldPosition> placedAt);

    /**
     * 把自己刚摆下的那台收回背包：走到跟前、挖下来、捡起这一下掉出来的。做完时那一格已经挖掉、掉落物捡过；
     * 挖不下来、掉落物没捡到时动作以问题失败。没接上收回的现场动作时为空。
     *
     * @param permissions 这次任务的许可：走过去能动多少地形按它来
     */
    default Optional<Action> takeBack(WorldPosition at, Permissions permissions) {
        return Optional.empty();
    }

    /**
     * 记得的那台设施眼下是不是已经没了：那一片加载着、那一格却已经不是这种方块（被挖走、被炸掉）。
     * 没加载、读不到的不算没了，到跟前再看。
     */
    default boolean gone(WorldPosition at, String blockType) {
        return false;
    }
}
