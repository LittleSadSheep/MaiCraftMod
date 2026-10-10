// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 靠近时的世界只读视图：站位判断需要问的每一件事都在这里问，判断函数自己不读游戏对象。
 * 真正读世界与角色属性的实现在游戏接口层，测试里用替身摆出场景。
 */
public interface ApproachWorldView {

    /** 角色此刻脚底所在的格子。 */
    BlockPos currentFeet();

    /** 角色此刻的眼睛位置；潜行降下的眼高在这里如实反映。 */
    Vec3 currentEye();

    /** 角色此刻是否踩在地面上；跳跃与坠落中都不算落地。 */
    boolean onGround();

    /** 这个位置所在的区块是否已加载；没加载的地形既不能站也不能当理由。 */
    boolean isLoaded(BlockPos at);

    /** 站位脚下一格是否实心，角色要踩在东西上面。 */
    boolean solidFloor(BlockPos feet);

    /** 站位头顶两格是否都没有碰撞，角色要直得起身、跳得起来。 */
    boolean headroom(BlockPos feet);

    /** 从站位脚底往下数，到第一个能落脚的面落差有多少格；旁边没有落脚面时返回一个表示深渊的大值。 */
    double dropBelow(BlockPos feet);

    /** 站位这一格是否泡在液体里。 */
    boolean inFluid(BlockPos feet);

    /** 站位紧邻的格子里有没有岩浆；隔着一格的岩浆不算。 */
    boolean lavaBeside(BlockPos feet);

    /**
     * 从给定眼睛位置看向目标，射线是否先命中目标本身；中途撞到别的方块就是看不见。
     * 透光方块（玻璃、树叶）不挡视线，与角色平时的观察一致。
     */
    boolean visibleFrom(Vec3 eye, ApproachTarget target);
}
