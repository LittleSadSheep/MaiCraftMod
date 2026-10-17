// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 俯视网格的格子归类：平地、上下台阶、落差、墙、水、岩浆、树，各按能否站立与危险分对。 */
class OverheadGridTest {

    // 角色脚位在 65：脚下一格（64）是地面，身体占 65、66 两格。
    private static final int FEET_Y = 65;

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // 树的归类认原木与树叶标签：替身世界里给橡树的原木与树叶绑上这两个标签。
        BuiltInRegistries.BLOCK.bindTags(Map.of(
                BlockTags.LOGS, List.of(BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.OAK_LOG)),
                BlockTags.LEAVES, List.of(BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.OAK_LEAVES))));
    }

    @Test
    void flatGroundStepUpAndDropFollowTheStandableSurface() {
        FakeWorld world = new FakeWorld();
        // 北边（-Z）默认平地；南边一格抬高：跳一格能站上去，是上台阶；
        // 再南边一格整柱挖空：站不上去也撑不住身体，是落差。
        world.columns.put(new BlockPos(0, FEET_Y, 1), Blocks.STONE.defaultBlockState());
        world.columns.put(new BlockPos(0, 64, 3), Blocks.AIR.defaultBlockState());
        world.columns.put(new BlockPos(0, 63, 3), Blocks.AIR.defaultBlockState());
        assertEquals(OverheadGrid.FLAT, OverheadGrid.classify(world, 0, FEET_Y, -1));
        assertEquals(OverheadGrid.STEP_UP, OverheadGrid.classify(world, 0, FEET_Y, 1));
        assertEquals(OverheadGrid.DROP, OverheadGrid.classify(world, 0, FEET_Y, 3),
                "附近没有可站的地面就是落差或虚空");
    }

    @Test
    void tallWallsAndTwoUpGroundAreWalls() {
        FakeWorld world = new FakeWorld();
        // 身体两格都被堵住是墙；地板比脚位高两格（要连跳两格才上得去）也算墙。
        for (int y = FEET_Y; y <= FEET_Y + 2; y++) {
            world.columns.put(new BlockPos(0, y, -2), Blocks.COBBLESTONE.defaultBlockState());
        }
        world.columns.put(new BlockPos(0, FEET_Y + 1, -3), Blocks.STONE.defaultBlockState());
        world.columns.put(new BlockPos(0, FEET_Y + 2, -3), Blocks.STONE.defaultBlockState());
        assertEquals(OverheadGrid.WALL, OverheadGrid.classify(world, 0, FEET_Y, -2));
        assertEquals(OverheadGrid.WALL, OverheadGrid.classify(world, 0, FEET_Y, -3));
    }

    @Test
    void waterLavaAndTreesGetTheirOwnSymbols() {
        FakeWorld world = new FakeWorld();
        // 水与岩浆看脚位一格；树看身体两格：原木堵着身体又站不上去时按树报。
        world.columns.put(new BlockPos(0, FEET_Y, 2), Blocks.WATER.defaultBlockState());
        world.columns.put(new BlockPos(0, FEET_Y, 4), Blocks.LAVA.defaultBlockState());
        world.columns.put(new BlockPos(0, FEET_Y, -4), Blocks.OAK_LOG.defaultBlockState());
        world.columns.put(new BlockPos(0, FEET_Y + 1, -4), Blocks.OAK_LOG.defaultBlockState());
        assertEquals(OverheadGrid.WATER, OverheadGrid.classify(world, 0, FEET_Y, 2));
        assertEquals(OverheadGrid.HAZARD, OverheadGrid.classify(world, 0, FEET_Y, 4));
        assertEquals(OverheadGrid.TREE, OverheadGrid.classify(world, 0, FEET_Y, -4));
    }

    @Test
    void legendExplainsEverySymbolInOneLine() {
        String legend = new OverheadGrid.View(new BlockPos(0, 64, 0), 2, new char[5][5]).legend();
        for (String symbol : new String[]{"@", ".", "^", ",", "v", "#", "~", "!", "x", "T", "?"}) {
            assertEquals(true, legend.contains(symbol), "图例要解释符号 " + symbol);
        }
    }

    /** 替身世界：按格记录显式摆放的方块，其余柱子在地面高度铺一层草，足以驱动格子归类。 */
    private static final class FakeWorld implements BlockGetter {

        final Map<BlockPos, BlockState> columns = new HashMap<>();

        @Override
        public BlockState getBlockState(BlockPos pos) {
            BlockState placed = columns.get(pos);
            if (placed != null) {
                return placed;
            }
            // 未显式摆放的格子在 y=64 铺草当地面，其余按空气。
            return pos.getY() == 64 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }
    }
}
