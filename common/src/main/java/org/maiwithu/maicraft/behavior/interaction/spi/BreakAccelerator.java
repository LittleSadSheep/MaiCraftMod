// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction.spi;

import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;

/**
 * 挖掘加速的接缝：对准一格的某一面、按住连锁键挖它时，模组按选好的形状把一批格一起挖掉（FTB Ultimine 这类连锁挖）。
 * 模组按自己的形状一起挖，没法让它跳过某几格，所以整批每一格调用方都同意才真连锁；
 * 有一格不同意、等不到模组的预览、此刻用不了，就松开连锁键只挖这一格。松键后哪些真挖了由调用方逐格复查。
 * 一批最多几格由模组按自己的配置给，这里不另定。
 */
public interface BreakAccelerator {

    /**
     * 开一次连锁挖：挖第一下之前反复问它准备好没有，挖的时候每刻续按，挖完松开。
     * 模组没装、服务器不支持时为空，调用方照常逐格挖。
     *
     * @param shape    要一起挖的形状
     * @param mayBreak 这一格能不能挖：模组给出的那一批每一格都要它同意
     */
    Optional<ChainKeyHold> hold(ChainShape shape, Predicate<BlockPos> mayBreak);

    /** 一起挖的形状：挖什么用哪种，按命中的那一面算。 */
    enum ChainShape {
        /** 命中面上以触发格为中心的 3×3：清障挖一片。 */
        FACE_SQUARE,
        /** 和触发格连着的同一种方块：整条矿脉、整根树干。 */
        CONNECTED,
        /** 从触发格顺着角色朝向斜着往下的一条：挖往下的台阶，三行各挖一次。 */
        DOWNWARD_LINE,
        /** 从触发格顺着角色朝向直着往前的一条：挖水平通道，两行各挖一次。 */
        STRAIGHT_LINE
    }
}
