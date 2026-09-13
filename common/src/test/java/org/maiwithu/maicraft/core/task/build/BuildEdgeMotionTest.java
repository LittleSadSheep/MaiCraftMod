package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;

/** 用原生碰撞形状核对0.15格真实支撑、完整连续扫掠和慢速潜行指令；不伪造玩家位置或速度。 */
public final class BuildEdgeMotionTest {
    private static final Vec3 ANCHOR = new Vec3(4.5, 76, 8.5), EDGE = new Vec3(4.5, 76, 9.15);
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        partialSupportAndReverse(); unsupportedMiddleAndBodyCollisions(); protectionAndHazards(); steadyGroundAndCameraRelativeInput();
        System.out.println("BuildEdgeMotionTest: continuous narrow support, reversed access, collision/protection and native sneaking controls passed");
    }
    private static void partialSupportAndReverse() {
        var world = new World(); world.set(4, 75, 8, Blocks.SMOOTH_QUARTZ.defaultBlockState());
        new BuildEdgeMotion(ANCHOR, EDGE, new LongOpenHashSet(), p -> true);
        new BuildEdgeMotion(EDGE, ANCHOR, new LongOpenHashSet(), p -> true);
        new BuildEdgeMotion(EDGE, EDGE, new LongOpenHashSet(), p -> true);
        check(clear(world, ANCHOR, EDGE), "the 0.65-block final segment retains 0.15 blocks of real footprint overlap");
        check(clear(world, EDGE, ANCHOR) && clear(world, EDGE, EDGE), "a partly supported edge is a valid reverse or stationary start");
        check(!clear(world, ANCHOR, new Vec3(4.5, 76, 9.5)), "quantizing the continuous edge to its unsupported block center is forbidden");
        check(clear(world, new Vec3(4.4, 76, 8.35), EDGE), "a slightly off-center real arrival is re-proved as its own swept segment");
        world.set(4, 75, 8, Blocks.AIR.defaultBlockState());
        check(!clear(world, ANCHOR, EDGE), "removing the actual support invalidates the prior proof immediately");
    }
    private static void unsupportedMiddleAndBodyCollisions() {
        var world = new World(); world.set(4, 75, 8, Blocks.IRON_BARS.defaultBlockState()); world.set(4, 75, 9, Blocks.IRON_BARS.defaultBlockState());
        Vec3 end = new Vec3(4.5, 76, 9.2);
        check(clear(world, ANCHOR, ANCHOR) && clear(world, end, end), "each endpoint alone has native collision-shape support");
        check(!clear(world, ANCHOR, end), "supported endpoints cannot hide a gap under the swept body");
        world = new World(); world.set(4, 75, 8, Blocks.SMOOTH_QUARTZ.defaultBlockState()); world.set(4, 77, 9, Blocks.STONE.defaultBlockState());
        check(!clear(world, ANCHOR, EDGE), "the whole crouching body must clear an overhead obstacle");
        world.set(4, 77, 9, Blocks.AIR.defaultBlockState());
        var physical = new PhysicalObstacleSnapshot(List.of(new AABB(4.2, 76, 8.95, 4.8, 77.5, 9.05)), 0, 0, "ready");
        check(!BuildEdgeMotion.safeSweep(world, p -> true, .6, 1.5, new LongOpenHashSet(), p -> true, physical, ANCHOR, EDGE),
                "an entity or moving-structure box crossing the final segment blocks motion");
    }
    private static void protectionAndHazards() {
        var world = new World(); world.set(4, 75, 8, Blocks.SMOOTH_QUARTZ.defaultBlockState());
        var forbidden = new LongOpenHashSet(); forbidden.add(new BlockPos(4, 76, 9).asLong());
        check(!BuildEdgeMotion.safeSweep(world, p -> true, .6, 1.5, forbidden, p -> true, PhysicalObstacleSnapshot.EMPTY, ANCHOR, EDGE),
                "explicit forbidden body cells remain forbidden while sneaking");
        check(!BuildEdgeMotion.safeSweep(world, p -> true, .6, 1.5, new LongOpenHashSet(), p -> p.getZ() != 9,
                PhysicalObstacleSnapshot.EMPTY, ANCHOR, EDGE), "the caller's live body predicate is checked for the full sweep");
        Predicate<BlockPos> loaded = p -> p.getZ() < 9;
        world.loaded = loaded;
        check(!BuildEdgeMotion.safeSweep(world, loaded, .6, 1.5, new LongOpenHashSet(), p -> true,
                PhysicalObstacleSnapshot.EMPTY, ANCHOR, EDGE), "missing target chunks are not treated as air or force-loaded");
        world.loaded = p -> true; world.set(4, 76, 9, Blocks.WATER.defaultBlockState());
        check(!clear(world, ANCHOR, EDGE), "a new fluid intersecting the body stops the edge approach");
    }
    private static void steadyGroundAndCameraRelativeInput() {
        check(BuildEdgeMotion.arrived(EDGE, EDGE, new Vec3(0, -.0784, 0), EDGE), "normal native gravity while on ground does not prevent a steady arrival");
        check(!BuildEdgeMotion.arrived(EDGE, EDGE, new Vec3(0, -.0784, .04), EDGE), "arriving with horizontal momentum still requires natural braking");
        check(!BuildEdgeMotion.arrived(EDGE, EDGE, Vec3.ZERO, EDGE.add(0, 0, -.03)), "recent actual movement cannot be counted as a settled frame");
        for (float yaw : new float[]{0, 37, 90, 180, -135}) {
            var input = BuildEdgeMotion.steering(new Vec3(0, 0, 1), .3f, yaw);
            double angle = Math.toRadians(yaw);
            double x = input.strafe() * Math.cos(angle) - input.forward() * Math.sin(angle);
            double z = input.forward() * Math.cos(angle) + input.strafe() * Math.sin(angle);
            check(Math.abs(x) < 1e-6 && Math.abs(z - .3) < 1e-6 && input.sneaking() && !input.sprinting() && !input.jumping(),
                    "camera yaw changes preserve the same slow world-space direction without jumping or sprinting");
        }
        boolean refused = false;
        try { new BuildEdgeMotion(ANCHOR, EDGE.add(0, 0, .1), new LongOpenHashSet(), p -> true); }
        catch (IllegalArgumentException expected) { refused = true; }
        check(refused, "this primitive cannot grow into a longer navigation route");
    }
    private static boolean clear(World world, Vec3 from, Vec3 to) {
        return BuildEdgeMotion.safeSweep(world, p -> true, .6, 1.5, new LongOpenHashSet(), p -> true, PhysicalObstacleSnapshot.EMPTY, from, to);
    }
    private static final class World implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        Predicate<BlockPos> loaded = p -> true;
        void set(int x, int y, int z, BlockState state) { blocks.put(new BlockPos(x, y, z), state); }
        public BlockState getBlockState(BlockPos pos) {
            if (!loaded.test(pos)) throw new AssertionError("queried an unloaded block instead of rejecting its proof");
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        public int getHeight() { return 128; }
        public int getMinBuildHeight() { return 0; }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
