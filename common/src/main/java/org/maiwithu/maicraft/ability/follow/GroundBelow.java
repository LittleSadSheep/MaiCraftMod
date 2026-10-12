// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import net.minecraft.core.BlockPos;

/**
 * 他脚下站得住的那一格：跟随走到哪、跟上没跟上都以这一格为准。
 *
 * <p>他站在地上就是脚下；在空中（起跳、爬梯子、飞、鞘翅滑翔）往下找，最多 64 格：
 * 下面一格站得住就停在它上面，碰到穿不过又站不住的东西（水面、岩浆）也停在它上面。
 * 他在头顶飞时我站到他正下方就算跟上，不会因为够不着空中的他而判跟不过去。纯函数，格子怎么样由调用方答。
 */
final class GroundBelow {

    /** 往下最多找几格。 */
    static final int SCAN = 64;

    private GroundBelow() {}

    /** 读格子的接缝：生产实现查世界的通行判断，测试直接给答案。 */
    interface Cells {
        /** 这一格能不能踩在上面站住。 */
        boolean standsOn(BlockPos cell);

        /** 这一格身子能不能穿过去（空气、草、开着的门）。 */
        boolean passes(BlockPos cell);
    }

    /**
     * @param feet     他脚下的格
     * @param onGround 他此刻站在地上
     */
    static BlockPos of(BlockPos feet, boolean onGround, Cells cells) {
        if (onGround) return feet;
        BlockPos at = feet;
        for (int i = 0; i < SCAN; i++) {
            BlockPos below = at.below();
            // 下面一格踩得住：就是这儿；下面一格既踩不住又穿不过（水面、岩浆）：也停在这儿，不往液体里找。
            if (cells.standsOn(below) || !cells.passes(below)) return at;
            at = below;
        }
        return at;
    }
}
