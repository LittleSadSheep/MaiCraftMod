// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 逐格补丁合并与存盘写法：同一格以补丁为准、没提的留着、补丁的先后作数；存进去读回来一模一样。 */
class BlueprintPatchTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static PlannedCell stone(int x) {
        return PlannedCell.block(new BlockPos(x, 0, 0), Blocks.STONE.defaultBlockState(), Set.of());
    }

    @Test
    void 同一格以补丁为准_没提的旧目标留着_补丁写的格按补丁先后排到后面() {
        // 墙上 (1,0,0) 改成门、(3,0,0) 新加一块；(0,0,0)、(2,0,0) 补丁没提，照旧。
        PlannedCell door = PlannedCell.block(new BlockPos(1, 0, 0), Blocks.SPRUCE_DOOR.defaultBlockState(), Set.of());
        List<PlannedCell> merged = BlueprintPatch.merge(List.of(stone(0), stone(1), stone(2)), List.of(stone(3), door));
        assertEquals(List.of(stone(0), stone(2), stone(3), door), merged);
    }

    @Test
    void 不看先后_同一位置要的一样就是同一份() {
        assertTrue(BlueprintPatch.same(List.of(stone(0), stone(1)), List.of(stone(1), stone(0))));
        assertFalse(BlueprintPatch.same(List.of(stone(0)), List.of(stone(0), stone(1))), "多一格就是变了");
        PlannedCell dirt = PlannedCell.block(new BlockPos(1, 0, 0), Blocks.DIRT.defaultBlockState(), Set.of());
        assertFalse(BlueprintPatch.same(List.of(stone(0), stone(1)), List.of(stone(0), dirt)), "同一格换了方块就是变了");
    }

    @Test
    void 计划格存进去读回来一模一样() {
        BlockState door = Blocks.SPRUCE_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        for (PlannedCell cell : List.of(PlannedCell.block(new BlockPos(3, 64, -2), door, Set.of("facing", "half")),
                PlannedCell.air(new BlockPos(0, 1, 0)), PlannedCell.block(BlockPos.ZERO, Blocks.WATER.defaultBlockState(), Set.of()),
                stone(5).byMachine())) {
            assertEquals(cell, PlannedCellCodec.decode(PlannedCellCodec.encode(cell)));
        }
    }
}
