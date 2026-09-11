package org.maiwithu.maicraft.core.integration.machine.control;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.AttachFace;
import static org.maiwithu.maicraft.core.integration.machine.control.ControlComponents.Cell;

public final class ControlSignalsTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var lever=new Cell(BlockPos.ZERO,Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE,AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST),null);
        check(ControlSignals.outputs(lever,Direction.WEST,true),"wall control powers its actual support");
        check(!ControlSignals.outputs(lever,Direction.EAST,true),"opposite side is not strong power");
        var glass=new Cell(BlockPos.ZERO,Blocks.GLASS.defaultBlockState(),null);
        check(!ControlSignals.outputs(glass,Direction.WEST,false),"proximity to a block is not an output port");
        var repeater=new Cell(BlockPos.ZERO,Blocks.REPEATER.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.NORTH),null);
        check(ControlSignals.outputs(repeater,Direction.SOUTH,false),"diode output direction matches native queried side");
        check(!ControlSignals.outputs(repeater,Direction.NORTH,false),"diode is not a bidirectional wire");
        check(ControlSignals.input(repeater,Direction.NORTH)!=null && ControlSignals.input(repeater,Direction.EAST)==null,
                "side locking input cannot be treated as the main signal path");
        var dust=new Cell(BlockPos.ZERO,Blocks.REDSTONE_WIRE.defaultBlockState(),null);
        check(ControlSignals.outputs(dust,Direction.DOWN,true) && !ControlSignals.outputs(dust,Direction.UP,false),"dust powers its support below");
        System.out.println("ControlSignalsTest: passed");
    }
    private static void check(boolean value,String reason) { if(!value) throw new AssertionError(reason); }
}
