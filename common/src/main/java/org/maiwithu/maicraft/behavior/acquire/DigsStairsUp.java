// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.function.Predicate;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskRecords;

/**
 * 往上挖楼梯回地面：在地下、头顶压着石头时，像玩家一样斜着往上挖一段楼梯，每上一级先挖开头顶、前上方、
 * 前方齐头三格，再走上去，直到脚下露天。实现接在启动时创建并登记上，测试用替身。
 */
public interface DigsStairsUp {

    /**
     * @param outside     站在这一格（脚的位置）算不算已经出来了，例如头顶露天
     * @param permissions 这次任务的许可：楼梯上每一格动之前都过许可检查
     * @param records     记账：挖掉的方块按种类合并计数记进这次的变化
     */
    Action digUp(Predicate<BlockPos> outside, Permissions permissions, TaskRecords records);
}
