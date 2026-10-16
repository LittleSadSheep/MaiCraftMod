// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

/**
 * 拿东西的代价：走多远，加上要做几个动作，再加上动手前还得先去弄的原料折成的格数，再加上动手以后还得站着等多久。
 * 先用简单的"距离 + 动作数 + 缺料 + 等待"比较各来源，以后觉得不够准再换成带风险与工具损耗的估法。
 *
 * @param distanceBlocks  从角色当前位置到来源的大致距离，单位格；"愿意走多远"只比它
 * @param actionCount     做成这件事大概要点多少下、开几次界面、挖几格
 * @param shortfallBlocks 现做的东西身上还缺原料时，先去弄原料的麻烦折成几格（缺一件算一段路）；不缺为 0
 * @param waitTicks       动手以后东西不是立刻到手、还得站着等多久（刻）：AE2 下单等网络做好、熔炉烧、
 *                        库存请求器的包裹在路上；立等可取为 0。只是用来比快慢的估计，实际等多久以现场为准
 */
public record AcquisitionCost(double distanceBlocks, int actionCount, double shortfallBlocks, long waitTicks)
        implements Comparable<AcquisitionCost> {

    /** 一个动作折算成多少格距离：点几下界面的麻烦大约等于走近十来格。 */
    private static final double DISTANCE_PER_ACTION = 10.0;
    /**
     * 站着等一刻折算成多少格距离：和走路用同一把尺子，走路每秒约 4.3 格，站着等一秒就和走四格多一样耗时间。
     * 等的时候主任务干不了别的（最多趁空吃一口），所以不打折——AE2 慢慢做 44 块铁板要等二三十秒，
     * 就该和走一百来格去箱子里拿比，而不是当成和开一次终端一样快。
     */
    static final double BLOCKS_PER_WAITED_TICK = 4.3 / 20;

    public AcquisitionCost {
        if (distanceBlocks < 0) throw new IllegalArgumentException("距离不能为负：" + distanceBlocks);
        if (actionCount < 0) throw new IllegalArgumentException("动作数不能为负：" + actionCount);
        if (shortfallBlocks < 0) throw new IllegalArgumentException("缺料折算不能为负：" + shortfallBlocks);
        if (waitTicks < 0) throw new IllegalArgumentException("等待时长不能为负：" + waitTicks);
    }

    /** 立等可取的代价：走多远、做几个动作、缺料折几格。 */
    public AcquisitionCost(double distanceBlocks, int actionCount, double shortfallBlocks) {
        this(distanceBlocks, actionCount, shortfallBlocks, 0);
    }

    /** 不缺原料、立等可取的代价：走多远加做几个动作。 */
    public AcquisitionCost(double distanceBlocks, int actionCount) {
        this(distanceBlocks, actionCount, 0);
    }

    /** 不用走路、也不用动手：东西已经在身上。 */
    public static AcquisitionCost free() {
        return new AcquisitionCost(0, 0);
    }

    /** 同样走这么远、做这么多动作，动手以后还得等 ticks 刻东西才到手。 */
    public AcquisitionCost withWait(long ticks) {
        return new AcquisitionCost(distanceBlocks, actionCount, shortfallBlocks, ticks);
    }

    /** 折算成同一把尺子的总代价，来源之间比的就是它。 */
    public double total() {
        return distanceBlocks + actionCount * DISTANCE_PER_ACTION + shortfallBlocks + waitTicks * BLOCKS_PER_WAITED_TICK;
    }

    @Override public int compareTo(AcquisitionCost other) {
        return Double.compare(total(), other.total());
    }
}
