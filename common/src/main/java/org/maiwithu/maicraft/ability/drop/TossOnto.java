// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.drop;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.inventory.TossesOnto;
import org.maiwithu.maicraft.kernel.goal.Permissions;

/** 把身上的东西朝一格扔过去（扔到传送带、工作盆上）：走到旁边、换到主手、按抛物线丢，看着它落下。 */
@FunctionalInterface
public interface TossOnto {

    /** 朝 cell 扔 count 件 itemId 的动作。 */
    TossesOnto toss(String itemId, int count, BlockPos cell, Permissions permissions);
}
