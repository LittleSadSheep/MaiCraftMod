// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 蓝图核对：每种结论一例，没加载不猜，留给机器的格不下结论，范围内多出来的只作信息。 */
class BlueprintCheckTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 替身世界：没摆的格按没加载算，摆了的格给状态。 */
    private static final class FakeWorld implements ReadsBlocks {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();

        FakeWorld put(BlockPos pos, BlockState state) {
            blocks.put(pos.immutable(), state);
            return this;
        }

        @Override public boolean loaded(BlockPos pos) {
            return blocks.containsKey(pos);
        }

        @Override public BlockState state(BlockPos pos) {
            return blocks.get(pos);
        }
    }

    private static final String DIM = "minecraft:overworld";

    @Test
    void 每种结论各一格() {
        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.HALF, Half.TOP);
        var cells = List.of(
                PlannedCell.block(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState(), Set.of()),
                PlannedCell.block(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState(), Set.of()),
                PlannedCell.block(new BlockPos(2, 0, 0), stairs, Set.of("half")),
                PlannedCell.block(new BlockPos(3, 0, 0), Blocks.STONE.defaultBlockState(), Set.of()),
                PlannedCell.block(new BlockPos(4, 0, 0), Blocks.STONE.defaultBlockState(), Set.of()),
                PlannedCell.block(new BlockPos(5, 0, 0), Blocks.STONE.defaultBlockState(), Set.of()).byMachine());
        Blueprint blueprint = new Blueprint(DIM, BlockPos.ZERO, cells);
        FakeWorld world = new FakeWorld()
                .put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState())
                .put(new BlockPos(1, 0, 0), Blocks.DIRT.defaultBlockState())
                .put(new BlockPos(2, 0, 0), Blocks.OAK_STAIRS.defaultBlockState())
                .put(new BlockPos(3, 0, 0), Blocks.SHORT_GRASS.defaultBlockState())
                .put(new BlockPos(5, 0, 0), Blocks.AIR.defaultBlockState());
        var result = BlueprintCheck.compare(blueprint, world, Set.of());
        assertEquals(CellState.MATCHES, result.states().get(new BlockPos(0, 0, 0)));
        assertEquals(CellState.WRONG_BLOCK, result.states().get(new BlockPos(1, 0, 0)));
        assertEquals(CellState.WRONG_STATE, result.states().get(new BlockPos(2, 0, 0)));
        assertEquals(CellState.MISSING, result.states().get(new BlockPos(3, 0, 0)));
        assertEquals(CellState.UNKNOWN, result.states().get(new BlockPos(4, 0, 0)));
        assertEquals(CellState.CHECKED_BY_MACHINE, result.states().get(new BlockPos(5, 0, 0)));
        assertFalse(result.complete());
        assertFalse(result.allMatch());
        assertEquals(List.of(new BlockPos(1, 0, 0), new BlockPos(2, 0, 0), new BlockPos(3, 0, 0)),
                result.needingWork(blueprint));
    }

    @Test
    void 没点名属性时同方块就算对_点名了就逐项比() {
        BlockState wanted = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH);
        BlockState live = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
        assertEquals(CellState.MATCHES, BlueprintCheck.stateOf(PlannedCell.block(BlockPos.ZERO, wanted, Set.of()), live));
        assertEquals(CellState.WRONG_STATE,
                BlueprintCheck.stateOf(PlannedCell.block(BlockPos.ZERO, wanted, Set.of("facing")), live));
    }

    @Test
    void 火把落成墙式也算这一格放好了() {
        var cell = PlannedCell.block(BlockPos.ZERO, Blocks.TORCH.defaultBlockState(), Set.of());
        assertEquals(CellState.MATCHES, BlueprintCheck.stateOf(cell, Blocks.WALL_TORCH.defaultBlockState()));
    }

    @Test
    void 栅栏门被推开转了向不算错() {
        BlockState wanted = Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.NORTH);
        BlockState live = Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.SOUTH);
        assertEquals(CellState.MATCHES,
                BlueprintCheck.stateOf(PlannedCell.block(BlockPos.ZERO, wanted, Set.of("facing")), live));
    }

    @Test
    void 清空与倒桶各自的结论() {
        assertEquals(CellState.MATCHES, BlueprintCheck.stateOf(PlannedCell.air(BlockPos.ZERO), Blocks.AIR.defaultBlockState()));
        assertEquals(CellState.WRONG_BLOCK, BlueprintCheck.stateOf(PlannedCell.air(BlockPos.ZERO), Blocks.STONE.defaultBlockState()));
        var water = PlannedCell.block(BlockPos.ZERO, Blocks.WATER.defaultBlockState(), Set.of());
        assertEquals(CellKind.FLUID_SOURCE, water.kind());
        assertEquals(CellState.MATCHES, BlueprintCheck.stateOf(water, Blocks.WATER.defaultBlockState()));
        assertEquals(CellState.MISSING, BlueprintCheck.stateOf(water, Blocks.AIR.defaultBlockState()));
        assertEquals(CellState.WRONG_BLOCK, BlueprintCheck.stateOf(water, Blocks.STONE.defaultBlockState()));
    }

    @Test
    void 范围内没声明的格有东西只作信息() {
        Blueprint blueprint = new Blueprint(DIM, BlockPos.ZERO, List.of(
                PlannedCell.block(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState(), Set.of()),
                PlannedCell.block(new BlockPos(2, 0, 0), Blocks.STONE.defaultBlockState(), Set.of())));
        FakeWorld world = new FakeWorld()
                .put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState())
                .put(new BlockPos(1, 0, 0), Blocks.COBBLESTONE.defaultBlockState())
                .put(new BlockPos(2, 0, 0), Blocks.STONE.defaultBlockState());
        var result = BlueprintCheck.compare(blueprint, world, Set.of());
        assertTrue(result.allMatch());
        assertEquals(List.of(new BlockPos(1, 0, 0)), BlueprintCheck.extras(blueprint, world));
    }
}
