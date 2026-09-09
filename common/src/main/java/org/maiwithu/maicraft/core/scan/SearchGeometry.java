package org.maiwithu.maicraft.core.scan;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * 为附近搜索提供遍历顺序和“外面不可能再有更近结果”的停止依据。
 * 它只做几何与结果排序，不读世界、加载区块或执行导航。
 */
public final class SearchGeometry {

    /** 一个 chunk 的边长(格)。 */
    private static final int CHUNK = 16;

    private SearchGeometry() {}

    /**
     * section 的访问序:中心所在那一层先看,然后向下、向上交替外扩。
     *
     * <p>找东西的人几乎总在找离自己近的东西,而一列 section 从下往上走会把
     * 脚边那层留到最后——半径覆盖整个高度时,最该先看的一节排在第二十几位。
     * 同距先给下面那层:往下是矿,往上多半是空气。
     *
     * @param centerSectionY 中心所在的 section Y;超出 [min,max] 时夹到边界
     * @return 每一层恰好出现一次的 section Y 序列;区间为空时返回空数组
     */
    // 先查离玩家高度近的区块段，再向下、向上交替展开；输入范围为空就返回空顺序。
    public static int[] sectionOrder(int minSectionY, int maxSectionY, int centerSectionY) {
        if (maxSectionY < minSectionY) {
            return new int[0];
        }
        int n = maxSectionY - minSectionY + 1;
        int[] out = new int[n];
        int centre = Math.clamp(centerSectionY, minSectionY, maxSectionY);
        int at = 0;
        out[at++] = centre;
        for (int d = 1; at < n; d++) {
            if (centre - d >= minSectionY) {
                out[at++] = centre - d;
            }
            if (at < n && centre + d <= maxSectionY) {
                out[at++] = centre + d;
            }
        }
        return out;
    }

    /**
     * 切比雪夫第 {@code ring} 个 chunk 环上,任何方块离中心的<b>最小可能</b>距离。
     *
     * <p>中心在自己 chunk 内的偏移最多 15 格,所以第 m 环最近也有
     * {@code m*16 - 15} 格。这是个下界,不是估计:环序枚举下,它就是"后面还能
     * 挖出多好的结果"的上限。
     */
    // 算某一圈区块中可能出现的最小水平距离，用来判断外圈是否还可能比已找到的结果更近。
    public static double ringFloorDistance(int ring) {
        return ring <= 0 ? 0.0 : (double) ring * CHUNK - (CHUNK - 1);
    }

    /**
     * 走完第 {@code ring} 环后能不能收工:手上已经攒够了,且攒到的最远那个也比
     * 下一环的最近可能距离更近——再走下去不可能换掉其中任何一个。
     *
     * <p>这是精确判据不是启发式,提前收工与走满全程的结果逐字一致。
     */
    public static boolean canStop(int ring, NearestBound bound) {
        return bound.full() && bound.worst() <= ringFloorDistance(ring + 1);
    }

    /**
     * 见过的距离里最近的 {@code want} 个的上界。攒满之前 {@link #worst()} 是正无穷
     * ——"还不够,继续走"。
     *
     * <p>只留 {@code want} 个距离而不是全部命中:判据只关心第 want 近的那个有多远。
     */
    // 只保留所需数量的最近距离；堆顶放其中最远的，便于新距离更小时替换它。
    public static final class NearestBound {

        private final int want;
        /** 留住的 want 个距离,堆顶是其中最远的那个。 */
        private final PriorityQueue<Double> kept;

        public NearestBound(int want) {
            this.want = Math.max(0, want);
            this.kept = new PriorityQueue<>(Math.max(1, this.want), Comparator.reverseOrder());
        }

        /** 记下一个命中的距离。 */
        public void offer(double distance) {
            if (want == 0) {
                return;
            }
            if (kept.size() < want) {
                kept.add(distance);
            } else if (distance < kept.peek()) {
                kept.poll();
                kept.add(distance);
            }
        }

        /** 攒够 {@code want} 个了吗。 */
        public boolean full() {
            return want > 0 && kept.size() >= want;
        }

        /** 留住的这批里最远的那个;没攒够是正无穷。 */
        public double worst() {
            return full() ? kept.peek() : Double.POSITIVE_INFINITY;
        }
    }

    /** Bounded nearest candidates; dense sections must compare every cell before discarding it. */
    // 同样保留最近若干位置，并固定同距离时的坐标顺序；保存坐标副本，避免扫描复用的可变坐标把结果改掉。
    public static final class NearestPositions {
        private final int want;
        private final BlockPos center;
        private final Comparator<BlockPos> order;
        private final PriorityQueue<BlockPos> kept;
        private final Set<BlockPos> excluded;

        public NearestPositions(BlockPos center, int want) {
            this(center, want, Set.of());
        }

        public NearestPositions(BlockPos center, int want, Set<BlockPos> excluded) {
            this.center = center.immutable();
            this.want = Math.max(1, want);
            this.excluded = excluded.stream().map(BlockPos::immutable)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            this.order = Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(this.center))
                    .thenComparingInt(BlockPos::getY)
                    .thenComparingInt(BlockPos::getX)
                    .thenComparingInt(BlockPos::getZ);
            this.kept = new PriorityQueue<>(Math.min(this.want, 64), order.reversed());
        }

        public void offer(BlockPos pos) {
            if (excluded.contains(pos)) return;
            if (kept.size() < want) kept.add(pos.immutable());
            else if (order.compare(pos, kept.peek()) < 0) {
                kept.poll();
                kept.add(pos.immutable());
            }
        }

        // 必须严格近于外圈理论下限才停，保留同距位置参与稳定排序的机会。
        public boolean canStopAfterRing(int ring) {
            double lowerBound = ringFloorDistance(ring + 1);
            // Strict comparison keeps equal-distance tie-breaking independent of ring order.
            return kept.size() >= want && kept.peek().distSqr(center) < lowerBound * lowerBound;
        }

        public List<BlockPos> sorted() {
            List<BlockPos> result = new ArrayList<>(kept);
            result.sort(order);
            return List.copyOf(result);
        }
    }
}
