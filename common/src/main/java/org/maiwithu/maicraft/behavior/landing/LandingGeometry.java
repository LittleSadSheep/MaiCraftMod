// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import org.maiwithu.maicraft.behavior.navigation.transport.TransportLanding;
import org.maiwithu.maicraft.game.world.BucketWater;

/**
 * 放好之后人落不落得下：身体放不放得下、脚下有没有真支撑；收回之前再看拿走之后还站不站得住。
 * 按"放好以后"的样子假想一遍地形（水先当排掉，只保留方块本身的外形），交给落点检查。
 */
public final class LandingGeometry {

    private LandingGeometry() {}

    /**
     * 放好这一种之后人能不能安全落在落点：黏液块、干草把落脚面抬高一格，身体也跟着抬高一格查；
     * 水还要水面高过脚下的碰撞面（满格的上半砖、台阶里灌水，水面比脚低，清不了摔落）。
     *
     * @param forbiddenBody 身体不许进的格子
     */
    public static boolean safe(BlockGetter world, Predicate<BlockPos> loaded, LandingPlan plan,
                               double width, double height, LongSet forbiddenBody) {
        boolean raisedSupport = plan.aid().raisesSupport() && !plan.existing();
        BlockPos physicalFeet = raisedSupport ? plan.feet().above() : plan.feet();
        BlockGetter afterwards = new Imagined(world, plan, raisedSupport);
        var destination = TransportLanding.inspect(afterwards, loaded, physicalFeet, width, height, forbiddenBody)
                .destination();
        if (destination == null) return false;
        if (plan.aid() == LandingAid.WATER && plan.cell().getY() < plan.feet().getY()) {
            double waterTop = plan.cell().getY() + Fluids.WATER.getSource(false).getHeight(world, plan.cell());
            return destination.landingPoint().y < waterTop - 1.0E-4;
        }
        return true;
    }

    /** 收回自己放的之后还站不站得住：把那一格当成没放过（水排掉、方块拿走）再查一遍。 */
    public static boolean safeAfterRemoval(BlockGetter world, Predicate<BlockPos> loaded, LandingPlan plan,
                                           double width, double height, LongSet forbiddenBody) {
        var withoutAid = new LandingPlan(LandingAid.WATER, plan.feet(), plan.cell(), plan.clicked(), plan.face(),
                false);
        return safe(world, loaded, withoutAid, width, height, forbiddenBody);
    }

    // 放好以后的地形：水一律当排掉（含水方块只留外形）；放东西那一格换成放好的样子——
    // 水那一格是空的（含水方块留外形），抬高落脚面的换成黏液块或干草，蛛网、藤蔓这类人能穿过去的当空气。
    private record Imagined(BlockGetter world, LandingPlan plan, boolean raisedSupport) implements BlockGetter {
        @Override
        public BlockState getBlockState(BlockPos pos) {
            BlockState state = world.getBlockState(pos);
            if (!pos.equals(plan.cell())) return BucketWater.dryGeometry(state);
            if (plan.aid() == LandingAid.WATER) {
                return BucketWater.waterContainer(state) ? BucketWater.dryGeometry(state)
                        : Blocks.AIR.defaultBlockState();
            }
            if (raisedSupport) return plan.aid().block().defaultBlockState();
            return plan.aid().raisesSupport() ? state : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return world.getBlockEntity(pos);
        }

        @Override
        public int getHeight() {
            return world.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return world.getMinBuildHeight();
        }
    }
}
