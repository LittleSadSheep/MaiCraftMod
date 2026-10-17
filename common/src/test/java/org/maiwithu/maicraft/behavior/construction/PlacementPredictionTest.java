// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 放置预测的纯判断：两格方块的另一半、多次放置的进度、要点几下、视角对应的原版朝向顺序。 */
class PlacementPredictionTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 门与床的另一半() {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        var generated = PlacementPrediction.generatedBy(new BlockPos(0, 0, 0), lower);
        assertEquals(1, generated.size());
        assertEquals(new BlockPos(0, 1, 0), generated.getFirst().pos());
        assertEquals(DoubleBlockHalf.UPPER, generated.getFirst().expected().getValue(BlockStateProperties.DOUBLE_BLOCK_HALF));
        BlockState foot = Blocks.RED_BED.defaultBlockState().setValue(BlockStateProperties.BED_PART, BedPart.FOOT)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        assertEquals(new BlockPos(1, 0, 0), PlacementPrediction.generatedBy(BlockPos.ZERO, foot).getFirst().pos());
        assertTrue(PlacementPrediction.generatedBy(BlockPos.ZERO, Blocks.STONE.defaultBlockState()).isEmpty());
        var upper = PlannedCell.block(new BlockPos(0, 1, 0), lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), Set.of());
        assertEquals(new BlockPos(0, 0, 0), PlacementPrediction.primaryOf(upper), "门上半要放的其实是下半");
        var head = PlannedCell.block(new BlockPos(1, 0, 0), foot.setValue(BlockStateProperties.BED_PART, BedPart.HEAD), Set.of());
        assertEquals(new BlockPos(0, 0, 0), PlacementPrediction.primaryOf(head), "床头要放的其实是床脚");
    }

    @Test
    void 多次放置的进度与次数() {
        var doubleSlab = PlannedCell.block(BlockPos.ZERO, Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE), Set.of());
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState bottom = Blocks.STONE_SLAB.defaultBlockState();
        assertTrue(PlacementPrediction.isProgress(doubleSlab, air, bottom), "先放下第一片算进度");
        assertFalse(PlacementPrediction.complete(doubleSlab, bottom), "一片不算放好");
        assertTrue(PlacementPrediction.complete(doubleSlab, bottom.setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE)));
        assertEquals(2, PlacementPrediction.maximumUses(doubleSlab));
        var candles = PlannedCell.block(BlockPos.ZERO, Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.CANDLES, 3), Set.of());
        BlockState one = Blocks.CANDLE.defaultBlockState();
        BlockState two = one.setValue(BlockStateProperties.CANDLES, 2);
        assertTrue(PlacementPrediction.isProgress(candles, air, one));
        assertTrue(PlacementPrediction.isProgress(candles, one, two));
        assertFalse(PlacementPrediction.isProgress(candles, two, one), "数量倒退不算进度");
        assertFalse(PlacementPrediction.isProgress(candles, two, one.setValue(BlockStateProperties.CANDLES, 4)), "超过目标也不算");
        assertFalse(PlacementPrediction.complete(candles, two));
        assertEquals(3, PlacementPrediction.maximumUses(candles));
        var stone = PlannedCell.block(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), Set.of());
        assertEquals(1, PlacementPrediction.maximumUses(stone));
        assertFalse(PlacementPrediction.isProgress(stone, air, air), "没变不算");
    }

    @Test
    void 视角对应的原版朝向顺序() {
        assertEquals(Direction.SOUTH, LookDirections.ordered(0, 0)[0], "正对南时南最近");
        assertEquals(Direction.WEST, LookDirections.ordered(90, 0)[0]);
        assertEquals(Direction.NORTH, LookDirections.ordered(180, 0)[0]);
        assertEquals(Direction.EAST, LookDirections.ordered(-90, 0)[0]);
        assertEquals(Direction.DOWN, LookDirections.ordered(0, 90)[0], "低头看地时下最近");
        assertEquals(Direction.UP, LookDirections.ordered(0, -90)[0]);
        Direction[] ordered = LookDirections.ordered(0, 0);
        assertEquals(ordered[0].getOpposite(), ordered[5], "最后一个是第一个的反向");
        Direction[] placing = LookDirections.forPlacement(ordered, Direction.UP, false);
        assertEquals(Direction.DOWN, placing[0], "贴着实心方块放时支撑面那一向提到最前");
        assertEquals(Direction.SOUTH, LookDirections.forPlacement(ordered, Direction.UP, true)[0], "替换被点格时不提前");
        assertEquals(Direction.UP, LookDirections.vertical(-10));
        assertEquals(Direction.DOWN, LookDirections.vertical(10));
    }

    @Test
    void 支撑顺序先下后四周最后上() {
        assertEquals(Direction.DOWN, PlacementPrediction.SUPPORT_ORDER[0]);
        assertEquals(Direction.UP, PlacementPrediction.SUPPORT_ORDER[5]);
    }
}
