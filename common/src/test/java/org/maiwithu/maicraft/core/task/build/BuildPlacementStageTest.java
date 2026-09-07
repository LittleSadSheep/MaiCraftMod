// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/** An 18-cube machine is assembled from the bottom up; its future roof cannot seal today's workspace. */
public final class BuildPlacementStageTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Map<Long, BuildTaskRecord.Target> plan = new LinkedHashMap<>();
        for (int y = 63; y < 81; y++) for (int z = 0; z < 18; z++) for (int x = 0; x < 18; x++) {
            var target = target(new BlockPos(x, y, z), false);
            plan.put(target.pos().asLong(), target);
        }
        TestWorld live = new TestWorld();
        TestWorld completed = new TestWorld();
        plan.values().forEach(target -> completed.cells.put(target.pos(), target.desiredState()));
        BuildTaskRecord.Target interior = plan.get(BlockPos.asLong(9, 65, 9));
        check(!route(new BuildPlacementStage(completed, pos -> true, plan, interior, false), interior),
                "the finished dense volume really has no first-person stance for its deep interior");
        int checkedLayers = 0;
        for (BuildTaskRecord.Target target : plan.values().stream().sorted(BuildOrder.BUILD_ORDER).toList()) {
            if (target.pos().getX() == 9 && target.pos().getZ() == 9) {
                check(route(new BuildPlacementStage(new TestWorld(), pos -> true, plan, target, true), target),
                        "preflight must keep future layers open at Y=" + target.pos().getY());
                check(route(new BuildPlacementStage(live, pos -> true, plan, target, false), target),
                        "real earlier layers must support interior construction before the roof at Y=" + target.pos().getY());
                checkedLayers++;
            }
            live.cells.put(target.pos(), target.desiredState());
        }
        check(checkedLayers == 18, "test includes every interior layer and the final roof");
        rejectsActualObstructions(plan, interior);
        System.out.println("BuildPlacementStageTest: passed; checked 18 closed-volume construction layers");
    }

    private static void rejectsActualObstructions(Map<Long, BuildTaskRecord.Target> plan, BuildTaskRecord.Target active) {
        TestWorld live = new TestWorld();
        BlockPos feet = active.pos().offset(0, 1, 2);
        var stage = new BuildPlacementStage(live, pos -> true, plan, active, true);
        check(stage.bodyCellAvailable(feet), "unbuilt upper cells initially remain available");
        live.cells.put(feet, Blocks.STONE.defaultBlockState());
        check(!stage.bodyCellAvailable(feet), "a real obstruction in a future target may never be erased by the preview");
        live.cells.clear();
        Vec3 from = new Vec3(9.5, 67.27, 11.5), to = new Vec3(9.5, 67.27, 8.5);
        check(stage.rayClear(from, to, new BlockPos(9, 67, 8)), "future upper blocks do not occlude a ray");
        live.cells.put(new BlockPos(9, 67, 10), Blocks.STONE.defaultBlockState());
        check(!stage.rayClear(from, to, new BlockPos(9, 67, 8)), "real upper obstacles still occlude the identical ray");
        check(!new BuildPlacementStage(live, pos -> false, plan, active, false).bodyCellAvailable(feet),
                "unloaded cells are not proof of an empty stance");
        live.cells.clear();
        BlockPos cleared = active.pos().offset(0, 0, 2);
        Map<Long, BuildTaskRecord.Target> excavation = new HashMap<>(plan);
        excavation.put(cleared.asLong(), target(cleared, true));
        live.cells.put(cleared, Blocks.STONE.defaultBlockState());
        check(new BuildPlacementStage(live, pos -> true, excavation, active, true).bodyCellAvailable(cleared),
                "preflight retains the explicitly earlier excavation contract");
        check(!new BuildPlacementStage(live, pos -> true, excavation, active, false).bodyCellAvailable(cleared),
                "live execution cannot assume that promised excavation actually happened");
    }

    private static boolean route(BuildPlacementStage stage, BuildTaskRecord.Target target) {
        BlockPos support = target.pos().below();
        if (!stage.support(support, Direction.UP)) return false;
        Vec3 point = Vec3.atCenterOf(support).add(0, .4999, 0);
        for (BlockPos stance : BuildPlacementGeometry.candidateStances(target.pos())) {
            Vec3 eye = new Vec3(stance.getX() + .5, stance.getY() + 1.27, stance.getZ() + .5);
            if (stage.bodyCellAvailable(stance) && stage.support(stance.below(), Direction.UP)
                    && eye.distanceToSqr(point) <= 4.45 * 4.45 && stage.rayClear(eye, point, support)) return true;
        }
        return false;
    }

    private static BuildTaskRecord.Target target(BlockPos pos, boolean air) {
        return new BuildTaskRecord.Target(air ? Blocks.AIR.defaultBlockState() : Blocks.IRON_BLOCK.defaultBlockState(),
                air ? Items.AIR : Items.IRON_BLOCK, pos, "matrix cell", null, null, null);
    }
    private static final class TestWorld implements BlockGetter {
        final Map<BlockPos, BlockState> cells = new HashMap<>();
        @Override public BlockState getBlockState(BlockPos pos) {
            return cells.getOrDefault(pos, pos.getY() < 63 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
