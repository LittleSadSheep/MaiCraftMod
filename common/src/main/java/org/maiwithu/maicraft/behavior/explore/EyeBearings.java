// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import java.util.Optional;

import org.maiwithu.maicraft.behavior.perception.DirectionWords;

/**
 * 末影之眼指路的判断：这一扔说明了什么——要塞就在脚下、两次飞向交在哪，还是接着往眼指的方向走多远。
 * 纯计算，不读世界；扔、捡、走都在 {@link EnderEyeSearch}。
 *
 * <p>像真人一样：扔一只看它往哪飞，沿着走一段再扔；眼往下钻说明要塞起点就在脚下十二格内；
 * 两次飞向夹出明显的角度，交点就是要塞；走过头时眼会掉头往回指，往回走半段再扔。
 * 离得远时两次飞向几乎平行，交点差之千里不作数，接着走；越走越稳就把一段放长，开始偏了就收短；
 * 走过头一次以后要塞就夹在走过的路上，之后每段都收短一半，来回夹到眼往下钻为止。
 */
public final class EyeBearings {

    /** 眼最后的位置比飞起时低这么多（格）就是往下钻：离要塞起点不到十二格时，眼直接朝它往地下飞。 */
    static final double DIVE_DROP = 2.0;
    /** 横着挪了这么远（格）才读得出方向；离要塞远时眼总是横飞十二格。 */
    static final double MIN_DRIFT = 2.0;
    /** 两次飞向夹角不到这么多度几乎平行，交点差得太远不作数。 */
    static final double MIN_CROSS_DEGREES = 12;
    /** 夹角超过这么多度是掉头了（走过头），两条线几乎重合，交不出一个点。 */
    static final double MAX_CROSS_DEGREES = 168;
    /** 交点离后一次出手点超过这么远（格）不作数：远处的交点误差大，先接着走近再扔，也让去末地时近处就查得到这条线索。 */
    static final double MAX_CROSS_REACH = 128;
    /** 两次飞向差不到这么多度算一路指着同一个方向：把下一段放长。 */
    static final double STEADY_DEGREES = 5;
    /** 第一段走多远（格）。 */
    public static final int FIRST_LEG = 80;
    /** 一段最短、最长走多远（格）：几十到一两百格。 */
    static final int MIN_LEG = 24;
    static final int MAX_LEG = 160;

    private EyeBearings() {}

    /** 这一扔说明了什么。 */
    public sealed interface Verdict {
        /** 找到了：要塞大概在 (x, z)，{@code how} 是凭什么说的。 */
        record Found(int x, int z, String how) implements Verdict {}

        /**
         * 接着往眼指的方向走：水平单位方向 (dx, dz)，这一段走多远（格）。
         *
         * @param overshot 走过头过（眼掉过头）：要塞已经夹在走过的路上，之后每段都收短
         */
        record Walk(double dx, double dz, int leg, boolean overshot) implements Verdict {}

        /** 眼几乎没横着挪也没往下钻，读不出方向：从原地再扔一只。 */
        record Unreadable() implements Verdict {}
    }

