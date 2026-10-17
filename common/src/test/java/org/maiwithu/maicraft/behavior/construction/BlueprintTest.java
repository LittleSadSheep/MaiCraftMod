// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 蓝图与计划格：落到锚点时偏移与朝向一起转，材料按格计件，重复格与非法格一开始就拒绝。 */
class BlueprintTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final String DIM = "minecraft:overworld";

    @Test
    void 落到锚点时偏移绕锚点转_楼梯朝向一起转() {
        var stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
        var relative = List.of(PlannedCell.block(new BlockPos(2, 0, 0), stairs, Set.of("facing")));
        Blueprint turned = Blueprint.at(DIM, new BlockPos(10, 64, 10), Rotation.CLOCKWISE_90, relative);
        PlannedCell cell = turned.cells().getFirst();
        assertEquals(new BlockPos(10, 64, 12), cell.pos());
        assertEquals(Direction.EAST, cell.state().getValue(StairBlock.FACING));
        assertEquals(new BlockPos(10, 64, 10), turned.anchor());
    }

    @Test
    void 材料按格计件_门上半不计费_双层半砖两件() {
        var lower = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        var upper = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        var doubleSlab = Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE);
        Blueprint blueprint = new Blueprint(DIM, BlockPos.ZERO, List.of(
                PlannedCell.block(new BlockPos(0, 0, 0), lower, Set.of()),
                PlannedCell.block(new BlockPos(0, 1, 0), upper, Set.of()),
                PlannedCell.block(new BlockPos(1, 0, 0), doubleSlab, Set.of()),
                PlannedCell.block(new BlockPos(2, 0, 0), Blocks.FARMLAND.defaultBlockState(), Set.of()),
                PlannedCell.air(new BlockPos(3, 0, 0))));
        var materials = blueprint.materials();
        assertEquals(1, materials.get("minecraft:oak_door"));
        assertEquals(2, materials.get("minecraft:stone_slab"));
        assertEquals(1, materials.get("minecraft:dirt"));
        assertEquals(3, materials.size());
        assertEquals(Items.DIRT, blueprint.cellAt(new BlockPos(2, 0, 0)).orElseThrow().item());
    }

    @Test
    void 水源格是倒桶_物品是水桶() {
        var cell = PlannedCell.block(BlockPos.ZERO, Blocks.LAVA.defaultBlockState(), Set.of());
        assertEquals(CellKind.FLUID_SOURCE, cell.kind());
        assertEquals(Items.LAVA_BUCKET, cell.item());
        assertEquals(1, cell.materialCount());
    }

    @Test
    void 同一格写两次_或点名了方块没有的属性_一开始就拒绝() {
        var stone = Blocks.STONE.defaultBlockState();
        assertThrows(IllegalArgumentException.class, () -> new Blueprint(DIM, BlockPos.ZERO, List.of(
                PlannedCell.block(BlockPos.ZERO, stone, Set.of()), PlannedCell.block(BlockPos.ZERO, stone, Set.of()))));
        assertThrows(IllegalArgumentException.class, () -> PlannedCell.block(BlockPos.ZERO, stone, Set.of("facing")));
    }

    @Test
    void 包围盒与体积() {
        Blueprint blueprint = new Blueprint(DIM, BlockPos.ZERO, List.of(
                PlannedCell.block(new BlockPos(-1, 0, 2), Blocks.STONE.defaultBlockState(), Set.of()),
                PlannedCell.block(new BlockPos(1, 2, 0), Blocks.STONE.defaultBlockState(), Set.of())));
        var bounds = blueprint.bounds();
        assertEquals(new BlockPos(-1, 0, 0), bounds.min());
        assertEquals(new BlockPos(1, 2, 2), bounds.max());
        assertEquals(27, bounds.volume());
    }
}
