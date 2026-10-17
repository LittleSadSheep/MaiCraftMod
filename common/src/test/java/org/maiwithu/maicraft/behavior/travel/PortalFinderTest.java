// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import net.minecraft.core.Direction;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 找活门的纯判断：整扇连通识别（含上限与没加载的当墙）、按每列底部挑入口
 * （排除已试、近到远）、门方块还在才算活门。
 */
class PortalFinderTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 一扇 2×3 内门的门格：原点 (4, 64, 4)，横着向东两格、向上三格。 */
    private static Set<Long> aPortal(BlockPos origin) {
        NetherPortalFrame frame = new NetherPortalFrame(origin,
                Direction.Axis.X, 2, 3);
        Set<Long> cells = new HashSet<>();
        for (BlockPos cell : frame.interior()) {
            cells.add(cell.asLong());
        }
        return cells;
    }

    @Test
    void connectedCellsFloodsTheWholePortalButStopsAtUnloadedCells() {
        Map<Long, BlockState> world = new HashMap<>();
        Set<Long> portal = aPortal(new BlockPos(4, 64, 4));
        for (Long key : portal) {
            world.put(key, Blocks.NETHER_PORTAL.defaultBlockState());
        }
        Function<BlockPos, Optional<BlockState>> read = cell ->
                Optional.ofNullable(world.get(cell.asLong()));

        Set<Long> connected = PortalFinder.connectedCells(read, new BlockPos(4, 64, 4), 256);
        assertEquals(portal, connected, "整扇门连成一片，一列不少");

        // 拿走两格（没加载）：洪泛把它们当墙，认出的门格少两格，不冒充读得到的世界。
        world.remove(new BlockPos(5, 64, 4).asLong());
        world.remove(new BlockPos(5, 65, 4).asLong());
        Set<Long> partial = PortalFinder.connectedCells(read, new BlockPos(4, 64, 4), 256);
        assertEquals(portal.size() - 2, partial.size(), "读不到的格不冒充门格");
    }

    @Test
    void connectedCellsRespectsTheSizeLimit() {
        Map<Long, BlockState> world = new HashMap<>();
        for (int y = 60; y < 90; y++) {
            world.put(new BlockPos(0, y, 0).asLong(), Blocks.NETHER_PORTAL.defaultBlockState());
        }
        Function<BlockPos, Optional<BlockState>> read = cell ->
                Optional.ofNullable(world.get(cell.asLong()));
        Set<Long> connected = PortalFinder.connectedCells(read, new BlockPos(0, 70, 0), 10);
        assertEquals(10, connected.size(), "上限拦住失控洪泛");
    }

    @Test
    void entranceColumnsPickTheBottomOfEachColumnNearFirst() {
        Set<Long> portal = aPortal(new BlockPos(4, 64, 4));
        // 两列的底部是 (4,64,4) 与 (5,64,4)；中层门格不是入口。
        List<BlockPos> entrances = PortalFinder.entranceColumns(portal, Set.of(), new BlockPos(0, 64, 0));
        assertEquals(2, entrances.size());
        assertEquals(new BlockPos(4, 64, 4), entrances.get(0), "近的列排前面");
        assertEquals(new BlockPos(5, 64, 4), entrances.get(1));

        // 试过的入口不再挑出来；只剩一列也照样给。
        List<BlockPos> afterAttempt = PortalFinder.entranceColumns(portal,
                Set.of(new BlockPos(4, 64, 4).asLong()), new BlockPos(0, 64, 0));
        assertEquals(List.of(new BlockPos(5, 64, 4)), afterAttempt, "只排除已试的那格");

        // 底下有门格的中层不进候选：高台上的门格没有落脚面。
        assertFalse(afterAttempt.contains(new BlockPos(5, 65, 4)));
    }

    @Test
    void aliveRequiresTheBlockToStillBeThere() {
        Map<Long, BlockState> world = new HashMap<>();
        BlockPos cell = new BlockPos(4, 64, 4);
        Function<BlockPos, Optional<BlockState>> read = c ->
                Optional.ofNullable(world.get(c.asLong()));
        // 读不到（没加载/被拆）不算活门。
        assertFalse(PortalFinder.aliveOf(read, cell, Blocks.NETHER_PORTAL), "读不到没有证据");
        world.put(cell.asLong(), Blocks.OBSIDIAN.defaultBlockState());
        assertFalse(PortalFinder.aliveOf(read, cell, Blocks.NETHER_PORTAL), "别的方块不是活门");
        world.put(cell.asLong(), Blocks.NETHER_PORTAL.defaultBlockState());
        assertTrue(PortalFinder.aliveOf(read, cell, Blocks.NETHER_PORTAL));
    }
}
