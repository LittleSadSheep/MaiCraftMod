// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 把一格方块收进包：走到够得着的地方 → 挖掉它 → 走过去捡起这一下掉出来的东西。
 * 像真人挖矿一样一步步来，不隔空挖，也不把掉在地上的东西丢着不管。
 *
 * <p>收没收到、收了几件以背包的重新清点为准；这个动作只保证每一步都如实做了：
 * 走不到、挖不动以问题失败，掉落物捡不到如实失败。实现接在启动时创建并登记上，测试用替身。
 */
public interface CollectsBlocks {

    /**
     * @param cell        要收的那一格
     * @param permissions 这次任务的许可：走过去能动多少地形按它来
     * @return 收这一格的动作；接不上时为空，调用方按"这条路还没通"换路
     */
    Optional<Action> collect(BlockPos cell, Permissions permissions);
}
