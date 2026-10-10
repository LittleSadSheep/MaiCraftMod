// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 来源对一次问询的回答：能给多少、代价多少、有什么风险；给不了就说清为什么。
 * 引擎收集所有来源的回答再挑最省事的路，回答本身不决定先后。
 */
public sealed interface SourceQuote {

    /** 来源的名字，写进结果让 LLM 看到问过谁，例如"身上的背包""记得的箱子"。 */
    String source();

    /**
     * 能给：从这里能拿到多少、要走多远做多少动作。
     *
     * @param source          来源的名字，写进结果让 LLM 看到问过谁
     * @param obtainableCount 能拿到的数量；-1 表示还没打开看过、不知道有多少（例如只记得那里有只箱子）
     * @param cost            拿到它的代价
     * @param risk            要留意的事，例如"没开过，不确定里面有什么"；没有就为 null
     * @param hint            来源自己认的线索（哪个箱子、哪条配方），引擎原样带回去不解读
     */
    record Offer(String source, int obtainableCount, AcquisitionCost cost, String risk,
            String hint) implements SourceQuote {

        public Offer {
            if (obtainableCount != UNKNOWN_COUNT && obtainableCount <= 0) {
                throw new IllegalArgumentException("能拿到 0 件就不该报价，应回答给不了：" + source);
            }
        }

        /** 数量明确、没有线索的报价。 */
        public Offer(String source, int obtainableCount, AcquisitionCost cost, String risk) {
            this(source, obtainableCount, cost, risk, "");
        }

        /** 数量未知的报价：只记得有个箱子、没开过的时候给。 */
        public static final int UNKNOWN_COUNT = -1;

        public boolean countUnknown() {
            return obtainableCount == UNKNOWN_COUNT;
        }
    }

    /** 给不了：这次问询下这个来源没有货，reason 写清游戏里的事实。 */
    record Unavailable(String source, String reason) implements SourceQuote {
    }

    /** 超出许可：货可能有，但这次任务的许可不让动，problem 里写清要哪一项许可。 */
    record NeedsApproval(String source, Problem problem) implements SourceQuote {
    }

    /** 不支持：这类来源还没有实现或需要的模组没装；引擎不会在它身上空转。 */
    record Unsupported(String source, String reason) implements SourceQuote {
    }

    /**
     * 再问：来源还在看现场（附近的方块还没扫完），这一刻说不准有没有；
     * 引擎等一会儿再问遍，不把"还没看完"当成"没有"，等太久才按给不了收场。
     */
    record NotYet(String source, String reason) implements SourceQuote {
    }
}
