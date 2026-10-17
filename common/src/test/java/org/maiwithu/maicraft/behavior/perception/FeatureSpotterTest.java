// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 地形特征聚类：成片的水、树、落差各聚成一处，孤格不报，中心落在这一片的平均位置。 */
class FeatureSpotterTest {

    private static OverheadGrid.View view(char[][] cells) {
        return new OverheadGrid.View(new BlockPos(100, 64, 200), cells.length / 2, cells);
    }

    @Test
    void waterAndTreeClustersAreSpottedSeparately() {
        // 5x5 网格：左上两格水，右侧三格树，其余平地。
        char[][] cells = {
                {'~', '~', '.', 'T', '.'},
                {'~', '.', '.', 'T', '.'},
                {'.', '.', '.', 'T', '.'},
                {'.', '.', '.', '.', '.'},
                {'.', '.', '.', '.', '.'}};
        List<FeatureSpotter.Marked> found = FeatureSpotter.spot(view(cells));
        assertEquals(2, found.size());
        assertTrue(found.stream().anyMatch(m -> "水体".equals(m.kind()) && m.cells() == 3));
        assertTrue(found.stream().anyMatch(m -> "树林".equals(m.kind()) && m.cells() == 3));
    }

    @Test
    void singleCellsAreNotReported() {
        char[][] cells = {
                {'.', '.', '.'},
                {'.', '~', '.'},
                {'.', '.', '.'}};
        assertEquals(List.of(), FeatureSpotter.spot(view(cells)), "一格水洼不值得占一个观察编号");
    }

    @Test
    void clusterCenterLandsOnItsAverageCell() {
        char[][] cells = {
                {'T', 'T', '.'},
                {'.', '.', '.'},
                {'.', '.', '.'}};
        WorldPosition center = FeatureSpotter.spot(view(cells)).getFirst().center();
        // 网格中心世界坐标 (100, 200)，半径 1：两格树在顶部一行，平均后中心落在北偏一格。
        assertEquals(100, center.x());
        assertEquals(199, center.z());
    }
}
