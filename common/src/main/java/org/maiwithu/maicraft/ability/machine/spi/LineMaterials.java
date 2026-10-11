// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 一种网络怎么用方块接线：机器能力的线路规划按它铺，从来源那张网铺到要接的机器。
 * 应力网是轴（沿轴向直走）、齿轮箱（拐弯）、链条输送机（远距离跨越）；ME 网与能量网是不分方向的线缆。
 * 线路规划只认这里给的方块，不认具体是哪个模组；不会接线的网络不给它，接网络时如实说铺不了。
 */
public interface LineMaterials {

    /** 线接的是哪种进出口：要接的机器与来源都按这种进出口找能接线的面（转动、能量、ME……）。 */
    ExchangePoint.Medium medium();

    /** 沿 axis 走的一格线材；不分方向的三个轴给同一种。 */
    BlockState straight(Direction.Axis axis);

    /** 在一格里从 in 轴拐到 out 轴的那一格：轴用齿轮箱；拐不了给空。不分方向的线缆就是直走那一格。 */
    Optional<BlockState> turn(Direction.Axis in, Direction.Axis out);

    /**
     * 线材接上的面里除了沿线的前后两面，挨着这种方块会不会被一起接上：会就别把线材放在那一格，
     * 免得把旁边别的机器、别的网也连进来（齿轮箱的侧面贴着别人的轴，转向一冲突整段会断）。
     */
    boolean joins(BlockState neighbor);

    /** 这一格线材跟哪几面接上：轴是沿轴向的两面，齿轮箱是轴向以外的四面，线缆是六面。 */
    Set<Direction> connectedFaces(BlockState piece);

    /** 远距离跨越：两头各一个方块，用原生安装段连起来（链条输送机之间的链）；没有为空。 */
    Optional<Span> span();

    /** 远距离跨越的做法。 */
    interface Span {

        /** 两头之间的安装段种类，例如 create:chain_conveyor。 */
        String installationKind();

        /** 新放的那一头是什么方块。 */
        BlockState end();

        /** 这一格已有的方块算不算跨越的一头（来源那张网里已有的链条输送机）。 */
        boolean isEnd(BlockState state);

        /** 两头从哪个轴接线：链条输送机只从上下两面接转动。 */
        Direction.Axis endAxis();

        /** 两头最远相距几格。 */
        int reach();

        /** 两头这样摆合不合模组的规则；合给空，不合给一句说明。 */
        Optional<String> problem(BlockPos from, BlockPos to);

        /** 已有的那一头还能不能再连一条（每台有连接数上限）。 */
        boolean acceptsLink(BlockPos existingEnd);
    }
}
