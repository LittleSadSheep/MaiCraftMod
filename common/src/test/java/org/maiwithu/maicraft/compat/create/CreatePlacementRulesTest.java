// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BiFunction;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.construction.ReadsBlocks;

/** Create 放法规则里的纯函数：竖直齿轮箱的轴向、动力辊对着哪一格放。 */
class CreatePlacementRulesTest {

    private static final BlockPos AT = new BlockPos(0, 65, 0);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 一张世界表：没写的格是空气，全都加载着。 */
    private static ReadsBlocks world(Map<BlockPos, BlockState> blocks) {
        return new ReadsBlocks() {
            @Override public boolean loaded(BlockPos pos) {
                return true;
            }

            @Override public BlockState state(BlockPos pos) {
                return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
            }
        };
    }

    @Test
    void 只有东西两侧有轴伸过来_竖直齿轮箱轴向南北() {
        assertEquals(Direction.Axis.Z, CreatePlacementRules.verticalGearboxAxis(EnumSet.of(Direction.Axis.X), Direction.NORTH));
        assertEquals(Direction.Axis.X, CreatePlacementRules.verticalGearboxAxis(EnumSet.of(Direction.Axis.Z), Direction.NORTH));
    }

    @Test
    void 没有轴或两个方向都有_按面朝方向转九十度() {
        // 面朝北，顺时针转九十度是东，轴向东西。
        assertEquals(Direction.Axis.X, CreatePlacementRules.verticalGearboxAxis(Set.of(), Direction.NORTH));
        assertEquals(Direction.Axis.Z, CreatePlacementRules.verticalGearboxAxis(EnumSet.of(Direction.Axis.X, Direction.Axis.Z), Direction.EAST));
    }

    @Test
    void 动力辊下面空着再下面是地面_对着下面那一格放() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(AT.below(2), Blocks.STONE.defaultBlockState());
        assertEquals(AT.below(), CreatePlacementRules.rollerPlaceAt(AT, world(blocks)));
    }

    @Test
    void 动力辊正下方是实心顶面_只能对着那一格放_那格被占着() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(AT.below(), Blocks.STONE.defaultBlockState());
        assertEquals(AT.below(), CreatePlacementRules.rollerPlaceAt(AT, world(blocks)));
    }

    @Test
    void 动力辊下面悬空_对着它自己放() {
        assertEquals(AT, CreatePlacementRules.rollerPlaceAt(AT, world(new HashMap<>())));
        Map<BlockPos, BlockState> slab = new HashMap<>();
        // 下面是下半砖：顶面不满，原版不抬高。
        slab.put(AT.below(), Blocks.STONE_SLAB.defaultBlockState());
        assertEquals(AT, CreatePlacementRules.rollerPlaceAt(AT, world(slab)));
    }

    @Test
    void 扳手转法_宽度优先找最少几下转到要的朝向() {
        // 替身转法：点哪一面就绕那一面的轴顺时针转九十度，点朝向所在的轴不动（照 Create 方向机器的转法）。
        BlockState south = Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.SOUTH);
        BiFunction<BlockState, Direction, Optional<BlockState>> wrench = (state, face) -> {
            Direction facing = state.getValue(BlockStateProperties.FACING);
            return Optional.of(facing.getAxis() == face.getAxis() ? state
                    : state.setValue(BlockStateProperties.FACING, facing.getClockWise(face.getAxis())));
        };
        var down = CreatePlacementRules.turns(south,
                state -> state.getValue(BlockStateProperties.FACING) == Direction.DOWN, wrench).orElseThrow();
        BlockState turned = south;
        for (Direction face : down) turned = wrench.apply(turned, face).orElseThrow();
        assertEquals(Direction.DOWN, turned.getValue(BlockStateProperties.FACING));
        assertTrue(down.size() <= 3, "替身只会顺时针转，最多三下转到朝下：" + down);
        assertTrue(CreatePlacementRules.turns(south, state -> false, wrench).isEmpty(), "转不到就为空");
        assertTrue(CreatePlacementRules.turns(south, state -> true, (state, face) -> Optional.empty()).isEmpty(),
                "读不出转法当转不到");
    }
}
