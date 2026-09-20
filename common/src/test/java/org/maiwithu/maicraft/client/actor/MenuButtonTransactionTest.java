// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import static org.maiwithu.maicraft.client.actor.ActorControlTestHarness.*;

/** 直接调用生产菜单端口和原版按钮发包方法；手动注入显示与同步事实，不启动游戏、渲染窗口或实际附魔。 */
public final class MenuButtonTransactionTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        submissionAndSynchronization(); rejectionAndExceptions(); submissionGates();
        timeoutAndRevocation(); synchronizedNegativeResults(); legacyPortCompatibility();
        replacementWithSameIdCannotConfirm();
        System.out.println("MenuButtonTransactionTest: native button transaction boundaries passed");
    }

    private static void submissionAndSynchronization() throws Exception {
        var h = new Harness(); h.ready(); h.menu.optimisticVersion = true;
        MenuConfirmation.Verdict[] postcondition = {MenuConfirmation.Verdict.APPLIED};
        MenuReceipt receipt = h.port.pressButton(h.context(), 2, (c, r) -> postcondition[0], 30);
        check(receipt.kind() == MenuReceipt.Kind.BUTTON && !receipt.terminal(), "本地接受不能当作附魔成功");
        check(h.menu.validations == 1 && h.menu.packetsAtValidation == 0, "必须先本地校验再发包");
        check(h.actor.connection.packets.size() == 1
                && h.actor.connection.packets.getFirst() instanceof ServerboundContainerButtonClickPacket packet
                && packet.containerId() == h.menu.containerId && packet.buttonId() == 2, "只发送一次对应菜单按钮包");
        check(!h.context().mutationAvailable(), "按钮须占用本刻原生操作额度");
        expect(IllegalStateException.class, () -> h.port.pressButton(h.context(), 1, MenuConfirmation.pending(), 20));
        // 本地校验即使改了版本且结果看似满足，等待超过旧稳定窗口也不能冒充服务器接受。
        for (int i = 0; i < 12; i++) { h.next(true); h.port.poll(h.context(), receipt); }
        check(!receipt.terminal() && receipt.beforeStateId() == 0 && h.menu.getStateId() == 1,
                "没有新同步时，乐观版本和经过时间都不能确认按钮");
        postcondition[0] = MenuConfirmation.Verdict.PENDING;
        h.menu.setItem(0, 2, Items.ENCHANTED_BOOK.getDefaultInstance()); h.next(true);
        h.port.poll(h.context(), receipt);
        check(!receipt.terminal(), "菜单版本单独变化不能替代调用方的精确后置条件");
        postcondition[0] = MenuConfirmation.Verdict.APPLIED; h.next(true);
        h.port.poll(h.context(), receipt); h.port.poll(h.context(), receipt);
        check(receipt.status() == MenuReceipt.Status.CONFIRMED_APPLIED && h.menu.validations == 1
                && h.actor.connection.packets.size() == 1, "精确结果和新同步齐全后确认，轮询不能再次扣费");
    }

    private static void rejectionAndExceptions() throws Exception {
        var rejected = new Harness(); rejected.ready(); rejected.menu.accept = false;
        var receipt = rejected.port.pressButton(rejected.context(), 0, MenuConfirmation.pending(), 20);
        check(receipt.status() == MenuReceipt.Status.CONFIRMED_NOT_APPLIED && rejected.actor.connection.packets.isEmpty(),
                "客户端拒绝报价时明确未应用且不得发包");
        // 校验或发送已经开始后抛异常，只能记录不确定；恢复连接与轮询不会补发第二次。
        for (boolean failLocally : new boolean[]{true, false}) {
            var h = new Harness(); h.ready(); h.menu.failValidation = failLocally;
            h.actor.connection.failSend = !failLocally;
            var uncertain = h.port.pressButton(h.context(), 1, MenuConfirmation.pending(), 20);
            check(uncertain.status() == MenuReceipt.Status.UNCERTAIN, "原生事务途中异常必须保留不确定结果");
            h.menu.failValidation = false; h.actor.connection.failSend = false; h.next(true);
            h.port.poll(h.context(), uncertain);
            check(h.menu.validations == 1 && h.actor.connection.packets.isEmpty(), "异常结果不能自动重试按钮");
        }
    }

    private static void submissionGates() throws Exception {
        var h = new Harness();
        expect(IllegalStateException.class, () -> h.port.pressButton(h.context(), 0, MenuConfirmation.pending(), 20));
        h.show(); h.port.ensureVisible(h.context());
        for (int i = 0; i < 5; i++) h.next(true);
        expect(IllegalStateException.class, () -> h.port.pressButton(h.context(), 0, MenuConfirmation.pending(), 20));
        MenuVisibility.rendered(h.screen);
        check(h.port.ensureVisible(h.context()), "对应菜单真正绘制后才就绪");
        var stale = h.context(); h.next(true);
        expect(IllegalStateException.class, () -> h.port.pressButton(stale, 0, MenuConfirmation.pending(), 20));
        h.context().claimMutation();
        expect(IllegalStateException.class, () -> h.port.pressButton(h.context(), 0, MenuConfirmation.pending(), 20));
        h.next(false);
        expect(IllegalStateException.class, () -> h.port.pressButton(h.context(), 0, MenuConfirmation.pending(), 20));
        check(h.menu.validations == 0 && h.actor.connection.packets.isEmpty(),
                "隐藏、未绘制、旧上下文、同刻重复和无控制权时均不能进入原生按钮校验");
    }

    private static void timeoutAndRevocation() throws Exception {
        var timeout = new Harness(); timeout.ready();
        var expired = timeout.port.pressButton(timeout.context(), 0, (c, r) -> MenuConfirmation.Verdict.APPLIED, 8);
        for (int i = 0; i < 8; i++) { timeout.next(true); timeout.port.poll(timeout.context(), expired); }
        check(expired.status() == MenuReceipt.Status.UNCERTAIN, "超时不能把缺少服务器同步的附魔判成成功");
        var handoff = new Harness(); handoff.ready();
        var revoked = handoff.port.pressButton(handoff.context(), 0, MenuConfirmation.pending(), 20);
        handoff.next(false); handoff.port.poll(handoff.context(), revoked);
        check(revoked.status() == MenuReceipt.Status.UNCERTAIN, "手动接管后停止等待自动确认");
        // 用户关闭原菜单也会失去这次附魔的结果来源；不能把默认物品栏里的变化当成旧按钮确认。
        var closed = new Harness(); closed.ready();
        var interrupted = closed.port.pressButton(closed.context(), 0, MenuConfirmation.pending(), 20);
        closed.actor.player.containerMenu = closed.actor.player.inventoryMenu; closed.next(true);
        closed.port.poll(closed.context(), interrupted);
        check(interrupted.status() == MenuReceipt.Status.UNCERTAIN, "原菜单关闭后必须停止等待该按钮确认");
        check(handoff.actor.connection.packets.size() == 1 && timeout.actor.connection.packets.size() == 1
                && closed.actor.connection.packets.size() == 1, "接管、关闭和超时收尾均不能重新提交按钮");
    }

    private static void synchronizedNegativeResults() throws Exception {
        // 同步前保持等待；服务器给出与期望不符的事实后，才能报告拒绝或分歧。
        for (var verdict : new MenuConfirmation.Verdict[]{MenuConfirmation.Verdict.NOT_APPLIED, MenuConfirmation.Verdict.DIVERGED}) {
            var h = new Harness(); h.ready();
            var receipt = h.port.pressButton(h.context(), 0, (c, r) -> verdict, 20);
            h.next(true); h.port.poll(h.context(), receipt); check(!receipt.terminal(), "网络延迟不能当作拒绝");
            h.menu.setItem(0, 1, Items.BOOK.getDefaultInstance()); h.next(true); h.port.poll(h.context(), receipt);
            check(receipt.status() == (verdict == MenuConfirmation.Verdict.NOT_APPLIED
                    ? MenuReceipt.Status.CONFIRMED_NOT_APPLIED : MenuReceipt.Status.DIVERGED), "保留同步后的明确负面结果");
        }
    }

    private static void replacementWithSameIdCannotConfirm() throws Exception {
        var h = new Harness(); h.ready();
        var receipt = h.port.pressButton(h.context(), 0, (context, pending) -> MenuConfirmation.Verdict.APPLIED, 20);
        var replacement = new TestMenu(h.actor.connection);
        check(replacement.containerId == h.menu.containerId, "场景必须复用同一菜单编号");
        replacement.setItem(0, 2, Items.ENCHANTED_BOOK.getDefaultInstance());
        h.actor.player.containerMenu = replacement;
        h.next(true); h.port.poll(h.context(), receipt);
        check(receipt.status() == MenuReceipt.Status.UNCERTAIN && h.actor.connection.packets.size() == 1,
                "同编号新菜单的版本与物品变化不能确认旧菜单的按钮，也不能导致重新提交");
    }

    private static void legacyPortCompatibility() throws Exception {
        // 旧测试端口可继续只实现槽位能力；新按钮默认拒绝，不会假装支持并耗用物品。
        MenuPort legacy = (MenuPort) Proxy.newProxyInstance(MenuPort.class.getClassLoader(), new Class<?>[]{MenuPort.class},
                (proxy, method, args) -> InvocationHandler.invokeDefault(proxy, method, args));
        expect(UnsupportedOperationException.class, () -> legacy.pressButton(null, 0, MenuConfirmation.pending(), 20));
    }

    private static final class Harness {
        final ActorControlTestHarness actor = new ActorControlTestHarness();
        final DefaultMenuPort port = actor.actor.menus();
        final TestMenu menu = new TestMenu(actor.connection);
        final MultiPlayerGameMode gameMode = new MultiPlayerGameMode(actor.minecraft, actor.connection);
        Screen screen;
        Harness() throws Exception {
            actor.player.containerMenu = menu; actor.minecraft.gameMode = gameMode;
            field(DefaultLocalPlayerContext.class, "gameMode").set(actor.context, gameMode);
        }
        DefaultLocalPlayerContext context() { return actor.context; }
        void next(boolean permitted) throws Exception {
            actor.nextTick(permitted); field(DefaultLocalPlayerContext.class, "gameMode").set(actor.context, gameMode);
        }
        void show() throws Exception {
            screen = actor.inventoryScreen(); field(AbstractContainerScreen.class, "menu").set(screen, menu);
            actor.minecraft.screen = screen;
        }
        void ready() throws Exception {
            show(); check(!port.ensureVisible(context()), "新菜单先等待展示");
            for (int i = 0; i < 4; i++) next(true);
            MenuVisibility.rendered(screen); check(port.ensureVisible(context()), "注入已绘制事实后菜单就绪");
        }
    }

    private static final class TestMenu extends AbstractContainerMenu {
        final ActorControlTestHarness.RecordingConnection connection;
        boolean accept = true, failValidation, optimisticVersion;
        int validations, packetsAtValidation;
        TestMenu(ActorControlTestHarness.RecordingConnection connection) {
            super(null, 7); this.connection = connection; addSlot(new Slot(new SimpleContainer(1), 0, 0, 0));
        }
        @Override public boolean clickMenuButton(Player player, int button) {
            validations++; packetsAtValidation = connection.packets.size();
            if (optimisticVersion) incrementStateId();
            if (failValidation) throw new IllegalStateException("test native validation failed");
            return accept;
        }
        @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return true; }
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void expect(Class<? extends Exception> type, Checked operation) throws Exception {
        try { operation.run(); }
        catch (Exception failure) { check(type.isInstance(failure), "错误类型不符: " + failure); return; }
        throw new AssertionError("应拒绝此按钮提交: " + type.getSimpleName());
    }
}
