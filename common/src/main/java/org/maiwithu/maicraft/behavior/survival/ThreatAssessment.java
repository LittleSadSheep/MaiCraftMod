// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 威胁评估：读双方与处境，回答"打得过打不过"的纯函数，全仓只有这一份。
 *
 * <p>结论只有三种：能赢、有风险、打不过。它不回答"想不想打"——那由任务的许可表达；
 * 被攻击时还手还是撤离、床边清怪还是换地方、夜里躲不躲，都拿这个结论当输入。
 *
 * <p>我方血量按护甲折算成有效血量再比：满铁甲的 20 颗心和裸奔的 20 颗心不是一回事。
 * 对方按数量、种类（近战、远程、爆炸、飞行）、是否正在追、有没有点着引信累计威胁；
 * 空着手对面还会还手的，不打。退路没有了改判背水一战——退不掉就只好打。
 */
public final class ThreatAssessment {

    /** 评估结论。 */
    public enum Verdict {
        /** 能赢：正常打。 */
        WINNABLE,
        /** 有风险：打，但保持在远程距离带，别硬凑近战。 */
        RISKY,
        /** 打不过：不开打；撤离也撤不掉时按背水一战处理。 */
        OUTMATCHED
    }

    /** 对方一只威胁的种类。 */
    public enum Kind {
        /** 走过来打人的：僵尸这类。 */
        MELEE,
        /** 远程攻击的：骷髅这类。 */
        RANGED,
        /** 会爆炸的：苦力怕；点着引信时威胁最大。 */
        EXPLOSIVE,
        /** 飞着的：恶魂、幻翼这类，近战常够不着。 */
        FLYING
    }

    /** 我方处境：血量、护甲点数、手里的武器分、带的食物份数、有没有退路。 */
    public record MySide(double health, int armorPoints, int weaponScore, int foodCount, boolean hasEscapeRoute) {}

    /** 对方一只威胁：距离（格）、种类、是否正在追我、引信是否已点（爆炸类）。 */
    public record Foe(double distance, Kind kind, boolean chasingMe, boolean armed) {}

    /** 护甲最多按 80% 减伤折算（原版 20 点护甲的上限）；再高的护甲值不多折。 */
    private static final double MAX_ARMOR_REDUCTION = 0.8;

    /** 拒战线：折算后的有效血量低于它就撤（约两颗心的有效血量，挨一下就没了）。 */
    public static final double RETREAT_LINE_EFFECTIVE_HEALTH = 8.0;

    /** 逃跑判定距离：所有参与追击的都在它之外才算甩掉；必须远大于警戒半径。 */
    public static final double FLEE_CLEAR_DISTANCE = 32.0;

    /** 自卫警戒半径：被攻击、锁定、点引信的威胁在这个范围内就构成"正在被威胁"。 */
    public static final double VIGILANCE_RADIUS = 14.0;

    /** 苦力怕的提前警戒：这个距离内看得见就先动手，等它开始膨胀再反应就来不及躲。 */
    public static final double CREEPER_ALERT_RADIUS = 8.0;

    /** 有没有退路的判定：撤离寻路连续失败这么多次才算退无可退。 */
    public static final int ESCAPE_FAILURES_BEFORE_LAST_STAND = 3;

