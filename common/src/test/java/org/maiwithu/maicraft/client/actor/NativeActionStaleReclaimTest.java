// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** 接管或长期无驱动遗留的待确认动作在下次提交前就地结算；未过期且同身体版本的冲突仍按拒绝处理。 */
public final class NativeActionStaleReclaimTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var h = new ActorControlTestHarness();
        Method requireIdle = DefaultNativeActionPort.class.getDeclaredMethod("requireIdle", LocalPlayerContext.class);
        requireIdle.setAccessible(true);
        // 过期僵尸：deadline 越过后，提交路径就地结算为不确定并放行，不再抛会话级异常。
        var expiredContext = context(h, 0, 0, 300);
        var expired = receipt(expiredContext, 5);
        ActorControlTestHarness.field(DefaultNativeActionPort.class, "active").set(h.actions, expired);
        requireIdle.invoke(h.actions, context(h, 0, 0, 400));
        check(expired.status() == NativeActionReceipt.Status.UNCERTAIN,
                "过期待确认必须就地结算为不确定，不能楔死动作队列");
        // 未过期但身体/控制版本已变：接管现场同样由下一次提交回收，旧结果不再可信。
        var foreignContext = context(h, 1, 2, 100);
        var foreign = receipt(foreignContext, 200);
        ActorControlTestHarness.field(DefaultNativeActionPort.class, "active").set(h.actions, foreign);
        requireIdle.invoke(h.actions, context(h, 2, 3, 101));
        check(foreign.status() == NativeActionReceipt.Status.UNCERTAIN,
                "跨身体/控制版本的待确认由新提交回收");
        // 未过期且同身体版本：真实并发冲突仍拒绝，不能吞掉两次提交共用一份结果的错误。
        var liveContext = context(h, 3, 4, 100);
        var live = receipt(liveContext, 200);
        ActorControlTestHarness.field(DefaultNativeActionPort.class, "active").set(h.actions, live);
        try {
            requireIdle.invoke(h.actions, context(h, 3, 4, 101));
            throw new AssertionError("未过期的同版本待确认仍应拒绝新提交");
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof IllegalStateException, "拒绝必须保持原有异常语义");
        }
        System.out.println("NativeActionStaleReclaimTest: passed");
    }

    private static DefaultLocalPlayerContext context(
            ActorControlTestHarness h, long bodyEpoch, long controlRevision, long tick) {
        return new DefaultLocalPlayerContext(h.actor, h.minecraft, h.player, null, null, h.connection,
                bodyEpoch, controlRevision, tick, true);
    }

    private static NativeActionReceipt receipt(LocalPlayerContext context, int timeoutTicks) {
        return new NativeActionReceipt(NativeActionReceipt.Kind.BREAK_BLOCK, context, timeoutTicks, 1, null, null, null);
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
