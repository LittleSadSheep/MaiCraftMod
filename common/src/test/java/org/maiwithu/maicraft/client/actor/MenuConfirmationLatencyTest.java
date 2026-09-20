// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.AbstractContainerMenu;
import static org.maiwithu.maicraft.client.actor.ActorControlTestHarness.*;

/** 模拟两秒往返：预测结果不能在半秒就确认，后来到达的服务端拒绝仍应覆盖它。 */
public final class MenuConfirmationLatencyTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        delayedCorrection();
        stableAfterRoundTrip();
        System.out.println("MenuConfirmationLatencyTest: passed");
    }

    private static void delayedCorrection() throws Exception {
        var h = highLatency();
        var port = h.actor.menus();
        MenuConfirmation.Verdict[] observation = {MenuConfirmation.Verdict.APPLIED};
        var receipt = create(port, h, (context, pending) -> observation[0]);
        check(receipt.deadlineTick() - receipt.submittedTick() >= 45, "确认预算应覆盖两秒往返和显示余量");
        for (int i = 0; i < 20; i++) { h.nextTick(true); port.poll(h.context, receipt); }
        check(!receipt.terminal(), "一秒的本地预测不能提前成为两秒链路上的确定结果");
        observation[0] = MenuConfirmation.Verdict.NOT_APPLIED;
        field(AbstractContainerMenu.class, "stateId").setInt(h.player.containerMenu, 1);
        h.nextTick(true); port.poll(h.context, receipt);
        check(receipt.status() == MenuReceipt.Status.CONFIRMED_NOT_APPLIED, "服务端随后拒绝时仍须保留拒绝结果");
    }

    private static void stableAfterRoundTrip() throws Exception {
        var h = highLatency(); var port = h.actor.menus();
        var receipt = create(port, h, (context, pending) -> MenuConfirmation.Verdict.APPLIED);
        for (int i = 0; i < 43; i++) { h.nextTick(true); port.poll(h.context, receipt); }
        check(receipt.status() == MenuReceipt.Status.CONFIRMED_APPLIED,
                "没有版本回显的正常点击，仍可在完整往返窗口后按持续稳定事实确认");
        check(h.connection.packets.isEmpty(), "轮询不能重新发出点击");
    }

    private static ActorControlTestHarness highLatency() throws Exception {
        var h = new ActorControlTestHarness();
        var info = h.allocate(PlayerInfo.class); field(PlayerInfo.class, "latency").setInt(info, 2000);
        Map<UUID, PlayerInfo> players = new HashMap<>(); players.put(h.player.getUUID(), info);
        field(ClientPacketListener.class, "playerInfoMap").set(h.connection, players);
        return h;
    }

    private static MenuReceipt create(DefaultMenuPort port, ActorControlTestHarness h, MenuConfirmation confirmation) throws Exception {
        var method = DefaultMenuPort.class.getDeclaredMethod("create", MenuReceipt.Kind.class, LocalPlayerContext.class,
                AbstractContainerMenu.class, int.class, boolean.class, MenuConfirmation.class);
        method.setAccessible(true);
        return (MenuReceipt) method.invoke(port, MenuReceipt.Kind.CLICK, h.context, h.player.containerMenu, 20, false, confirmation);
    }
}
