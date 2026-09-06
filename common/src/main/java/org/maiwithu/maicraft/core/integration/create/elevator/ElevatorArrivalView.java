package org.maiwithu.maicraft.core.integration.create.elevator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/** Read-only arrival hypothesis for doors that Create's native moving-door behaviour will open. */
final class ElevatorArrivalView implements BlockGetter {
    private final BlockGetter world;
    private final Map<BlockPos, BlockState> opened;

    private ElevatorArrivalView(BlockGetter world, Map<BlockPos, BlockState> opened) {
        this.world = world;
        this.opened = Map.copyOf(opened);
    }

    static ElevatorArrivalView predict(BlockGetter world, Predicate<BlockPos> loaded, Map<BlockPos, Direction> nativePairs) {
        Map<BlockPos, BlockState> opened = new LinkedHashMap<>();
        for (var pair : nativePairs.entrySet()) {
            BlockPos pos = pair.getKey();
            if (!loaded.test(pos)) continue;
            BlockState state = world.getBlockState(pos);
            if (!(state.getBlock() instanceof DoorBlock) || state.getValue(DoorBlock.FACING).getAxis() != pair.getValue().getAxis()) continue;
            opened.put(pos.immutable(), state.setValue(DoorBlock.OPEN, true));
            BlockPos other = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            if (!loaded.test(other)) continue;
            BlockState half = world.getBlockState(other);
            if (half.is(state.getBlock()) && half.getValue(DoorBlock.FACING) == state.getValue(DoorBlock.FACING)
                    && half.getValue(DoorBlock.HALF) != state.getValue(DoorBlock.HALF)) opened.put(other, half.setValue(DoorBlock.OPEN, true));
        }
        return new ElevatorArrivalView(world, opened);
    }

    // SlidingDoorMovementBehaviour.getDoorFacing: elevators translate without rotating their cabin.
    static Direction outward(BlockPos local, Direction stateFacing, Vec3 boundsCenter) {
        Direction direction = Direction.get(Direction.AxisDirection.POSITIVE, stateFacing.getAxis());
        Vec3 difference = Vec3.atCenterOf(local).add(Vec3.atLowerCornerOf(stateFacing.getNormal()).scale(-0.45)).subtract(boundsCenter);
        return direction.getAxis().choose(difference.x, difference.y, difference.z) < 0 ? direction.getOpposite() : direction;
    }

    Map<BlockPos, BlockState> predictedDoors() { return opened; }
    @Override public BlockState getBlockState(BlockPos pos) { return opened.getOrDefault(pos, world.getBlockState(pos)); }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public BlockEntity getBlockEntity(BlockPos pos) { return world.getBlockEntity(pos); }
    @Override public int getHeight() { return world.getHeight(); }
    @Override public int getMinBuildHeight() { return world.getMinBuildHeight(); }
}
