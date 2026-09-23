// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.execute;

import net.minecraft.core.BlockPos;

/** 上一客户端 tick 中由角色拥有并执行的地面移动；等待不算移动。 */
public record NavigationStep(BlockPos from, BlockPos to) {
    public NavigationStep { from=from.immutable();to=to.immutable(); }
}
