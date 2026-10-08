package org.maiwithu.maicraft.behavior.navigation.baritone;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;

/** 步行前先按原生潜行键下车，等待乘客同步；不能只在客户端摘掉座位关系。 */
public final class NavigationDismount {
    public enum State { WAITING, READY, FAILED }
    private NativeActionReceipt receipt;
    private String detail = "not_requested";

    boolean pending() { return receipt != null && !receipt.terminal(); }
    public String detail() { return detail; }

    public State tick(LocalPlayerContext ctx) {
        if (receipt != null) {
            ctx.actions().poll(ctx, receipt);
            detail = receipt.detail();
            if (receipt.terminal()) {
                ctx.body().applyMovement(BodyControlPort.Movement.STOPPED, ctx.tickRevision());
                return receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED
                        ? State.READY : State.FAILED;
            }
        } else {
            if (!ctx.player().isPassenger()) return State.READY;
            if (!ctx.mutationAvailable()) { detail = "waiting_for_native_action_slot"; return State.WAITING; }
            receipt = ctx.actions().submitControlProtocol(ctx, "navigation native Shift dismount",
                    () -> sneak(ctx), live -> !live.player().isPassenger()
                            ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING, 30);
            detail = "waiting_for_server_dismount";
        }
        // 输入只租用一刻；服务器尚未同步下车时续按潜行，同步后松键，期间不开始步行。
        if (ctx.player().isPassenger()) sneak(ctx);
        else ctx.body().applyMovement(BodyControlPort.Movement.STOPPED, ctx.tickRevision());
        return State.WAITING;
    }

    private static void sneak(LocalPlayerContext ctx) {
        ctx.body().applyMovement(new BodyControlPort.Movement(0, 0, false, true, false), ctx.tickRevision());
    }

    public void cancel(LocalPlayer player) {
        // 暂停或换任务只结清本次下车回执；旧身体或失去控制权时由角色边界清理，不能操作新角色。
        ClientRuntime.actor().activeContext().filter(ctx -> ctx.player() == player && ctx.permitsNativeActions())
                .ifPresent(ctx -> {
                    if (pending()) ctx.actions().retireOneShotForTaskBoundary(ctx, receipt, "navigation dismount interrupted");
                });
        receipt = null;
    }
}
