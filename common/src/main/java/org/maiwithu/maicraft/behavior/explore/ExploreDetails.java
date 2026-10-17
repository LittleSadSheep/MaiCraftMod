// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import java.util.List;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryOrigin;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 探索的结果细节：途中的发现、核实到的位置、怎么收的尾、观察统计、
 * 出发点与生效过的扇形、走行段统计。
 *
 * @param findings      途中的发现：种类、目标 id、位置、到访还是远望
 * @param reached       定向探索核实到的位置；没核实不给
 * @param stopReason    怎么收的尾
 * @param coverage      观察统计：观察列数、最远的角色位置与观察距离、未加载边界的样本
 * @param origin        出发点
 * @param sectorHistory 实际生效过的扇形方位（含换出发点与轮换的方位历史），先发生的在前
 * @param legs          走行段统计：几段、失败几段
 */
public record ExploreDetails(List<Item> findings, WorldPosition reached, StopReason stopReason,
        Coverage coverage, WorldPosition origin, List<String> sectorHistory, Legs legs)
        implements ResultDetails {

    /** 怎么收的尾。 */
    public enum StopReason {
        /** 到达探索半径的判定带。 */
        RADIUS_REACHED,
        /** 能走到的外围都走过了，预算耗尽。 */
        FRONTIERS_EXHAUSTED,
        /** 越过了探索半径的硬边界，截停。 */
        LEFT_BOUND,
        /** 受阻：换出发点与轮换方位都试过，还是走不出去。 */
        BLOCKED
    }

    /** 一条发现对外的样子。 */
    public record Item(ExploreFinding.Kind kind, String targetId, WorldPosition position,
            MemoryOrigin origin) {
    }

    /** 观察统计。 */
    public record Coverage(int observedColumns, int maxSelfDistance, int maxSampleDistance,
            int unloadedEdgeSamples) {
    }

    /** 走行段统计。 */
    public record Legs(int total, int failures) {
    }
}
