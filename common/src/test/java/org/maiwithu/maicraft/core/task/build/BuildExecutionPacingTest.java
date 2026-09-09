package org.maiwithu.maicraft.core.task.build;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import it.unimi.dsi.fastutil.longs.LongSets;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.pathing.baritone.GroundCorridor;
import org.maiwithu.maicraft.task.TaskState;

/** Bookkeeping may run together, but an unresolved native action must stop the pipeline. */
public final class BuildExecutionPacingTest {
    public static void main(String[] args) {
        var phase = new AtomicInteger();
        var steps = new AtomicInteger();
        var clicks = new AtomicInteger();
        var available = new AtomicBoolean(true);
        var confirmed = new AtomicBoolean(false);
        java.util.function.Supplier<TaskState> step = () -> {
            steps.incrementAndGet();
            if (phase.get() < 2) phase.incrementAndGet();
            else if (phase.get() == 2) {
                check(available.get(), "a second mutation entered the same actor tick");
                clicks.incrementAndGet(); available.set(false); phase.set(3);
            } else if (confirmed.get()) phase.set(0);
            return TaskState.RUNNING;
        };
        BuildTickPipeline.advance(phase::get, step, available::get);
        check(steps.get() == 3 && clicks.get() == 1 && phase.get() == 3,
                "ready selection and aim should not each consume an empty tick");
        available.set(true); steps.set(0);
        BuildTickPipeline.advance(phase::get, step, available::get);
        check(steps.get() == 1 && clicks.get() == 1, "pending receipt is observed once and cannot trigger another click");
        confirmed.set(true); steps.set(0);
        BuildTickPipeline.advance(phase::get, step, available::get);
        check(clicks.get() == 2 && steps.get() == 4, "confirmed prior receipt may lead into the next placement in this tick");
        steps.set(0);
        BuildTickPipeline.advance(phase::get, () -> {
            steps.incrementAndGet(); phase.set(1 - phase.get()); return TaskState.RUNNING;
        }, () -> true);
        check(steps.get() == 8, "phase cycling must yield under a bounded tick budget");

        var clock = new java.util.concurrent.atomic.AtomicLong();
        steps.set(0);
        BuildTickPipeline.advance(phase::get, () -> {
            steps.incrementAndGet(); phase.incrementAndGet(); clock.addAndGet(3_000_000);
            return TaskState.RUNNING;
        }, () -> true, clock::get);
        check(steps.get() == 2, "expensive phases share a tick deadline instead of each claiming a fresh budget");

        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        BlockGetter flat = new BlockGetter() {
            public BlockState getBlockState(BlockPos pos) { return (pos.getY() == -1 ? Blocks.STONE : Blocks.AIR).defaultBlockState(); }
            public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            public int getHeight() { return 384; }
            public int getMinBuildHeight() { return -64; }
        };
        Vec3 from = new Vec3(.5, 0, .5), to = new Vec3(3.5, 0, .5);
        check(clear(flat, from, to), "approach is initially open");
        check(!clear(BuildPlacementMotion.afterPlacement(flat,
                Map.of(new BlockPos(2, 0, 0), Blocks.OAK_PLANKS.defaultBlockState())), from, to),
                "walking placement cannot continue through the block about to appear");
        check(clear(BuildPlacementMotion.afterPlacement(flat,
                Map.of(new BlockPos(2, 0, 1), Blocks.OAK_PLANKS.defaultBlockState())), from, to),
                "a clear neighboring build target permits the approach to continue");
        for (float yaw : new float[]{0, 37, 90, 179, -123}) {
            var movement = BuildPlacementMotion.toward(from, to, yaw);
            double angle = Math.toRadians(yaw);
            double x = -movement.forward() * Math.sin(angle) + movement.strafe() * Math.cos(angle);
            double z = movement.forward() * Math.cos(angle) + movement.strafe() * Math.sin(angle);
            check(Math.abs(x - 1) < 1e-6 && Math.abs(z) < 1e-6,
                    "aiming the camera must not turn the verified approach into another direction");
        }
        System.out.println("BuildExecutionPacingTest: phase budgets and moving placement corridor passed");
    }
    private static boolean clear(BlockGetter world, Vec3 from, Vec3 to) {
        return new GroundCorridor(world, p -> true, .6, 1.8, LongSets.emptySet(), PhysicalObstacleSnapshot.EMPTY).clear(from, to);
    }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
