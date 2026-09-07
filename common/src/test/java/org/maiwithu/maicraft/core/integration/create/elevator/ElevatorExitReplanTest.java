package org.maiwithu.maicraft.core.integration.create.elevator;

import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;

/** Replays dock synchronization and changing native collision shapes through the actual cabin walker. */
public final class ElevatorExitReplanTest {
    private static final Vec3 ORIGIN = new Vec3(4, 4, 4);
    private static final Vec3 START = new Vec3(.5, 1, .5), EXIT = new Vec3(2.5, 1, .5);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        failedSearchRetries(); changedCabinReroutes(); actualDoorAndSupportRemainRequired();
        System.out.println("ElevatorExitReplanTest: passed");
    }

    private static void failedSearchRetries() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            Scene scene = corridor(); var geometry = scene.geometry(); var motion = new ElevatorMotion();
            // A dock tick can report feet slightly inside the synchronized moving floor.
            h.position(START.add(ORIGIN).add(0, -.001, 0));
            check(inside(h, motion, scene, geometry) == ElevatorMotion.Progress.BLOCKED,
                    "penetrating floor must fail its actual collision search");
            check(motion.diagnostics().get("last_motion_failure").equals("no continuous supported cabin walkway"),
                    "failed search did not report its current cause");
            h.position(START.add(ORIGIN)); h.nextTick();
            check(inside(h, motion, scene, geometry) == ElevatorMotion.Progress.MOVING,
                    "first empty search permanently pinned the same exit goal");
            check(motion.failure.isEmpty(), "recovered route kept reporting the old failure");
            h.position(new Vec3(1.5, 1, .5).add(ORIGIN)); h.nextTick();
            check(inside(h, motion, scene, geometry) == ElevatorMotion.Progress.MOVING,
                    "supported intermediate stance did not advance the route");
            h.position(EXIT.add(ORIGIN)); h.nextTick();
            check(inside(h, motion, scene, geometry) == ElevatorMotion.Progress.REACHED,
                    "synchronized supported exit did not complete");
        }
    }

    private static void changedCabinReroutes() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            Scene scene = corridor(); var motion = new ElevatorMotion(); h.position(START.add(ORIGIN));
            check(inside(h, motion, scene, scene.geometry()) == ElevatorMotion.Progress.MOVING, "initial route unavailable");
            // Preserve the exit and starting pose, but replace the cached middle support with a detour.
            scene.blocks.remove(new BlockPos(1, 0, 0));
            for (int x = 0; x < 3; x++) scene.put(new BlockPos(x, 0, 1), Blocks.IRON_BLOCK.defaultBlockState());
            h.nextTick();
            check(inside(h, motion, scene, scene.geometry()) == ElevatorMotion.Progress.MOVING,
                    "changed cabin geometry reused the old now-unsupported first step");
            var target = (Map<?, ?>) motion.diagnostics().get("motion_target");
            check(((Number) target.get("z")).doubleValue() > START.z + ORIGIN.z,
                    "replanned route did not enter the supported detour");
        }
    }

    private static void actualDoorAndSupportRemainRequired() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            Scene scene = corridor(); var motion = new ElevatorMotion(); h.position(START.add(ORIGIN));
            BlockPos lower = new BlockPos(1, 1, 0);
            var door = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
            scene.put(lower, door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
            scene.put(lower.above(), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
            var closed = scene.geometry();
            check(inside(h, motion, scene, closed) == ElevatorMotion.Progress.BLOCKED,
                    "predicted open-door path walked through the actual closed door");
            h.nextTick();
            check(inside(h, motion, scene, closed) == ElevatorMotion.Progress.BLOCKED,
                    "retry bypassed the still-closed native collision");
            scene.blocks.replaceAll((p, info) -> info.state().hasProperty(BlockStateProperties.OPEN)
                    ? new StructureBlockInfo(p, info.state().setValue(BlockStateProperties.OPEN, true), null) : info);
            h.nextTick();
            check(inside(h, motion, scene, scene.geometry()) == ElevatorMotion.Progress.MOVING,
                    "opening the synchronized door did not release the same exit route");
            scene.blocks.remove(new BlockPos(1, 0, 0)); h.nextTick();
            check(inside(h, motion, scene, scene.geometry()) == ElevatorMotion.Progress.BLOCKED,
                    "open door authorized crossing a missing floor");
            h.nextTick();
            check(inside(h, motion, scene, scene.geometry()) == ElevatorMotion.Progress.BLOCKED,
                    "repeated planning turned unsupported space into a walkway");
        }
    }

    private static ElevatorMotion.Progress inside(InteractionWorldTestHarness h, ElevatorMotion motion,
                                                  Scene scene, ElevatorGeometry geometry) {
        var cabin = new CreateElevatorBridge.Cabin(h.player, null,
                new CreateElevatorBridge.Column(0, 0, Direction.NORTH), 0, 5, true, ORIGIN,
                scene.blocks, scene, List.of(), List.of(), 0, 16);
        return motion.inside(ClientRuntime.requireContext(h.player), cabin, geometry, EXIT, LongSets.emptySet());
    }

    private static Scene corridor() {
        Scene scene = new Scene();
        for (int x = 0; x < 3; x++) scene.put(new BlockPos(x, 0, 0), Blocks.IRON_BLOCK.defaultBlockState());
        return scene;
    }

    private static final class Scene implements BlockGetter {
        final Map<BlockPos, StructureBlockInfo> blocks = new LinkedHashMap<>();
        void put(BlockPos pos, BlockState state) { blocks.put(pos, new StructureBlockInfo(pos, state, null)); }
        ElevatorGeometry geometry() { return new ElevatorGeometry(blocks, this, .6, 1.8); }
        public BlockState getBlockState(BlockPos pos) { var info = blocks.get(pos); return info == null ? Blocks.AIR.defaultBlockState() : info.state(); }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        public int getHeight() { return 16; }
        public int getMinBuildHeight() { return 0; }
    }

    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
