package baritone.pathing.movement;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Uses actual Minecraft door states and the geometry called by every production movement. */
public final class DoorPassageTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BlockPos doorway = new BlockPos(10, 64, 10);
        for (var block : new DoorBlock[]{(DoorBlock) Blocks.OAK_DOOR, (DoorBlock) Blocks.COPPER_DOOR}) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                for (DoorHingeSide hinge : DoorHingeSide.values()) {
                    for (DoubleBlockHalf half : DoubleBlockHalf.values()) {
                        var closed = block.defaultBlockState().setValue(DoorBlock.FACING, facing)
                                .setValue(DoorBlock.HINGE, hinge).setValue(DoorBlock.HALF, half);
                        var open = closed.setValue(DoorBlock.OPEN, true);
                        for (int sign : new int[]{-1, 1}) {
                            BlockPos outside = doorway.relative(facing, sign);
                            expect(MovementHelper.passageNeedsInteraction(closed, outside, doorway), true);
                            expect(MovementHelper.passageNeedsInteraction(open, outside, doorway), false);
                            expect(MovementHelper.passageNeedsInteraction(closed, doorway, outside), true);
                            expect(MovementHelper.passageNeedsInteraction(open, doorway, outside), false);
                            expect(MovementHelper.passageNeedsInteraction(closed, outside.below(), doorway), true);
                            expect(MovementHelper.passageNeedsInteraction(open, outside.below(), doorway), false);
                            BlockPos sideways = doorway.relative(facing.getClockWise(), sign);
                            expect(MovementHelper.passageNeedsInteraction(closed, sideways, doorway), false);
                            expect(MovementHelper.passageNeedsInteraction(open, sideways, doorway), true);
                        }
                        expect(MovementHelper.passageNeedsInteraction(closed, doorway, doorway.offset(1, 0, 1)), true);
                        expect(MovementHelper.passageNeedsInteraction(open, doorway, doorway.offset(1, 0, 1)), true);
                    }
                }
            }
        }
        var gate = Blocks.OAK_FENCE_GATE.defaultBlockState();
        var iron = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
                .setValue(DoorBlock.OPEN, true);
        expect(MovementHelper.openDoorBlocksDirection(iron, 0, 1), false);
        expect(MovementHelper.openDoorBlocksDirection(iron, 1, 0), true);
        expect(MovementHelper.passageNeedsInteraction(iron, doorway, doorway.above()), false);
        expect(MovementHelper.passageNeedsInteraction(gate, doorway, doorway.north()), true);
        expect(MovementHelper.passageNeedsInteraction(gate.setValue(FenceGateBlock.OPEN, true),
                doorway, doorway.north()), false);
        expect(MovementHelper.passageNeedsInteraction(Blocks.AIR.defaultBlockState(), doorway, doorway.north()), false);
        System.out.println("DoorPassageTest: passed");
    }

    private static void expect(boolean actual, boolean expected) {
        if (actual != expected) throw new AssertionError("Unexpected passage geometry: " + actual);
    }
}
