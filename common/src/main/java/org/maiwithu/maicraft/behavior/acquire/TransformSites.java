// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FluidState;

import org.maiwithu.maicraft.behavior.acquire.spi.FluidTransform;
import org.maiwithu.maicraft.behavior.acquire.spi.FluidTransforms;

/**
 * 往水里扔料要看的现场：角色所在的世界里有哪些配方、附近哪几格静水能扔料。只读；生产实现读客户端世界，测试用替身。
 */
public interface TransformSites {

    /** 这份配方表在角色此刻所在的世界里有哪些配方；不在世界里给空。 */
    Optional<List<FluidTransform>> recipes(FluidTransforms transforms);

    /**
     * 角色周围能扔料的水格，先排旁边没漂着别人东西的，再按远近。
     *
     * @param fluids 认哪几种流体（流体 ID）
     */
    Pools pools(BlockPos center, Set<String> fluids, int radius);

    /**
     * 找到的水格。
     *
     * @param cells    能扔料的格，排好了先后
     * @param complete 附近扫完了没有；没扫完时没找到不等于没有
     */
    record Pools(List<BlockPos> cells, boolean complete) {

        public Pools {
            cells = List.copyOf(cells);
        }
    }

    /** 一格水的样子，排先后用：在哪、离多远、旁边漂没漂着别人的东西。 */
    record Pool(BlockPos cell, double distance, boolean crowded) {
    }

    /**
     * 这一格能不能扔料，纯判断：是认的那种流体的源格（流动的水格不算源）、水不往外流（流向为零，东西不会漂走）、
     * 头顶是空气（扔得进去）。
     */
    static boolean usable(BlockGetter level, BlockPos pos, Set<String> fluids) {
        FluidState fluid = level.getFluidState(pos);
        if (fluid.isEmpty() || !fluid.isSource()) return false;
        if (!fluids.contains(BuiltInRegistries.FLUID.getKey(fluid.getType()).toString())) return false;
        if (!level.getBlockState(pos.above()).isAir()) return false;
        return fluid.getFlow(level, pos).lengthSqr() < 1.0e-6;
    }

    /** 排先后：旁边没漂着别人东西的在前，再按远近。 */
    static List<BlockPos> order(List<Pool> pools) {
        return pools.stream()
                .sorted(Comparator.comparing(Pool::crowded).thenComparingDouble(Pool::distance))
                .map(Pool::cell)
                .toList();
    }
}
