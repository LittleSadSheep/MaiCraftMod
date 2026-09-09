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

/**
 * 检查某种落地办法放好后，角色身体是否放得下、脚下是否能得到支撑；回收前还要检查拿走辅助物后能否安全站住。
 */
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
        if (plan.kind() == LandingAssistPlan.Kind.BOAT) {
            if (LandingBoatRescue.plan(world,plan.feet(),width,height) == null) return false;
            var body = new net.minecraft.world.phys.AABB(plan.aimPoint().x-width/2,plan.aimPoint().y+.563,plan.aimPoint().z-width/2,
                    plan.aimPoint().x+width/2,plan.aimPoint().y+.563+height,plan.aimPoint().z+width/2);
            for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(body.minX,body.minY,body.minZ),BlockPos.containing(body.maxX,body.maxY,body.maxZ)))
                if (!loaded.test(cell) || forbiddenBody.contains(cell.asLong())) return false;
            return true;
        }
        // 新放黏液块或干草会把落脚面抬高一格；检查身体位置时也跟着抬高，不能还按原来的空气格判断。
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
