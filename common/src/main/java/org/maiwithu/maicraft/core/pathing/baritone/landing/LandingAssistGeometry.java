package org.maiwithu.maicraft.core.pathing.baritone.landing;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.maiwithu.maicraft.core.pathing.transport.TransportLanding;

/** Check the full body and neighboring protrusions around the actual assisted support height. */
public final class LandingAssistGeometry {
    private LandingAssistGeometry() {}
    public static boolean safeAfterRemoval(BlockGetter world, Predicate<BlockPos> loaded, LandingAssistPlan plan,
                                           double width, double height, LongSet forbiddenBody) {
        var withoutAid = new LandingAssistPlan(LandingAssistPlan.Kind.WATER, plan.feet(), plan.cell(),
                plan.clicked(), plan.face(), false);
        return safe(world, loaded, withoutAid, width, height, forbiddenBody);
    }
    public static boolean safe(BlockGetter world, Predicate<BlockPos> loaded, LandingAssistPlan plan,
                               double width, double height, LongSet forbiddenBody) {
        boolean raisedSlime = plan.kind() == LandingAssistPlan.Kind.SLIME && !plan.existing();
        BlockPos physicalFeet = raisedSlime ? plan.feet().above() : plan.feet();
        BlockGetter geometry = new BlockGetter() {
            public BlockState getBlockState(BlockPos pos) {
                if (pos.equals(plan.cell())) return raisedSlime ? Blocks.SLIME_BLOCK.defaultBlockState()
                        : plan.kind() == LandingAssistPlan.Kind.SLIME ? world.getBlockState(pos) : Blocks.AIR.defaultBlockState();
                return world.getBlockState(pos);
            }
            public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            public BlockEntity getBlockEntity(BlockPos pos) { return world.getBlockEntity(pos); }
            public int getHeight() { return world.getHeight(); }
            public int getMinBuildHeight() { return world.getMinBuildHeight(); }
        };
        return TransportLanding.inspect(geometry, loaded, physicalFeet, width, height, forbiddenBody).destination() != null;
    }
}
