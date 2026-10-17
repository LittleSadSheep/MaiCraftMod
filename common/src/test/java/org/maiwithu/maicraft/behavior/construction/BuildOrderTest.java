// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 施工顺序：低层先、同层先清空再骨架后贴附件、按排蛇形走；归一化规则把运行态归默认。 */
class BuildOrderTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 低层先_同层清空骨架贴附件_按排蛇形() {
        var torchUp = PlannedCell.block(new BlockPos(0, 1, 0), Blocks.TORCH.defaultBlockState(), Set.of());
        var stoneUp = PlannedCell.block(new BlockPos(0, 1, 1), Blocks.STONE.defaultBlockState(), Set.of());
        var airLow = PlannedCell.air(new BlockPos(3, 0, 0));
        var stoneLow0 = PlannedCell.block(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState(), Set.of());
        var stoneLow1 = PlannedCell.block(new BlockPos(2, 0, 0), Blocks.STONE.defaultBlockState(), Set.of());
        var stoneRow1a = PlannedCell.block(new BlockPos(0, 0, 1), Blocks.STONE.defaultBlockState(), Set.of());
        var stoneRow1b = PlannedCell.block(new BlockPos(2, 0, 1), Blocks.STONE.defaultBlockState(), Set.of());
        var slabLow = PlannedCell.block(new BlockPos(1, 0, 0), Blocks.STONE_SLAB.defaultBlockState(), Set.of());
        List<PlannedCell> cells = new ArrayList<>(List.of(torchUp, stoneUp, airLow, stoneLow0, stoneLow1, stoneRow1a, stoneRow1b, slabLow));
        cells.sort(BuildOrder.LOW_TO_HIGH);
        // 第零层：先清空，再整层整块骨架（第 0 排 x 递增、第 1 排 x 递减），半砖等非整块排在整层骨架之后；第一层：石头先于火把。
        assertEquals(List.of(airLow, stoneLow0, stoneLow1, stoneRow1b, stoneRow1a, slabLow, stoneUp, torchUp), cells);
    }

    @Test
    void 依附件的判定() {
        assertTrue(BuildOrder.needsSupport(Blocks.LADDER.defaultBlockState()));
        assertTrue(BuildOrder.needsSupport(Blocks.OAK_DOOR.defaultBlockState()));
        assertTrue(BuildOrder.needsSupport(Blocks.LANTERN.defaultBlockState()));
        assertTrue(BuildOrder.needsSupport(Blocks.STONE_BUTTON.defaultBlockState()));
        assertFalse(BuildOrder.needsSupport(Blocks.GRINDSTONE.defaultBlockState()));
        assertFalse(BuildOrder.needsSupport(Blocks.STONE.defaultBlockState()));
    }

    @Test
    void 运行态归默认_树叶不腐烂_耕地按泥土() {
        var wheat = Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7);
        assertEquals(0, BlockStateRules.normalize(wheat).getValue(CropBlock.AGE));
        var leaves = Blocks.OAK_LEAVES.defaultBlockState();
        assertTrue(BlockStateRules.normalize(leaves).getValue(BlockStateProperties.PERSISTENT));
        assertEquals(Blocks.CAULDRON.defaultBlockState(), BlockStateRules.normalize(Blocks.WATER_CAULDRON.defaultBlockState()));
        assertEquals(Items.DIRT, BlockStateRules.materialItem(Blocks.FARMLAND));
        assertEquals(4, BlockStateRules.materialCount(Blocks.CANDLE.defaultBlockState()
                .setValue(BlockStateProperties.CANDLES, 4)));
        assertTrue(BlockStateRules.unbuildableReason(Blocks.PISTON_HEAD.defaultBlockState()) != null);
        assertTrue(BlockStateRules.unbuildableReason(Blocks.STONE.defaultBlockState()) == null);
    }
}
