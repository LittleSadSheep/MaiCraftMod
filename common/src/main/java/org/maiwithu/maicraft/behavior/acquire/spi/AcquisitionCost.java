// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

/**
 * 拿东西的代价：走多远，加上要做几个动作。先用简单的"距离 + 动作数"比较各来源，
 * 以后觉得不够准再换成带风险与工具损耗的估法。
 *
 * @param distanceBlocks 从角色当前位置到来源的大致距离，单位格
 * @param actionCount    做成这件事大概要点多少下、开几次界面、挖几格
 */
public record AcquisitionCost(double distanceBlocks, int actionCount) implements Comparable<AcquisitionCost> {

    /** 一个动作折算成多少格距离：点几下界面的麻烦大约等于走近十来格。 */
    private static final double DISTANCE_PER_ACTION = 10.0;

    public AcquisitionCost {
        if (distanceBlocks < 0) throw new IllegalArgumentException("距离不能为负：" + distanceBlocks);
        if (actionCount < 0) throw new IllegalArgumentException("动作数不能为负：" + actionCount);
    }

    /** 不用走路、也不用动手：东西已经在身上。 */
    public static AcquisitionCost free() {
        return new AcquisitionCost(0, 0);
    }

    /** 折算成同一把尺子的总代价，来源之间比的就是它。 */
    public double total() {
        return distanceBlocks + actionCount * DISTANCE_PER_ACTION;
    }

    @Override public int compareTo(AcquisitionCost other) {
        return Double.compare(total(), other.total());
    }
}
