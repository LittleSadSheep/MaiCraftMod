// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.retry;

import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 一次失败之后的决定，只有三种：同一个办法再试、换排好序的下一个办法、带着完整事实结束。
 *
 * <p>没有"要不要重试"之外的第四种，也永远不会升级成向 LLM 问"要不要重试"；
 * 要提问的处境（超出许可、要选做法、目标说不清）不走这里，见 {@link QuestionEscalation}。
 */
public sealed interface RetryDecision {

    /** 同一个办法再试，先等 waitTicks 刻（退避）：失败原因可能随时间自己消失。 */
    record RetrySame(long waitTicks) implements RetryDecision {}

    /** 这个办法行不通，换排好序的下一个办法。 */
    record TryNext() implements RetryDecision {}

    /** 不再重试：把卡在哪、试过什么、怎样能继续原样交代清楚。 */
    record GiveUp(Problem problem) implements RetryDecision {}

    /** 换办法不需要附加信息，各处共用同一个实例。 */
    RetryDecision TRY_NEXT = new TryNext();
}
