// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

/**
 * 拿东西的代价：走多远，加上要做几个动作，再加上动手前还得先去弄的原料折成的格数。
 * 先用简单的"距离 + 动作数 + 缺料"比较各来源，以后觉得不够准再换成带风险与工具损耗的估法。
 *
 * @param distanceBlocks  从角色当前位置到来源的大致距离，单位格；"愿意走多远"只比它
 * @param actionCount     做成这件事大概要点多少下、开几次界面、挖几格
 * @param shortfallBlocks 现做的东西身上还缺原料时，先去弄原料的麻烦折成几格（缺一件算一段路）；不缺为 0
 */
public record AcquisitionCost(double distanceBlocks, int actionCount, double shortfallBlocks)
        implements Comparable<AcquisitionCost> {

    /** 一个动作折算成多少格距离：点几下界面的麻烦大约等于走近十来格。 */
    private static final double DISTANCE_PER_ACTION = 10.0;

    public AcquisitionCost {
        if (distanceBlocks < 0) throw new IllegalArgumentException("距离不能为负：" + distanceBlocks);
        if (actionCount < 0) throw new IllegalArgumentException("动作数不能为负：" + actionCount);
        if (shortfallBlocks < 0) throw new IllegalArgumentException("缺料折算不能为负：" + shortfallBlocks);
    }

    /** 不缺原料的代价：走多远加做几个动作。 */
    public AcquisitionCost(double distanceBlocks, int actionCount) {
        this(distanceBlocks, actionCount, 0);
    }

    /** 不用走路、也不用动手：东西已经在身上。 */
    public static AcquisitionCost free() {
        return new AcquisitionCost(0, 0);
    }

    /** 折算成同一把尺子的总代价，来源之间比的就是它。 */
    public double total() {
        return distanceBlocks + actionCount * DISTANCE_PER_ACTION + shortfallBlocks;
    }

    @Override public int compareTo(AcquisitionCost other) {
        return Double.compare(total(), other.total());
    }
}
