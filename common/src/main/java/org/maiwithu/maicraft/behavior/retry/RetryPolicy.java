// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.retry;

import org.maiwithu.maicraft.kernel.result.Attempt;
import org.maiwithu.maicraft.kernel.result.Problem;

import java.util.List;
import java.util.Objects;

/**
 * 重试策略：一个步骤失败之后，按问题的种类决定是同一个办法再试、换排好序的下一个办法，还是结束；
 * 同一种失败重复的次数有上限，不会用同一个办法无限撞墙。
 *
 * <p>纯判断：读失败的问题种类与已试次数，给决定。它不执行重试，也不掌握办法清单——
 * 排好序的办法由使用它的任务持有，这里只回答"下一步怎么走"。
 *
 * <p>按问题种类的分流（种类含义见 {@link Problem.Kind}）：
 * <ul>
 * <li>同一个办法再试：失败原因会随时间自己消失的——{@code WRONG_TIME}（时间窗没到）；
 *     {@code REFUSED_BY_GAME} 里也有瞬时的拒绝，值得再试，固定拒绝会很快撞到上限，自动转去换办法。</li>
 * <li>换下一个办法：这个对象或这条路已经不行，换候选里的下一个才有意义的——{@code UNREACHABLE}、{@code TARGET_GONE}。</li>
 * <li>结束：再试也改变不了事实的——缺东西、危险、游戏规则不允许、背包满、不支持、程序出错等。
 *     {@code NEED_APPROVAL} 不在这里处理：它应先经 {@link QuestionEscalation} 升级成提问；
 *     原样落到这里时按结束处理，把问题原样带回去，不悄悄替 LLM 决定。</li>
 * </ul>
 */
public final class RetryPolicy {

    private final Backoff backoff;
    private final int maxSameFailures;

    /**
     * @param backoff          同一个办法再试前等多久的退避规则
     * @param maxSameFailures  同一种失败最多允许重复多少次；到上限就必须换办法或结束
     */
    public RetryPolicy(Backoff backoff, int maxSameFailures) {
        if (maxSameFailures <= 0) {
            throw new IllegalArgumentException("同一种失败的重试上限必须为正");
        }
        this.backoff = Objects.requireNonNull(backoff, "backoff");
        this.maxSameFailures = maxSameFailures;
    }

    /**
     * 一次失败之后怎么走。
     *
     * @param failure            这次的失败
     * @param sameFailures       同一种失败（同一个办法、同一个问题种类）已经发生了几次，从 1 数起
     * @param untriedApproaches  排好的办法里还有几个没试过
     */
    public RetryDecision afterFailure(Problem failure, int sameFailures, int untriedApproaches) {
        return switch (failure.kind()) {
            // 会随时间自己消失的失败：同一个办法再试，退避等待，撞到上限才换。
            case WRONG_TIME, REFUSED_BY_GAME -> retrySameOrFail(failure, sameFailures, untriedApproaches);
            // 这个对象或这条路已经不行：只要还有办法就换下一个。
            case UNREACHABLE, TARGET_GONE -> untriedApproaches > 0
                    ? RetryDecision.TRY_NEXT
                    : new RetryDecision.GiveUp(ladderExhausted(failure));
            // 再试也改变不了事实：直接结束，问题原样带回去。
            default -> new RetryDecision.GiveUp(failure);
        };
    }

    /**
     * 办法用尽、主动放弃时的问题：换过办法仍然反复失败，归为卡住。
     *
     * <p>问题里带上试过的办法摘要（让 LLM 看到已经走了哪些路，不逐刻复述）与怎样能继续的建议；
     * 没有把握的建议传 null，不编造。
     *
     * @param lastFailure 最后一次失败
     * @param attempts    试过的办法摘要
     * @param suggestion  怎样能继续；不确定时为 null
     */
    public Problem giveUp(Problem lastFailure, List<Attempt> attempts, String suggestion) {
        StringBuilder message = new StringBuilder("换过办法仍然不行，最后：").append(lastFailure.message());
        if (!attempts.isEmpty()) {
            message.append("；试过：");
            for (int i = 0; i < attempts.size(); i++) {
                if (i > 0) message.append("；");
                message.append(attempts.get(i).tried()).append("（").append(attempts.get(i).result()).append("）");
            }
        }
        return new Problem(Problem.Kind.STUCK, message.toString(), suggestion);
    }

    private RetryDecision retrySameOrFail(Problem failure, int sameFailures, int untriedApproaches) {
        if (sameFailures < maxSameFailures) {
            return new RetryDecision.RetrySame(backoff.waitTicks(sameFailures));
        }
        // 同一种失败已经到上限：还办法可换就换，否则承认这条路走不通。
        return untriedApproaches > 0
                ? RetryDecision.TRY_NEXT
                : new RetryDecision.GiveUp(ladderExhausted(failure));
    }

    // 办法清单见底时的问题：不再引用最后一次失败的具体种类，统一按卡住交代，事实由摘要携带。
    private Problem ladderExhausted(Problem lastFailure) {
        return Problem.of(Problem.Kind.STUCK,
                "能想的办法都试过了还是不行，最后：" + lastFailure.message());
    }
}
