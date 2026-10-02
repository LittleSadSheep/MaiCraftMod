// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import static org.maiwithu.maicraft.client.actor.ActorControlTestHarness.check;

/** 任务书与普通动作共用真实身体许可和逐刻配额，发送异常、暂停或旧上下文都不能放行额外请求。 */
public final class FtbQuestSubmissionTest {
    public static void main(String[] args) throws Exception {
        var h = new ActorControlTestHarness(); int[] calls = {0};
        FtbQuestSubmission.submit(h.context, () -> calls[0]++);
        check(calls[0] == 1 && !h.context.mutationAvailable(), "FTB 提交占用本刻操作机会");
        rejected(() -> FtbQuestSubmission.submit(h.context, () -> calls[0]++));
        var previous = h.context; h.nextTick(false);
        rejected(() -> FtbQuestSubmission.submit(previous, () -> calls[0]++));
        rejected(() -> FtbQuestSubmission.submit(h.context, () -> calls[0]++));
        h.nextTick(true);
        rejected(() -> FtbQuestSubmission.submit(h.context, () -> { calls[0]++; throw new IllegalStateException("发送后异常"); }));
        check(calls[0] == 2 && !h.context.mutationAvailable(), "异常不能重新放开已经使用的动作机会");
        System.out.println("FtbQuestSubmissionTest: passed");
    }
    private static void rejected(Runnable action) { try { action.run(); throw new AssertionError("不应放行此提交"); } catch (IllegalStateException expected) { /* 保留游戏控制边界，不执行替代动作。 */ } }
}
