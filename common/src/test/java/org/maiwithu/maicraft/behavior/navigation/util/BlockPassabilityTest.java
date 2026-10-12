// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.util;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 寻路共用的方块通行判断里，传送门内格不能当普通可走格（实机 198：出发点紧邻下界门时
 * 路线穿门抄近路，身体被原版机制传到另一维度）；走向点名要进的门格由寻路的目标编译放行，
 * 那条放行名单的测试在寻路目标一侧，这里守的是"默认一律不可走、不可站"的底。
 */
class BlockPassabilityTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void portalCellsAreNeverWalkableByDefault() {
        Grid world = new Grid();
        world.put(new BlockPos(3, 64, 3), Blocks.NETHER_PORTAL.defaultBlockState());
        world.put(new BlockPos(6, 64, 3), Blocks.END_PORTAL.defaultBlockState());
        world.put(new BlockPos(9, 64, 3), Blocks.STONE.defaultBlockState());

        assertFalse(BlockPassability.canWalkThrough(world, new BlockPos(3, 64, 3)),
                "下界门内格默认不可走：走进去会被传去另一维度");
        assertFalse(BlockPassability.canWalkThrough(world, new BlockPos(6, 64, 3)),
                "末地门格同样默认不可走");
        assertFalse(BlockPassability.isStandable(world, new BlockPos(3, 64, 3)),
                "门格不是能站的格子：不做站立候选");
        assertFalse(BlockPassability.canWalkThrough(world, new BlockPos(9, 64, 3)),
                "石头当然不可穿，判断没有顺手放空");
        assertTrue(BlockPassability.canWalkThrough(world, new BlockPos(9, 65, 3)),
                "门格上方与普通空气的判定不受影响");
    }

    @Test
    void fireStaysBlockedWhileNetherPortalJoinedTheList() {
        Grid world = new Grid();
        world.put(new BlockPos(3, 64, 3), Blocks.FIRE.defaultBlockState());
        assertFalse(BlockPassability.canWalkThrough(world, new BlockPos(3, 64, 3)),
                "原有的危险方块拦截没有被门格改动挤掉");
    }

    /** 小片世界替身：按坐标记方块，没记的当空气。 */
    @Test
    void standingOnTopOfALowBlockCountsTheCellAbove() {
        // 站在箱子、附魔台顶上：脚位是上面那一格，寻路才有起点（原先读成方块里面那一格，人下不来）。
        Grid world = new Grid();
        world.put(new BlockPos(3, 64, 3), Blocks.CHEST.defaultBlockState());
        world.put(new BlockPos(6, 64, 3), Blocks.ENCHANTING_TABLE.defaultBlockState());
        world.put(new BlockPos(9, 64, 3), Blocks.WHITE_CARPET.defaultBlockState());
        assertEquals(new BlockPos(3, 65, 3), BlockPassability.playerFeet(world, 3.5, 64.8749999, 3.5),
                "箱子顶（0.875 高，坐标差一点点也认）");
        assertEquals(new BlockPos(6, 65, 3), BlockPassability.playerFeet(world, 6.5, 64.75, 3.5), "附魔台顶（0.75 高）");
        assertEquals(new BlockPos(9, 64, 3), BlockPassability.playerFeet(world, 9.5, 64.0625, 3.5),
                "地毯不到半格：人还在地毯这一格");
    }

    private static final class Grid implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void put(BlockPos pos, BlockState state) {
            blocks.put(pos.immutable(), state);
        }

        BlockState at(BlockPos pos) {
            return blocks.getOrDefault(pos.immutable(), Blocks.AIR.defaultBlockState());
        }

        @Override public BlockState getBlockState(BlockPos pos) {
            return at(pos);
        }

        @Override public FluidState getFluidState(BlockPos pos) {
            return at(pos).getFluidState();
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override public int getHeight() {
            return 384;
        }

        @Override public int getMinBuildHeight() {
            return -64;
        }
    }
}
