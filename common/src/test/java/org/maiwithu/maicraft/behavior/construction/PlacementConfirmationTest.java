// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation.Verdict;

/** 放置确认：主格、另一半与耗材一起看；没变要等服务端确认才算没生效，另一半晚到继续等，变成别的是出乎预料。 */
class PlacementConfirmationTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    @Test
    void 单格方块的四种结论() {
        var cell = PlannedCell.block(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), Set.of());
        var confirmation = new PlacementConfirmation(cell, Map.of(BlockPos.ZERO, AIR), Blocks.STONE.defaultBlockState(), -1);
        Map<BlockPos, BlockState> live = new HashMap<>(Map.of(BlockPos.ZERO, AIR));
        assertEquals(Verdict.PENDING, confirmation.observe(live::containsKey, live::get, false), "没变、确认没到：等");
        assertEquals(Verdict.NOT_APPLIED, confirmation.observe(live::containsKey, live::get, true), "没变、确认到了：没生效");
        live.put(BlockPos.ZERO, Blocks.STONE.defaultBlockState());
        assertEquals(Verdict.APPLIED, confirmation.observe(live::containsKey, live::get, false));
        live.put(BlockPos.ZERO, Blocks.DIRT.defaultBlockState());
        assertEquals(Verdict.DIVERGED, confirmation.observe(live::containsKey, live::get, true), "放成了别的");
        assertEquals(Verdict.PENDING, confirmation.observe(pos -> false, live::get, true), "没加载不下结论");
    }

    @Test
    void 门要连上半一起看_铰链按预测() {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        BlockState predicted = lower.setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.RIGHT);
        var cell = PlannedCell.block(BlockPos.ZERO, lower, Set.of("facing"));
        var confirmation = new PlacementConfirmation(cell, Map.of(BlockPos.ZERO, AIR, BlockPos.ZERO.above(), AIR), predicted, -1);
        assertEquals(1, confirmation.generated().size());
        Map<BlockPos, BlockState> live = new HashMap<>(Map.of(BlockPos.ZERO, predicted, BlockPos.ZERO.above(), AIR));
        assertEquals(Verdict.PENDING, confirmation.observe(live::containsKey, live::get, true), "下半到了、上半还没同步：继续等");
        live.put(BlockPos.ZERO.above(), predicted.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        assertEquals(Verdict.APPLIED, confirmation.observe(live::containsKey, live::get, true), "右铰链是原版落法，不算错");
        live.put(BlockPos.ZERO.above(), Blocks.STONE.defaultBlockState());
        assertEquals(Verdict.DIVERGED, confirmation.observe(live::containsKey, live::get, true), "上半变成了别的东西");
    }

    @Test
    void 双层半砖先放一片算生效() {
        var cell = PlannedCell.block(BlockPos.ZERO, Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE), Set.of());
        var confirmation = new PlacementConfirmation(cell, Map.of(BlockPos.ZERO, AIR), Blocks.STONE_SLAB.defaultBlockState(), -1);
        Map<BlockPos, BlockState> live = Map.of(BlockPos.ZERO, Blocks.STONE_SLAB.defaultBlockState());
        assertEquals(Verdict.APPLIED, confirmation.observe(live::containsKey, live::get, true));
    }

    @Test
    void 耗材没扣就继续等_扣了却说没生效也等() {
        var cell = PlannedCell.block(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), Set.of());
        var confirmation = new PlacementConfirmation(cell, Map.of(BlockPos.ZERO, AIR), Blocks.STONE.defaultBlockState(), 10);
        assertEquals(Verdict.PENDING, confirmation.withMaterial(Verdict.APPLIED, 10), "方块到了但一件没扣：等库存同步");
        assertEquals(Verdict.APPLIED, confirmation.withMaterial(Verdict.APPLIED, 9));
        assertEquals(Verdict.PENDING, confirmation.withMaterial(Verdict.NOT_APPLIED, 9), "扣了却没看到方块：等同一单，不再点");
        assertEquals(Verdict.NOT_APPLIED, confirmation.withMaterial(Verdict.NOT_APPLIED, 10));
        var creative = new PlacementConfirmation(cell, Map.of(BlockPos.ZERO, AIR), Blocks.STONE.defaultBlockState(), -1);
        assertEquals(Verdict.APPLIED, creative.withMaterial(Verdict.APPLIED, 10), "不看耗材时照世界的结论");
    }

    @Test
    void 摆放属性要一致_运行态不比() {
        BlockState north = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        BlockState south = north.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
        assertTrue(PlacementConfirmation.samePlacement(north, north));
        assertFalse(PlacementConfirmation.samePlacement(south, north));
        BlockState open = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, true);
        assertTrue(PlacementConfirmation.samePlacement(open, Blocks.OAK_DOOR.defaultBlockState()), "开关是运行态");
    }
}
