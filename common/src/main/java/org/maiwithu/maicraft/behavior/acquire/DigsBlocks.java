// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 挖方块的执行接缝：走到一格方块跟前，持续挖到它消失，掉落物等角色自己捡。
 * 采集熟作物与采掘矿石都用它；现场动作（靠近、按住左键、确认方块消失）由游戏接口层
 * 用靠近与交互的公开接口实现，测试用替身。这个入口还没接上时返回 empty，来源如实报告不支持。
 */
public interface DigsBlocks {

    /** 为挖掉一格生成动作；做完时这一格已经是空气。 */
    Optional<Action> dig(BlockPos target);
}
