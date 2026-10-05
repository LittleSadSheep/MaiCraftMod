// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * 刻外任务取消（两次游戏刻之间，没有当刻上下文）不得把 PENDING 回执留在动作队列占位：
 * 挖掘登记边界原因由下一刻 advance 停手并结算，一次性动作就地如实终结为不确定。
 */
public final class NativeActionBoundaryDeferralTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var h = new ActorControlTestHarness();
        // 刻外取消登记：回执保持 PENDING 但已带边界原因，下一刻由 advance 完成停手与结算。
        var context = h.nextTick(true);
        var breakReceipt = receipt(context, NativeActionReceipt.Kind.BREAK_BLOCK, 200);
        ActorControlTestHarness.field(DefaultNativeActionPort.class, "active").set(h.actions, breakReceipt);
        h.actions.deferBreakCancellationForTaskBoundary(
                breakReceipt, "the block-digging task ended before its native break was confirmed");
        var reason = (String) ActorControlTestHarness.field(
                DefaultNativeActionPort.class, "pendingBreakCancellationReason").get(h.actions);
        check(reason != null && reason.contains("task ended"), "刻外取消必须登记边界原因供下一刻停手");
        check(!breakReceipt.terminal(), "登记阶段不终结回执，物理停手在下一刻 advance 完成");
        check(!h.actor.settledForRoutinePause(), "停手登记期间生活动作不得插队接手");
        // 下一刻 advance：先确认后停手结算，原因清空，队列放行。
        var nextTick = h.nextTick(true);
        h.actions.advance(nextTick);
        check(breakReceipt.terminal(), "登记后下一刻必须结算，不允许 PENDING 继续占位");
        check(breakReceipt.status() == NativeActionReceipt.Status.CANCELLED
                || breakReceipt.status() == NativeActionReceipt.Status.UNCERTAIN,
                "结算只能落到取消或效果未知，不能伪称已确认");
        var clearedReason = ActorControlTestHarness.field(
                DefaultNativeActionPort.class, "pendingBreakCancellationReason").get(h.actions);
        check(clearedReason == null, "结算后必须清空停手登记");
        check(h.actor.settledForRoutinePause(), "结算后队列对生活动作放行");
        // 刻外取消的一次性动作版：就地如实终结为不确定，不留占位。
        var oneShot = receipt(context, NativeActionReceipt.Kind.SELECT_HOTBAR, 200);
        ActorControlTestHarness.field(DefaultNativeActionPort.class, "active").set(h.actions, oneShot);
        h.actions.abandonOneShotForTaskBoundary(oneShot, "tool selection ended with its task");
        check(oneShot.status() == NativeActionReceipt.Status.UNCERTAIN
                && oneShot.detail().contains("may already have applied"),
                "一次性动作刻外收尾必须终结为效果未知");
        // 持续动作没有物理停手不得走就地终结；不属于本端口的回执也不动。
        var continuous = receipt(context, NativeActionReceipt.Kind.USE_ITEM, 200);
        ActorControlTestHarness.field(DefaultNativeActionPort.class, "active").set(h.actions, continuous);
        try {
            h.actions.abandonOneShotForTaskBoundary(continuous, "no");
            throw new AssertionError("持续动作必须走专门停手，不能就地终结");
        } catch (IllegalArgumentException expected) { }
        var foreign = receipt(context, NativeActionReceipt.Kind.SELECT_HOTBAR, 200);
        h.actions.abandonOneShotForTaskBoundary(foreign, "not mine");
        check(!foreign.terminal(), "非端口持有的回执不得被刻外收尾改动");
        System.out.println("NativeActionBoundaryDeferralTest: passed");
    }

    private static NativeActionReceipt receipt(
            LocalPlayerContext context, NativeActionReceipt.Kind kind, int timeoutTicks) {
        return new NativeActionReceipt(kind, context, timeoutTicks, 1, null, null, null);
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
