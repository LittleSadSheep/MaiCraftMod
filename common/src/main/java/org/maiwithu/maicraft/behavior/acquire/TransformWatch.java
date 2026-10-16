// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

/**
 * 料扔进水里以后等它变：每刻数一遍那格水附近新冒出来的产物，说还等不等。纯判断，不读游戏。
 *
 * <p>冒出来的件数够了就不等；冒出来以后两秒没再多就收（一份一份在变，停了就是料用完了）；
 * 十秒一件都没冒出来就不等了（AE2 要泡够三秒，加上落水的时间，十秒还没有就是凑不齐）。
 */
final class TransformWatch {

    /** 一件都没冒出来时最多等这么多刻。 */
    static final int GIVE_UP_TICKS = 200;
    /** 冒出来以后这么多刻没再多就收。 */
    static final int QUIET_TICKS = 40;

    /** 等的结论。 */
    enum Verdict {
        /** 接着等。 */
        WAITING,
        /** 冒出来的够数了。 */
        ENOUGH,
        /** 冒出来一些，之后不再多了。 */
        SETTLED,
        /** 等到头一件都没冒出来。 */
        NOTHING
    }

    private final int expected;
    private int waited;
    private int quiet;
    private int seen;

    /** @param expected 份数乘一份出几件：冒出来这么多就不等 */
    TransformWatch(int expected) {
        this.expected = expected;
    }

    /** 这一刻在那格水附近数到的产物件数（只数扔料以后新冒出来的）。 */
    Verdict observe(int productsNow) {
        waited++;
        if (productsNow > seen) {
            seen = productsNow;
            quiet = 0;
        } else {
            quiet++;
        }
        if (seen >= expected) return Verdict.ENOUGH;
        if (seen > 0 && quiet >= QUIET_TICKS) return Verdict.SETTLED;
        if (seen == 0 && waited >= GIVE_UP_TICKS) return Verdict.NOTHING;
        return Verdict.WAITING;
    }

    /** 到目前为止最多数到过几件。 */
    int seen() {
        return seen;
    }

    /** 等了几刻。 */
    int waited() {
        return waited;
    }
}
