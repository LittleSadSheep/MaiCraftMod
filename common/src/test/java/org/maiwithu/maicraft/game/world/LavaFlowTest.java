// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 岩浆流路：平地上普通维度流 3 格、下界流 7 格；墙挡得住；落下悬崖后重新满级；
 * 碰到没加载的格、流路大到算不完都说算不清。
 */
class LavaFlowTest {

    private static BlockState stone;
    private static BlockState air;

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        stone = Blocks.STONE.defaultBlockState();
        air = Blocks.AIR.defaultBlockState();
    }

    private final Map<BlockPos, BlockState> world = new HashMap<>();
    private final Set<BlockPos> unloaded = new HashSet<>();

    // 一块 y=63 的石头地，x、z 在 ±half 以内。
    private void floor(int y, int half) {
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) world.put(new BlockPos(x, y, z), stone);
        }
    }

    private Optional<Set<BlockPos>> pour(BlockPos source, boolean hot) {
        return pour(source, hot, source.getY() - 2);
    }

    private Optional<Set<BlockPos>> pour(BlockPos source, boolean hot, int lowestY) {
        return LavaFlow.reach(pos -> !unloaded.contains(pos), pos -> world.getOrDefault(pos, air), source, hot, lowestY);
    }

    @Test
    void 平地上普通维度连源格四格宽_第四格以外流不到() {
        floor(63, 12);
        Set<BlockPos> cells = pour(new BlockPos(0, 64, 0), false).orElseThrow();

        assertTrue(cells.contains(new BlockPos(3, 64, 0)), "源格外第 3 格流得到");
        assertTrue(cells.contains(new BlockPos(2, 64, 1)), "斜着走够三步的也流得到");
        assertFalse(cells.contains(new BlockPos(4, 64, 0)), "第 4 格流不到");
        assertFalse(cells.contains(new BlockPos(2, 64, 2)), "要走四步的流不到");
        assertFalse(cells.contains(new BlockPos(0, 65, 0)), "岩浆不往上流");
        assertEquals(25, cells.size(), "源格加三步以内的菱形");
    }

    @Test
    void 下界流七格() {
        floor(63, 12);
        Set<BlockPos> cells = pour(new BlockPos(0, 64, 0), true).orElseThrow();

        assertTrue(cells.contains(new BlockPos(7, 64, 0)));
        assertFalse(cells.contains(new BlockPos(8, 64, 0)));
    }

    @Test
    void 四面有墙_只剩源格() {
        floor(63, 12);
        for (BlockPos wall : new BlockPos[] {new BlockPos(1, 64, 0), new BlockPos(-1, 64, 0),
                new BlockPos(0, 64, 1), new BlockPos(0, 64, -1)}) {
            world.put(wall, stone);
        }

        assertEquals(Set.of(new BlockPos(0, 64, 0)), pour(new BlockPos(0, 64, 0), false).orElseThrow());
    }

    @Test
    void 流下悬崖_落地后重新满级往外流() {
        // x 小于 2 的地在 63 层，往东是两格高的悬崖，崖底的地在 61 层。
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) world.put(new BlockPos(x, x < 2 ? 63 : 61, z), stone);
        }
        Set<BlockPos> cells = pour(new BlockPos(0, 64, 0), false).orElseThrow();

        assertTrue(cells.contains(new BlockPos(2, 62, 0)), "流到崖边落下去");
        assertTrue(cells.contains(new BlockPos(5, 62, 0)), "崖底落地点满级再流三格");
        assertFalse(cells.contains(new BlockPos(-4, 64, 0)), "崖顶这边照平地流三格");
    }

    @Test
    void 旁边有没加载的格_算不清() {
        floor(63, 12);
        unloaded.add(new BlockPos(2, 64, 0));

        assertTrue(pour(new BlockPos(0, 64, 0), false).isEmpty());
    }

    @Test
    void 下面一直是空的_只算到调用方关心的那一层() {
        // 没有地：岩浆一路往下落。只算到 62 层，再往下不算。
        Set<BlockPos> cells = pour(new BlockPos(0, 64, 0), false).orElseThrow();
        assertTrue(cells.contains(new BlockPos(0, 62, 0)));
        assertFalse(cells.contains(new BlockPos(0, 61, 0)));
    }

    @Test
    void 流路大到算不完_就说算不清() {
        // 悬空的一大片，往下不设底：每落一格都重新满级往外摊，超过能算的格数。
        assertTrue(pour(new BlockPos(0, 64, 0), false, -64).isEmpty());
    }
}
