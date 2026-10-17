// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * 观察的账：抽样网格与游标（以当前位置为圆心逐列抽样，每刻一批，跑完为止）、
 * 看过的列（按绝对位置去重）、已试的落点、最远的身体与观察距离、未加载边界的样本数，
 * 以及留意清单的结构与实体多久扫一轮。数值初值按实机观感调整。
 */
final class ObservationSampling {

    /** 观察圈半径（格）。 */
    static final int OBSERVE_RADIUS = 112;
    /** 抽样网格步长（格）。 */
    static final int COLUMN_STEP = 8;
    /** 每刻抽样的列数。 */
    static final int COLUMNS_PER_TICK = 16;
    /** 留意清单的结构与实体多少刻扫一轮。 */
    static final int INTEREST_SCAN_INTERVAL = 40;

    final List<int[]> offsets = samplingOffsets();
    final Set<Long> observed = new HashSet<>();
    /** 已试过或已证实走不通的落点：周围 16 格内的航点不再选。 */
    final Set<Long> triedColumns = new HashSet<>();
    int cursor;
    int uniqueColumns;
    int unloadedEdges;
    int sinceInterestScan;
    double maxSelfDistance;
    double maxSampleDistance;

    /** 以出发点为圆心、观察圈半径内的抽样网格，由近到远。 */
    private static List<int[]> samplingOffsets() {
        List<int[]> cells = new ArrayList<>();
        for (int dx = -OBSERVE_RADIUS; dx <= OBSERVE_RADIUS; dx += COLUMN_STEP) {
            for (int dz = -OBSERVE_RADIUS; dz <= OBSERVE_RADIUS; dz += COLUMN_STEP) {
                if ((double) dx * dx + dz * dz <= (double) OBSERVE_RADIUS * OBSERVE_RADIUS) {
                    cells.add(new int[]{dx, dz});
                }
            }
        }
        cells.sort((first, second) -> Double.compare(
                (double) first[0] * first[0] + first[1] * first[1],
                (double) second[0] * second[0] + second[1] * second[1]));
        return cells;
    }

    /** 换观察圈：身体到了新的地方，从头把这一片看完（看过的列按绝对位置去重）。 */
    void reset() {
        cursor = 0;
    }

    boolean finished() {
        return cursor >= offsets.size();
    }

    /** 取下一批要抽样的绝对列。 */
    List<int[]> nextBatch(BlockPos center) {
        List<int[]> batch = new ArrayList<>();
        while (batch.size() < COLUMNS_PER_TICK && cursor < offsets.size()) {
            int[] offset = offsets.get(cursor++);
            batch.add(new int[]{center.getX() + offset[0], center.getZ() + offset[1]});
        }
        return batch;
    }

    /** 记一列看过了；返回是不是新的。 */
    boolean noteColumn(int x, int z, double distance) {
        if (observed.add(BlockPos.asLong(x, 0, z))) {
            uniqueColumns++;
            maxSampleDistance = Math.max(maxSampleDistance, distance);
            return true;
        }
        return false;
    }

    void noteSelfDistance(double distance) {
        maxSelfDistance = Math.max(maxSelfDistance, distance);
    }
}
