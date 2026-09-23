// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;

/** 真实运行时门控会占用一次角色修改权限，但不会阻塞下一 tick 的紧急输入。 */
public final class ServerActorMutationGateTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var h = new ServerRouterTestHarness(false);
        var receipt = h.submit("test.write");
        var submit = ServerSessionRuntime.class.getDeclaredMethod("submitMutation", ClientRequestReceipt.class, Runnable.class);
        submit.setAccessible(true);
        try (var world = new InteractionWorldTestHarness()) {
            var context = ClientRuntime.requireContext(world.player);
            int[] calls = {0};
            check((boolean) submit.invoke(null, receipt, (Runnable) () -> calls[0]++), "actual runtime gate sent one request");
            check(calls[0] == 1 && !context.mutationAvailable(), "remote submission retains the actor's one-mutation limit this tick");
            check(!(boolean) submit.invoke(null, receipt, (Runnable) () -> calls[0]++), "second same-tick mutation is denied before send");
            world.nextTick();
            context = ClientRuntime.requireContext(world.player);
            context.actions().submitControlProtocol(context, "emergency native action", () -> calls[0]++, NativeConfirmation.pending(), 1);
            check(calls[0] == 2, "a remote reply delay does not occupy the next tick's emergency native action slot");
        }
        System.out.println("ServerActorMutationGateTest: passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
