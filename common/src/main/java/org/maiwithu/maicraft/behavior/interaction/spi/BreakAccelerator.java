// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction.spi;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 挖掘加速的接缝：对准一格、按住连锁键时，模组会把一批格一起挖掉（FTB Ultimine 这类连锁挖）。
 * 模组按自己的形状一起挖，没法让它跳过某几格，所以调用方只在整批每一格都是本来就要清的格、都过许可时才用它；
 * 松键后哪些真挖了由调用方逐格复查。一批最多几格由模组按自己的配置给，这里不另定。
 */
public interface BreakAccelerator {

    /** 对准 trigger 这一格、按住连锁键时会一起挖的格（含它自己）；模组没装、此刻连不了或这块不连时为空表。 */
    List<BlockPos> wouldBreakWith(BlockPos trigger);

    /** 持键挖 trigger 这一格、连带挖掉那一批的动作；给不出时为空，调用方退回逐格挖。 */
    Optional<Action> breakBatch(BlockPos trigger);
}
