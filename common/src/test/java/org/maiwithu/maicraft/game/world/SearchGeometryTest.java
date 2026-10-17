// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 无论扫描顺序如何，稠密和稀疏地形都必须返回相同的最近目标格。 */
class SearchGeometryTest {

    @Test
    void denseScanReturnsNearestCellsNotIterationFirstCells() {
        BlockPos center = new BlockPos(15, 79, -1);
        List<BlockPos> cells = new ArrayList<>();
        for (int y = 64; y < 80; y++) {
            for (int z = -16; z < 0; z++) {
                for (int x = 0; x < 32; x++) cells.add(new BlockPos(x, y, z));
            }
        }
        Comparator<BlockPos> order = Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(center))
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ);
        List<BlockPos> expected = cells.stream().sorted(order).limit(12).toList();
        SearchGeometry.NearestPositions nearest = new SearchGeometry.NearestPositions(center, 12);
        cells.forEach(nearest::offer);
        assertEquals(expected, nearest.sorted(), "稠密扫描必须返回最近的格子，而不是遍历顺序在前的格子");
    }

    @Test
    void nearestCellsDoNotDependOnVisitOrderOrRetainMutablePositions() {
        BlockPos center = new BlockPos(15, 79, -1);
        List<BlockPos> cells = new ArrayList<>();
        for (int y = 64; y < 80; y++) {
            for (int z = -16; z < 0; z++) {
                for (int x = 0; x < 32; x++) cells.add(new BlockPos(x, y, z));
            }
        }
        Comparator<BlockPos> order = Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(center))
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ);
        List<BlockPos> expected = cells.stream().sorted(order).limit(12).toList();
        Collections.shuffle(cells, new Random(5));
        SearchGeometry.NearestPositions shuffled = new SearchGeometry.NearestPositions(center, 12);
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (BlockPos cell : cells) shuffled.offer(mutable.set(cell));
        mutable.set(1_000, 1_000, 1_000);
        assertEquals(expected, shuffled.sorted(), "最近格子不能依赖访问顺序，也不能保留可变位置");
    }

    @Test
    void equalDistanceCandidatesInNextRingAreStillConsidered() {
        SearchGeometry.NearestPositions boundary = new SearchGeometry.NearestPositions(BlockPos.ZERO, 1);
        boundary.offer(new BlockPos(1, 0, 0));
        assertFalse(boundary.canStopAfterRing(0), "下一环还有同距候选时不能提前收工");
        boundary.offer(BlockPos.ZERO);
        assertTrue(boundary.canStopAfterRing(0), "已有零距离候选时可以收工");
    }

    @Test
    void exhaustedNearestWindowDoesNotHideNextUsableTargets() {
        // 旧窗口中的候选即使全部单独被拒绝，也不能遮住更远处可用的目标。
        var rejected = new HashSet<BlockPos>();
        for (int x = 1; x <= 64; x++) rejected.add(new BlockPos(x, 0, 0));
        var usable = new SearchGeometry.NearestPositions(BlockPos.ZERO, 64, rejected);
        rejected.clear(); // 目标选择器会在各刻之间持有自己的排除快照。
        for (int x = 128; x >= 1; x--) usable.offer(new BlockPos(x, 0, 0));
        assertEquals(64, usable.sorted().size(), "排除窗口之外的目标必须保留");
        assertEquals(new BlockPos(65, 0, 0), usable.sorted().getFirst(), "被排除的近处不能占住最近位置");
    }
}
