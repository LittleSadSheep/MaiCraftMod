// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.travel.spi.TravelOffer;
import org.maiwithu.maicraft.behavior.travel.spi.TravelVia;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 行程规划的取舍，纯函数：这一段走路、坐哪种方式，还是哪条路都走不了。
 * 不指定方式时近处（水平 32 格内、高差 6 格内）直接走；远处或走路刚走不通就比报价，挑预计最快的那份
 * （所以同一层有电梯能到目标层时坐电梯，不硬爬楼）。指定了方式就只用它，它给不出报价如实结束，不悄悄改走路；
 * 它已经送过一段、剩下的就是首尾的步行。试过的报价（方式、起点、终点）这一趟不再试。
 */
public final class TravelPlanner {

    /** 近处：水平这么多格内、高差这么多格内直接走。 */
    static final int NEAR_HORIZONTAL = 32;
    static final int NEAR_VERTICAL = 6;
    /** 走路估速：每刻多少格（每秒 4.3 格）。 */
    static final double WALK_BLOCKS_PER_TICK = 4.3 / 20.0;

    private TravelPlanner() {}

    /** 规划的结论。 */
    public sealed interface Plan {
        /** 这一段走路。 */
        record Walk() implements Plan {}

        /** 这一段用这份报价。 */
        record Ride(TravelOffer offer) implements Plan {}

        /** 哪条路都走不了：原因写在问题里。 */
        record DeadEnd(Problem problem) implements Plan {}
    }

    /** 一种方式答不出的原因。 */
    public record Declined(TravelVia via, String reason, boolean unsupported) {}

    /**
     * 挑这一段怎么走。
     *
     * @param via             指定的方式；没指定为 null
     * @param from            角色脚下
     * @param to              目的地中心
     * @param walkFailedHere  从这里走路刚走不通（无路、地形挡住、困住、缺垫料）
     * @param rodeAlready     这一趟已经用指定的方式送过一段
     * @param offers          各方式这一轮给的报价
     * @param declined        各方式这一轮说送不了的原因
     * @param tried           这一趟试过的报价（{@link #key}）
     */
    public static Plan choose(String via, BlockPos from, BlockPos to, boolean walkFailedHere, boolean rodeAlready,
            List<TravelOffer> offers, List<Declined> declined, Set<String> tried) {
        // 试过的不再试；送完离目的地反而不近的不算往前送（电梯往下、车往反方向开）。
        List<TravelOffer> fresh = offers.stream().filter(offer -> !tried.contains(key(offer)))
                .filter(offer -> walkTicks(offer.to(), to) < walkTicks(from, to)).toList();
        if (TravelVia.WALK.name().equals(via)) return new Plan.Walk();
        if (via != null) {
            // 指定的方式送过一段，剩下的就是首尾的步行；还没送过就只看它的报价，给不出就如实结束。
            if (rodeAlready) return new Plan.Walk();
            Optional<TravelOffer> mine = fresh.stream().filter(offer -> offer.via().name().equals(via))
                    .min(Comparator.comparingLong(offer -> total(offer, to)));
            if (mine.isPresent()) return new Plan.Ride(mine.get());
            if (offers.stream().anyMatch(offer -> offer.via().name().equals(via))) {
                return new Plan.DeadEnd(Problem.of(Problem.Kind.UNREACHABLE, "指定的 " + via + " 这一趟能试的都试过了，没送到",
                        "看 legs 里每段停在哪、为什么，换别的方式或不指定 via"));
            }
            Optional<Declined> why = declined.stream().filter(answer -> answer.via().name().equals(via)).findFirst();
            return new Plan.DeadEnd(why.map(answer -> Problem.of(answer.unsupported() ? Problem.Kind.UNSUPPORTED
                            : Problem.Kind.UNREACHABLE, "指定的 " + via + " 送不了：" + answer.reason(),
                            "换别的方式，或不指定 via 让它自己挑"))
                    .orElse(Problem.of(Problem.Kind.UNSUPPORTED, "指定的 " + via + " 这里用不上（没有回答）",
                            "换别的方式，或不指定 via 让它自己挑")));
        }
        // 没指定：近处直接走；远处比"走过去"和"坐这段再走完剩下的"谁快，走路刚走不通就只比别的方式。
        if (near(from, to) && !walkFailedHere) return new Plan.Walk();
        Optional<TravelOffer> fastest = fresh.stream().min(Comparator.comparingLong(offer -> total(offer, to)));
        if (!walkFailedHere && (fastest.isEmpty() || walkTicks(from, to) <= total(fastest.get(), to))) {
            return new Plan.Walk();
        }
        if (fastest.isPresent()) return new Plan.Ride(fastest.get());
        return new Plan.DeadEnd(Problem.of(Problem.Kind.UNREACHABLE, "从这里走不过去，也没有别的方式能送"
                + reasons(declined), null));
    }

    /** 各方式说送不了的原因，接在说明后面；都没说就是空串。 */
    public static String reasons(List<Declined> declined) {
        List<String> reasons = new ArrayList<>();
        for (Declined answer : declined) reasons.add(answer.via().name() + "：" + answer.reason());
        return reasons.isEmpty() ? "" : "（" + String.join("；", reasons) + "）";
    }

    /** 坐这一段再走完剩下的，一共要多少刻。 */
    static long total(TravelOffer offer, BlockPos to) {
        return offer.estimatedTicks() + walkTicks(offer.to(), to);
    }

    /** 近处：水平 32 格内、高差 6 格内。 */
    public static boolean near(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return Math.sqrt(dx * dx + dz * dz) <= NEAR_HORIZONTAL && Math.abs(to.getY() - from.getY()) <= NEAR_VERTICAL;
    }

    /** 走路要多少刻的估计：水平距离加两倍高差，按每秒 4.3 格。 */
    public static long walkTicks(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double length = Math.sqrt(dx * dx + dz * dz) + 2.0 * Math.abs(to.getY() - from.getY());
        return Math.round(length / WALK_BLOCKS_PER_TICK);
    }

    /** 一份报价的身份：方式、起点、终点；试过的这一趟不再试。 */
    public static String key(TravelOffer offer) {
        return offer.via().name() + "|" + offer.from().toShortString() + "|" + offer.to().toShortString();
    }
}