    /**
     * 评估一场仗：给我方处境与对方的威胁列表，给结论与差在哪。
     *
     * @return 结论；打不过时附上差在哪（血、武器、数量），供失败信息与事件说清楚
     */
    public static Assessment assess(MySide mine, List<Foe> foes) {
        double effective = effectiveHealth(mine.health(), mine.armorPoints());
        double threatWeight = 0;
        boolean someoneWouldFightBack = false;
        boolean armedExplosiveNear = false;
        for (Foe foe : foes) {
            threatWeight += weightOf(foe);
            if (foe.kind() != Kind.EXPLOSIVE || foe.armed()) {
                // 爆炸类没点引信还只是个站着的桶；其余的都会还手或已经动手。
                someoneWouldFightBack = true;
            }
            if (foe.kind() == Kind.EXPLOSIVE && foe.armed()) {
                armedExplosiveNear = true;
            }
        }
        if (threatWeight == 0) {
            return new Assessment(Verdict.WINNABLE, List.of());
        }
        // 空着手对面还会还手：不主动凑上去；退无可退才背水一战。
        if (mine.weaponScore() <= 0 && someoneWouldFightBack) {
            if (!mine.hasEscapeRoute()) {
                return new Assessment(Verdict.RISKY, List.of("退无可退，空手背水一战"));
            }
            return new Assessment(Verdict.OUTMATCHED, List.of("手里没有任何武器，对面还会还手"));
        }
        double strength = effective / 8.0 + mine.weaponScore() / 2.0 + Math.min(mine.foodCount(), 4) * 0.25;
        if (strength < threatWeight) {
            List<String> gaps = new ArrayList<>();
            if (effective < RETREAT_LINE_EFFECTIVE_HEALTH * 2) gaps.add("血太少");
            if (mine.weaponScore() <= 2) gaps.add("武器太弱");
            if (threatWeight >= 3) gaps.add("对方数量或危险太多");
            if (!mine.hasEscapeRoute()) {
                return new Assessment(Verdict.RISKY, gaps.isEmpty() ? List.of("退无可退，只好背水一战") : gaps);
            }
            return new Assessment(Verdict.OUTMATCHED, gaps);
        }
        if (effective < RETREAT_LINE_EFFECTIVE_HEALTH || armedExplosiveNear || threatWeight >= strength * 0.7) {
            return new Assessment(Verdict.RISKY, List.of());
        }
        return new Assessment(Verdict.WINNABLE, List.of());
    }

    /**
     * 有效血量：血量按护甲减伤折算。代表性一击先被护甲按比例吃掉，剩下的才从血里扣，
     * 所以"还能挨几下"按折算后的量比。
     */
    public static double effectiveHealth(double health, int armorPoints) {
        double reduction = Math.min(armorPoints * 0.04, MAX_ARMOR_REDUCTION);
        return health / (1.0 - reduction);
    }

    /** 此刻要不要撤：折算后的有效血量跌到拒战线之下。护甲碎掉会让同一条血更快跌破这条线。 */
    public static boolean belowRetreatLine(MySide mine) {
        return effectiveHealth(mine.health(), mine.armorPoints()) < RETREAT_LINE_EFFECTIVE_HEALTH;
    }

    /** 一只威胁的分量：种类不同挨的打不一样，正在追的、点了引信的更要命。 */
    private static double weightOf(Foe foe) {
        double base = switch (foe.kind()) {
            case MELEE -> 1.5;
            case RANGED -> 2.0;
            case EXPLOSIVE -> foe.armed() ? 2.5 : 0.5;
            case FLYING -> 1.8;
        };
        return foe.chasingMe() ? base + 1.0 : base;
    }

    /**
     * 威胁排序：点着引信的爆炸类最前，其次正在追的，再按距离从近到远。
     * 一群怪里先对付最急的那个。
     */
    public static List<Foe> sortedByThreat(List<Foe> foes) {
        List<Foe> sorted = new ArrayList<>(foes);
        sorted.sort(Comparator
                .comparingInt((Foe foe) -> foe.kind() == Kind.EXPLOSIVE && foe.armed() ? 0 : 1)
                .thenComparing(foe -> foe.chasingMe() ? 0 : 1)
                .thenComparingDouble(Foe::distance));
        return sorted;
    }

    /**
     * 近战候选：会炸但没点引信的目标，手里又没有远程手段时不进近战候选——
     * 凑近一拳可能把它点着；躲它归撤离与避险。
     */
    public static List<Foe> meleeCandidates(List<Foe> foes, boolean hasRanged) {
        List<Foe> candidates = new ArrayList<>();
        for (Foe foe : foes) {
            if (foe.kind() == Kind.EXPLOSIVE && !foe.armed() && !hasRanged) {
                continue;
            }
            candidates.add(foe);
        }
        return candidates;
    }

    /** 一次评估的结论：判定与打不过时差在哪（空列表表示没有阻拦）。 */
    public record Assessment(Verdict verdict, List<String> gaps) {
        public Assessment {
            gaps = List.copyOf(gaps);
        }
    }
}
