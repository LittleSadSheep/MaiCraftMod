// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fish;

/**
 * 等咬钩的一段判断：这一刻该收竿取获、收竿报废、还是接着等。
 *
 * <p>咬钩看客户端同步的字段，不等服务端粒子；鱼钩消失、钩住实体、抛出没入水、
 * 一竿等太久都是报废，各自交代原因，钩住实体与没入水的水面还要进排除集。
 */
final class BiteWatch {

    /** 等咬钩的一刻事实。 */
    record BiteFacts(boolean hasHook, boolean biting, boolean hookedEntity, boolean inWater, int waitedTicks) {}

    /** 这一刻怎么走：收竿取获、收竿报废（水面进排除集与否分开交代）、或接着等。 */
    enum Step {
        /** 咬钩了，立刻收竿。 */
        REEL_IN,
        /** 钩坏了一竿：先收竿，这一竿报废。 */
        REEL_AND_SCRAP,
        /** 鱼钩已经没了：直接报废重抛，不用收。 */
        SCRAP_GONE,
        /** 接着等。 */
        WAIT
    }

    /** 抛出 5 秒还没入水就收竿重抛。F 游戏事实（一竿落水的耐心）。 */
    static final int SETTLE_TICKS = 5 * 20;
    /** 一竿 60 秒没咬钩就收竿重抛。F 游戏事实（一竿的等待上限）。 */
    static final int LIFETIME_TICKS = 60 * 20;

    /** 判断的结论：怎么走，报废时水面要不要进排除集（久等不咬不怪水面）。 */
    record Verdict(Step step, boolean rejectsLanding) {}

    private BiteWatch() {}

    /** 判断这一刻怎么走。 */
    static Verdict judge(BiteFacts facts) {
        if (!facts.hasHook()) return new Verdict(Step.SCRAP_GONE, false);
        // 咬钩优先：等了多久都立刻收。
        if (facts.biting()) return new Verdict(Step.REEL_IN, false);
        if (facts.hookedEntity()) return new Verdict(Step.REEL_AND_SCRAP, true);
        if (!facts.inWater() && facts.waitedTicks() >= SETTLE_TICKS) return new Verdict(Step.REEL_AND_SCRAP, true);
        if (facts.waitedTicks() >= LIFETIME_TICKS) return new Verdict(Step.REEL_AND_SCRAP, false);
        return new Verdict(Step.WAIT, false);
    }

    /** 报废的一句话原因，与判断同一张表，话术不会分岔。 */
    static String reason(Step step, int waitedTicks) {
        return switch (step) {
            case REEL_IN -> "咬钩了";
            case REEL_AND_SCRAP -> waitedTicks >= LIFETIME_TICKS
                    ? "一竿等了 " + LIFETIME_TICKS / 20 + " 秒都没有咬钩"
                    : "钩坏了一竿（钩住实体或没落进水里）";
            case SCRAP_GONE -> "鱼钩在咬钩前消失了";
            case WAIT -> "";
        };
    }
}
