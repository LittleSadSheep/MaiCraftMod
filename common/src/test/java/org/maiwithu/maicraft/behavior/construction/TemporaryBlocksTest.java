// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 临时方块：从目标旁边找一条接到地面的链，范围与长度有上限；收回先收远的再沿柱下撤，拆前看下一格落不落得稳。 */
class TemporaryBlocksTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 替身世界：没摆的格是空气（已加载），摆了的格给状态；可以指定哪些格没加载。 */
    private static final class FakeWorld implements ReadsBlocks {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();
        private final Set<BlockPos> unloaded = new HashSet<>();

        FakeWorld put(BlockPos pos, BlockState state) {
            blocks.put(pos.immutable(), state);
            return this;
        }

        FakeWorld unload(BlockPos pos) {
            unloaded.add(pos.immutable());
            return this;
        }

        @Override public boolean loaded(BlockPos pos) {
            return !unloaded.contains(pos);
        }

        @Override public BlockState state(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
    }

    @Test
    void 从目标下方找到地面_链从贴地那块开始() {
        BlockPos target = new BlockPos(0, 70, 0);
        FakeWorld world = new FakeWorld().put(new BlockPos(0, 66, 0), Blocks.STONE.defaultBlockState());
        List<BlockPos> chain = TemporaryBlocks.chain(world, target, pos -> true, pos -> true);
        assertEquals(List.of(new BlockPos(0, 67, 0), new BlockPos(0, 68, 0), new BlockPos(0, 69, 0)), chain, "贴地的先，贴目标的最后");
    }

    @Test
    void 贴目标的那块要能当点击面_不许放的格绕开() {
        BlockPos target = new BlockPos(0, 70, 0);
        FakeWorld world = new FakeWorld().put(new BlockPos(0, 68, 0), Blocks.STONE.defaultBlockState())
                .put(new BlockPos(1, 69, 0), Blocks.STONE.defaultBlockState());
        List<BlockPos> below = TemporaryBlocks.chain(world, target, pos -> true, pos -> pos.getY() < target.getY());
        assertEquals(List.of(new BlockPos(0, 69, 0)), below);
        List<BlockPos> side = TemporaryBlocks.chain(world, target, pos -> !pos.equals(new BlockPos(0, 69, 0)), pos -> true);
        assertFalse(side.isEmpty(), "下面不许放时从旁边接");
        assertTrue(side.stream().noneMatch(pos -> pos.equals(new BlockPos(0, 69, 0))));
    }

    @Test
    void 太远或太长就放弃() {
        BlockPos target = new BlockPos(0, 100, 0);
        FakeWorld far = new FakeWorld().put(new BlockPos(0, 60, 0), Blocks.STONE.defaultBlockState());
        assertTrue(TemporaryBlocks.chain(far, target, pos -> true, pos -> true).isEmpty(), "向下只找二十四格");
        FakeWorld nothing = new FakeWorld();
        assertTrue(TemporaryBlocks.chain(nothing, target, pos -> true, pos -> true).isEmpty(), "周围什么都没有");
        FakeWorld unloaded = new FakeWorld().put(new BlockPos(0, 96, 0), Blocks.STONE.defaultBlockState()).unload(new BlockPos(0, 97, 0));
        assertTrue(TemporaryBlocks.chain(unloaded, target, pos -> true, pos -> true).stream().noneMatch(pos -> pos.equals(new BlockPos(0, 97, 0))),
                "没加载的格不进链");
    }

    @Test
    void 收回顺序_先远的再沿柱下撤() {
        BlockPos feet = new BlockPos(0, 70, 0);
        Set<BlockPos> placed = Set.of(new BlockPos(0, 69, 0), new BlockPos(0, 68, 0), new BlockPos(3, 69, 0), new BlockPos(1, 69, 0));
        assertEquals(List.of(new BlockPos(3, 69, 0), new BlockPos(1, 69, 0), new BlockPos(0, 69, 0), new BlockPos(0, 68, 0)),
                TemporaryBlocks.removalOrder(placed, feet));
    }

    @Test
    void 拆脚下之前看下一格落得稳() {
        FakeWorld world = new FakeWorld().put(new BlockPos(0, 68, 0), Blocks.STONE.defaultBlockState());
        assertTrue(TemporaryBlocks.safeToDescend(world, new BlockPos(0, 69, 0)), "下面紧挨着实心");
        FakeWorld deep = new FakeWorld().put(new BlockPos(0, 60, 0), Blocks.STONE.defaultBlockState());
        assertFalse(TemporaryBlocks.safeToDescend(deep, new BlockPos(0, 69, 0)), "太深会摔");
        FakeWorld water = new FakeWorld().put(new BlockPos(0, 68, 0), Blocks.WATER.defaultBlockState());
        assertFalse(TemporaryBlocks.safeToDescend(water, new BlockPos(0, 69, 0)), "掉进水里不算稳");
        assertFalse(TemporaryBlocks.safeToDescend(new FakeWorld().unload(new BlockPos(0, 68, 0)), new BlockPos(0, 69, 0)));
    }

    @Test
    void 暂时做不了的格轮到最后() {
        assertEquals(List.of("c", "a"), TemporaryBlocks.defer(List.of("a", "b", "c"), 1, item -> !item.equals("b")));
    }
}