    /**
     * 判断最新这一扔：先看往下钻，再看和上一扔交不交，最后定下一段往哪走多远。
     *
     * @param earlier  上一次读得出方向的那一扔；这是第一扔时为 null
     * @param latest   刚扔的这一只
     * @param overshot 之前走过头过（上一个判断给的）
     */
    public static Verdict judge(EyeFlight earlier, EyeFlight latest, boolean overshot) {
        if (dives(latest)) {
            return new Verdict.Found((int) Math.floor(latest.toX()), (int) Math.floor(latest.toZ()),
                    "眼往下钻，要塞就在脚下附近");
        }
        Optional<double[]> heading = heading(latest);
        if (heading.isEmpty()) {
            return new Verdict.Unreadable();
        }
        double[] now = heading.get();
        Optional<double[]> before = earlier == null || dives(earlier) ? Optional.empty() : heading(earlier);
        if (before.isEmpty()) {
            return new Verdict.Walk(now[0], now[1], overshot ? MIN_LEG : FIRST_LEG, overshot);
        }
        double turn = turnDegrees(before.get(), now);
        Optional<Verdict.Found> crossed = crossing(earlier, before.get(), latest, now, turn);
        if (crossed.isPresent()) {
            return crossed.get();
        }
        double baseline = Math.hypot(latest.fromX() - earlier.fromX(), latest.fromZ() - earlier.fromZ());
        // 掉头了而两次出手点只隔最短一段：要塞就夹在两次出手点之间，再扔也只是来回指，取中点收工。
        if (turn > MAX_CROSS_DEGREES && baseline <= MIN_LEG * 1.5) {
            return new Verdict.Found((int) Math.floor((earlier.fromX() + latest.fromX()) / 2),
                    (int) Math.floor((earlier.fromZ() + latest.fromZ()) / 2), "走过头眼又往回指，要塞在两次出手点之间");
        }
        boolean bracketed = overshot || turn > 90;
        return new Verdict.Walk(now[0], now[1], nextLeg(baseline, turn, bracketed), bracketed);
    }

    /** 这一扔往下钻了：最后看到它时比飞起时低出好几格。 */
    public static boolean dives(EyeFlight flight) {
        return flight.toY() < flight.fromY() - DIVE_DROP;
    }

    /** 水平飞向的单位向量 {dx, dz}；横着挪得太少读不出方向时为空。 */
    static Optional<double[]> heading(EyeFlight flight) {
        double dx = flight.toX() - flight.fromX();
        double dz = flight.toZ() - flight.fromZ();
        double drift = Math.hypot(dx, dz);
        return drift < MIN_DRIFT ? Optional.empty() : Optional.of(new double[] {dx / drift, dz / drift});
    }

    /** 飞向的东南西北说法，给结果与进展念。 */
    public static String compassOf(EyeFlight flight) {
        return DirectionWords.compassOf(flight.toX() - flight.fromX(), flight.toZ() - flight.fromZ());
    }

    // 两个单位方向的夹角（度）：0 是同一个方向，180 是掉头。
    static double turnDegrees(double[] first, double[] second) {
        double dot = Math.max(-1, Math.min(1, first[0] * second[0] + first[1] * second[1]));
        return Math.toDegrees(Math.acos(dot));
    }

    // 两条飞向线交在哪：夹角要够大、交点要在两次出手点的前方、离后一次出手点不太远，才算交出了要塞的位置。
    private static Optional<Verdict.Found> crossing(EyeFlight earlier, double[] a, EyeFlight latest, double[] b,
            double turn) {
        if (turn < MIN_CROSS_DEGREES || turn > MAX_CROSS_DEGREES) {
            return Optional.empty();
        }
        double denominator = a[0] * b[1] - a[1] * b[0];
        double gapX = latest.fromX() - earlier.fromX();
        double gapZ = latest.fromZ() - earlier.fromZ();
        double alongEarlier = (gapX * b[1] - gapZ * b[0]) / denominator;
        double alongLatest = (gapX * a[1] - gapZ * a[0]) / denominator;
        if (alongEarlier <= 0 || alongLatest <= 0 || alongLatest > MAX_CROSS_REACH) {
            return Optional.empty();
        }
        return Optional.of(new Verdict.Found((int) Math.floor(earlier.fromX() + a[0] * alongEarlier),
                (int) Math.floor(earlier.fromZ() + a[1] * alongEarlier), "两次飞向交在这里"));
    }

    // 下一段走多远：一路指着同一个方向说明还远，放长一倍；开始偏、掉头了、或走过头过说明近了，收短一半。
    static int nextLeg(double baseline, double turn, boolean overshot) {
        int walked = (int) Math.round(baseline);
        if (turn < STEADY_DEGREES && !overshot) {
            return Math.min(MAX_LEG, Math.max(FIRST_LEG, walked * 2));
        }
        return Math.max(MIN_LEG, walked / 2);
    }
}
