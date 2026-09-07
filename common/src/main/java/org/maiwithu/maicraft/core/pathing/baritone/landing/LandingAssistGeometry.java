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
import org.maiwithu.maicraft.core.pathing.baritone.WaterBucketFall;

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
        boolean raisedSupport = plan.kind().solidSupport() && !plan.existing();
        BlockPos physicalFeet = raisedSupport ? plan.feet().above() : plan.feet();
        BlockGetter geometry = new BlockGetter() {
            public BlockState getBlockState(BlockPos pos) {
                if (plan.kind() == LandingAssistPlan.Kind.WATER) {
                    BlockState state = world.getBlockState(pos);
                    // Removing water preserves slabs/stairs and ignores the transient water
                    // column while independently proving a real solid floor under the body.
                    return pos.equals(plan.cell()) && !WaterBucketFall.waterContainer(state)
                            ? Blocks.AIR.defaultBlockState() : WaterBucketFall.dryGeometry(state);
                }
                if (pos.equals(plan.cell())) return raisedSupport ? plan.kind().block.defaultBlockState()
                        : plan.kind().solidSupport() ? world.getBlockState(pos) : Blocks.AIR.defaultBlockState();
                return WaterBucketFall.dryGeometry(world.getBlockState(pos));
            }
            public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            public BlockEntity getBlockEntity(BlockPos pos) { return world.getBlockEntity(pos); }
            public int getHeight() { return world.getHeight(); }
            public int getMinBuildHeight() { return world.getMinBuildHeight(); }
        };
        var destination = TransportLanding.inspect(geometry, loaded, physicalFeet, width, height, forbiddenBody).destination();
        if (destination == null) return false;
        if (plan.kind() == LandingAssistPlan.Kind.WATER && plan.cell().getY() < plan.feet().getY()) {
            // Waterlogging is useful only when fluid extends above the native collision floor.
            // A full-height top slab/step with water below the feet cannot reset the falling body.
            double waterTop = plan.cell().getY() + net.minecraft.world.level.material.Fluids.WATER
                    .getSource(false).getHeight(world,plan.cell());
            return destination.landingPoint().y < waterTop - 1.0E-4;
        }
        return true;
    }
}
