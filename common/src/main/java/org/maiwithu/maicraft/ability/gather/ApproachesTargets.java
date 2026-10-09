// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gather;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.goal.Permissions;

/**
 * 靠近采集目标的接缝：把"走到一格跟前"交给站位与靠近的模型（M2）组合出的动作。
 * 采集任务只说去哪，怎么挑站位、怎么走、到不了怎么换站位，都由靠近模型负责；
 * 启动时创建、登记：用世界视图与移动执行接缝实现，测试用替身。
 */
public interface ApproachesTargets {

    /** 为靠近一格生成动作：做完时角色已在够得着、看得见的站位上；到不了时以问题失败。 */
    Action toward(BlockPos target, Permissions permissions);
}
