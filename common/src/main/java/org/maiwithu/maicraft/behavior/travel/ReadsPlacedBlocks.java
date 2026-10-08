// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import net.minecraft.core.BlockPos;
import java.util.List;

/**
 * 读垫过的方块的只读接缝：这次走到路上为开路垫上的临时方块，结算时逐格进结果。
 * 垫方块发生在寻路内部，走到入口的报告不带它；由寻路的实现一方实现本接缝。
 * 垫的方块不自动收回，如实报告；要不要收回由玩家决定。
 */
public interface ReadsPlacedBlocks {

    /** 当前这段走到里垫上的方块格子；没垫过给空列表。 */
    List<BlockPos> placedDuringCurrentWalk();
}
