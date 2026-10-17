// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.travel.WaterCrossing.Kind;
import org.maiwithu.maicraft.behavior.travel.WaterCrossing.Top;

/**
 * 挑放船的岸边站位（纯函数）：脚下这一格站得住（地面是实地、脚和头两格是空的），
 * 前面一格是能放下船的水面（顶上是水、上面两格没有实遮挡、没冻住）。
 * 水面挨着站位的才算：隔着沟坎、垫高了的够不着水面不放。
 */
public final class BoatLaunchSpots {

    /** 站位脚下到水面最多隔几格高：放船要够得着水面，太高够不着。 */
    static final int MAX_DROP = 3;

    /** 一个放船的站位：站在哪一格，对着哪一格水面放船。 */
    public record Spot(BlockPos feet, BlockPos water) {}

    private BoatLaunchSpots() {}

    /**
     * 在一柱岸的附近找放船的站位，近目的地方向的排前面。
     *
     * @param read        读一格方块；没加载的格给空
     * @param shore       勘测得出的出发侧岸柱（地表一点）
     * @param from        角色现在的位置：站位按离它近先排
     * @param towardWater 目的地一侧：放下去的船朝那边开，选朝那边的水面
     * @param limit       最多给几个站位
     */
    public static List<Spot> around(Function<BlockPos, Optional<BlockState>> read, BlockPos shore, BlockPos from,
            BlockPos towardWater, int limit) {
        List<Spot> spots = new ArrayList<>();
        int yHint = shore.getY();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = shore.getX() + dx;
                int z = shore.getZ() + dz;
                WaterCrossing.standFeet(read, x, z, yHint).ifPresent(feetY -> {
                    BlockPos feet = new BlockPos(x, feetY, z);
                    waterNeighbor(read, feet).ifPresent(water -> spots.add(new Spot(feet, water)));
                });
            }
        }
        // 先挑朝目的地那边的水面，再按走到站位的远近排。
        spots.sort(Comparator
                .comparingDouble((Spot spot) -> spot.water().distSqr(towardWater))
                .thenComparingDouble(spot -> spot.feet().distSqr(from)));
        return spots.size() > limit ? List.copyOf(spots.subList(0, limit)) : spots;
    }

    /** 站位四邻里能放船的水面：那一柱顶上是活水、上面留得出船体，水面和站位脚齐平或低几格。 */
    private static Optional<BlockPos> waterNeighbor(Function<BlockPos, Optional<BlockState>> read, BlockPos feet) {
        BlockPos best = null;
        for (BlockPos side : List.of(feet.north(), feet.south(), feet.west(), feet.east())) {
            Optional<BlockPos> water = launchableWater(read, side, feet.getY());
            if (water.isEmpty()) continue;
            if (best == null || water.get().distSqr(feet) < best.distSqr(feet)) best = water.get();
        }
        return Optional.ofNullable(best);
    }

    /** 这一柱是不是能放船的水面：顶上那格是活水、往上两格没有实遮挡、水面比脚低得不太多；是就给水面那格。 */
    static Optional<BlockPos> launchableWater(Function<BlockPos, Optional<BlockState>> read, BlockPos cell, int feetY) {
        Optional<Top> top = WaterCrossing.surface(read, cell.getX(), cell.getZ(),
                cell.getY() + 2, cell.getY() - 3);
        if (top.isEmpty()) return Optional.empty();
        Top found = top.get();
        if (WaterCrossing.kindOf(found.state()) != Kind.WATER) return Optional.empty();
        // 顶格是睡莲的不放：放下去船就被睡莲顶住，和自己的避障打架。
        if (found.state().getBlock() == Blocks.LILY_PAD) {
            return Optional.empty();
        }
        if (feetY - (found.pos().getY() + 1) > MAX_DROP) return Optional.empty();
        // 水面上方两格要放得下船体：有实遮挡就放不下或上不了船。
        for (int above = 1; above <= 2; above++) {
            Optional<BlockState> state = read.apply(found.pos().above(above));
            if (state.isPresent() && !state.get().isAir()) return Optional.empty();
        }
        return Optional.of(found.pos());
    }
}
