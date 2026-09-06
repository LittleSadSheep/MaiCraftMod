package org.maiwithu.maicraft.core.integration.create.elevator;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/** Actual vanilla shapes model moving support, a two-half doorway and a fixed landing. */
public final class ElevatorGeometryTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Scene cabin = new Scene(), world = new Scene();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) cabin.put(new BlockPos(x, 0, z), Blocks.IRON_BLOCK.defaultBlockState());
        for (int x = -1; x <= 1; x++) for (int z = -3; z <= -2; z++) world.put(new BlockPos(x, 100, z), Blocks.STONE.defaultBlockState());
        Vec3 origin = new Vec3(0, 100, 0), inside = new Vec3(0.5, 1, 0.5), porch = new Vec3(0.5, 1, -0.5);
        Vec3 outside = new Vec3(0.5, 101, -1.5);
        ElevatorGeometry geometry = cabin.geometry();
        check(geometry.carries(inside), "standing on moving support must not require being a passenger");
        check(!geometry.carries(inside.add(0, 0.5, 0)), "an airborne body was treated as boarded");
        check(!geometry.landings(world, p -> true, origin, 0.6, LongSets.emptySet()).isEmpty(), "aligned fixed landing not found");
        check(geometry.landings(new Scene(), p -> true, origin, 0.6, LongSets.emptySet()).isEmpty(), "void counted as an exit");
        check(geometry.canStep(world, p -> true, origin, outside, porch.add(origin), 0.6, LongSets.emptySet()), "continuous fixed-to-moving floor transition");
        check(!geometry.path(porch, inside, origin, 0.6, LongSets.emptySet()).isEmpty(), "supported internal cabin path");
        var forbidden = new LongOpenHashSet(); forbidden.add(BlockPos.asLong(0, 101, -1));
        check(!geometry.canStep(world, p -> true, origin, outside, porch.add(origin), 0.6, forbidden), "boarding crossed forbidden body cells");
        forbidden.clear(); forbidden.add(BlockPos.asLong(0, 107, 0));
        check(!ElevatorSurvey.rideAllowed(inside, origin, origin.add(0, 12, 0), 0.6, 1.8, forbidden), "ride crossed a forbidden intermediate floor");
        var door = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
        cabin.put(new BlockPos(0, 1, -1), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        cabin.put(new BlockPos(0, 2, -1), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        ElevatorGeometry closed = cabin.geometry();
        check(!(closed.canStep(world, p -> true, origin, outside, porch.add(origin), 0.6, LongSets.emptySet())
                && closed.canStep(world, p -> true, origin, porch.add(origin), inside.add(origin), 0.6, LongSets.emptySet())), "closed two-half door did not block boarding");
        cabin.blocks.replaceAll((p, info) -> info.state().hasProperty(BlockStateProperties.OPEN)
                ? new StructureBlockInfo(p, info.state().setValue(BlockStateProperties.OPEN, true), null) : info);
        ElevatorGeometry open = cabin.geometry();
        check(open.canStep(world, p -> true, origin, outside, porch.add(origin), 0.6, LongSets.emptySet())
                && open.canStep(world, p -> true, origin, porch.add(origin), inside.add(origin), 0.6, LongSets.emptySet()), "open door retained stale collision");
        check(!open.canStep(world, p -> false, origin, outside, porch.add(origin), 0.6, LongSets.emptySet()), "unloaded exit geometry was accepted");
        callConnections(); supportLayers(); arrivingDoors();
        System.out.println("ElevatorGeometryTest: passed");
    }

    private static void callConnections() {
        Scene world = new Scene(); BlockPos contact = new BlockPos(0, 64, 0), button = new BlockPos(2, 64, 0);
        var state = Blocks.STONE_BUTTON.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        world.put(button.west(), Blocks.STONE.defaultBlockState());
        check(ElevatorSurvey.feeds(world, contact, button, state, true), "strong-powered adjacent support association");
        check(!ElevatorSurvey.feeds(world, contact, button, state.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST), true), "wrong button attachment inferred a call circuit");
        world.put(button.west(), Blocks.GLASS.defaultBlockState());
        check(!ElevatorSurvey.feeds(world, contact, button, state, true), "non-conducting support inferred a circuit");
        check(ElevatorSurvey.feeds(world, contact, contact.above(), state, true)
                && ElevatorSurvey.feeds(world, contact.above(2), contact.above(), state, true), "multi-floor input must be detectable as ambiguous");
    }

    private static void supportLayers() {
        Scene cabin = new Scene();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) cabin.put(new BlockPos(x, 0, z), Blocks.IRON_BLOCK.defaultBlockState());
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) cabin.put(new BlockPos(x, -4, z), Blocks.IRON_BLOCK.defaultBlockState());
        var stances = cabin.geometry().stances;
        check(stances.stream().filter(p -> p.y == 1).count() > stances.stream().filter(p -> p.y == -3).count(), "fixture roof must be larger than its cabin floor");
        check(ElevatorSurvey.deckCandidates(stances, 118, 115).equals(java.util.List.of(-3.0)), "larger roof hid the source cabin floor");
        check(ElevatorSurvey.deckCandidates(stances, 106, 103).equals(java.util.List.of(-3.0)), "contact offset failed to project destination floor");
        check(ElevatorSurvey.deckCandidates(stances, 106, 110).isEmpty(), "invented unsupported deck height");
    }

    private static void arrivingDoors() {
        Scene cabin = new Scene(), world = new Scene();
        cabin.put(BlockPos.ZERO, Blocks.IRON_BLOCK.defaultBlockState());
        world.put(new BlockPos(0, 100, -1), Blocks.STONE.defaultBlockState());
        BlockPos lower = new BlockPos(0, 101, -1);
        var door = Blocks.IRON_DOOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        world.put(lower, door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        world.put(lower.above(), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        var geometry = cabin.geometry();
        Vec3 origin = new Vec3(0, 100, 0), inside = new Vec3(0.5, 101, 0.5), outside = new Vec3(0.5, 101, -0.5);
        check(geometry.landings(world, p -> true, origin, 0.6, LongSets.emptySet(), 1).isEmpty(), "closed static door between clear endpoints escaped survey collision");
        var predicted = ElevatorArrivalView.predict(world, p -> true, Map.of(lower, Direction.NORTH));
        check(!geometry.landings(predicted, p -> true, origin, 0.6, LongSets.emptySet(), 1).isEmpty(), "native paired arrival door permanently rejected a valid landing");
        check(!geometry.canStep(world, p -> true, origin, outside, inside, 0.6, LongSets.emptySet()), "prediction authorized movement through a still-closed actual door");
        check(geometry.canStep(predicted, p -> true, origin, outside, inside, 0.6, LongSets.emptySet()), "open native doorway retained stale collision");
        check(!world.getBlockState(lower).getValue(BlockStateProperties.OPEN) && predicted.predictedDoors().size() == 2, "prediction mutated world or omitted the second half");
        check(ElevatorArrivalView.predict(world, p -> true, Map.of()).predictedDoors().isEmpty(), "unassociated door was predicted open");
        check(ElevatorArrivalView.predict(world, p -> true, Map.of(lower, Direction.EAST)).predictedDoors().isEmpty(), "wrong facing axis inferred native pairing");
        check(ElevatorArrivalView.predict(world, p -> false, Map.of(lower, Direction.NORTH)).predictedDoors().isEmpty(), "unloaded door state became evidence");
        world.put(lower, Blocks.STONE.defaultBlockState());
        check(geometry.landings(ElevatorArrivalView.predict(world, p -> true, Map.of(lower, Direction.NORTH)), p -> true,
                origin, 0.6, LongSets.emptySet(), 1).isEmpty(), "door prediction erased an actual wall");
        check(ElevatorArrivalView.outward(new BlockPos(0, 1, -1), Direction.NORTH, new Vec3(0.5, 1.5, 0.5)) == Direction.NORTH,
                "native cabin bounds did not determine the outward face");
    }

    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static final class Scene implements BlockGetter {
        final Map<BlockPos, StructureBlockInfo> blocks = new LinkedHashMap<>();
        void put(BlockPos pos, BlockState state) { blocks.put(pos, new StructureBlockInfo(pos, state, null)); }
        ElevatorGeometry geometry() { return new ElevatorGeometry(blocks, this, 0.6, 1.8); }
        public BlockState getBlockState(BlockPos pos) { var info = blocks.get(pos); return info == null ? Blocks.AIR.defaultBlockState() : info.state(); }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        public int getHeight() { return 384; }
        public int getMinBuildHeight() { return -64; }
    }
}
