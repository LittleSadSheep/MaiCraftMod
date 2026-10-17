// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * 世界替身：一块平整的默认地面，按测试需要在指定格子摆出悬崖、水、岩浆、挡板与禁行区。
 * 默认处处可站、处处看得见，测试只声明与默认不同的部分。
 */
final class StubWorld implements ApproachWorldView {

    BlockPos feet = new BlockPos(0, 64, 0);
    boolean grounded = true;
    final Set<BlockPos> noFloor = new HashSet<>();
    final Set<BlockPos> lowCeiling = new HashSet<>();
    final Set<BlockPos> deepDrops = new HashSet<>();
    final Set<BlockPos> fluids = new HashSet<>();
    final Set<BlockPos> lavas = new HashSet<>();
    /** 正下方那格是岩浆的格子：要挖的方块在脚下时看下面用什么。 */
    final Set<BlockPos> lavaUnderneath = new HashSet<>();
    final Set<BlockPos> unloaded = new HashSet<>();
    /** 看不见的默认反面：只对声明为挡住的眼睛位置回答看不见。 */
    BiPredicate<Vec3, ApproachTarget> hidden = (eye, target) -> false;
    /** 人在脚下那一格里的精确位置；不摆时按格子底面中心。 */
    Vec3 position;

    @Override public BlockPos currentFeet() {
        return feet;
    }

    @Override public Vec3 currentPosition() {
        return position != null ? position : ApproachWorldView.super.currentPosition();
    }

    @Override public Vec3 currentEye() {
        return new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
    }

    @Override public boolean onGround() {
        return grounded;
    }

    @Override public boolean isLoaded(BlockPos at) {
        return !unloaded.contains(at);
    }

    @Override public boolean solidFloor(BlockPos at) {
        return !noFloor.contains(at);
    }

    @Override public boolean headroom(BlockPos at) {
        return !lowCeiling.contains(at);
    }

    @Override public double dropBelow(BlockPos at) {
        return deepDrops.contains(at) ? 10 : 0;
    }

    @Override public boolean inFluid(BlockPos at) {
        return fluids.contains(at);
    }

    @Override public boolean lavaBeside(BlockPos at) {
        return lavas.contains(at);
    }

    @Override public boolean lavaUnder(BlockPos at) {
        // 与生产实现同一语义：问的是这一格的正下方那格。
        return lavaUnderneath.contains(at.below());
    }

    @Override public boolean visibleFrom(Vec3 eye, ApproachTarget target) {
        return !hidden.test(eye, target);
    }
}
