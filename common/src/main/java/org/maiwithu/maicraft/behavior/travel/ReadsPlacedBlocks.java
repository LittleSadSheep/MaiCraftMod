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

    /** 当前这段走到里每次落地救援的经过（放了什么、收没收回、掉没掉血）；没有给空列表。 */
    default List<String> landingsDuringCurrentWalk() {
        return List.of();
    }

    /** 当前这段走到里每次卡坑自动脱困的经过（怎么脱的、垫了几块、挖开几格）；没有给空列表。 */
    default List<String> trapEscapesDuringCurrentWalk() {
        return List.of();
    }

    /** 当前这段走到里为开路挖掉的方块："x, y, z 的 方块ID"；没挖过给空列表。 */
    default List<String> brokenDuringCurrentWalk() {
        return List.of();
    }
}
