// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 放自带床的接缝：把身上的一张床放到角色身边能放的两格上。测试给固定值。
 *
 * <p>床是两格方块，放下后哪格是床头由放置结算决定，放完从动作上读实际床头。
 */
public interface PlacesBed {

    /** 生成放床的动作；接不上（没接生产实现）时为空，调用方按"这条路没通"如实说。 */
    Optional<BedPlacement> placeCarriedBed();

    /** 一次放床的动作；做完后床头在哪一格由它回答。 */
    interface BedPlacement extends Action {

        /** 放下的床的床头位置；动作没做完时内容无意义。 */
        BlockPos placedHead();
    }
}
