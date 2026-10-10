// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.function.Predicate;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 往下挖楼梯找东西：附近看不见石头时，像玩家一样从脚下斜着往下挖一段楼梯，
 * 每下一级先从上往下挖开前面三格、再走下去，挖出来的土和石头都捡起来；挖够了就停在楼梯底。
 * 实现接在启动时创建并登记上，测试用替身。
 */
public interface DigsStairsDown {

    /**
     * @param yields      哪种方块挖了会掉想要的东西（按方块注册 ID 判断，例如石头掉圆石）
     * @param wantedCells 挖够几格这样的方块就停
     * @param permissions 这次任务的许可：楼梯上每一格动之前都过许可检查
     */
    Action digDown(Predicate<String> yields, int wantedCells, Permissions permissions);
}
