// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Proxy;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.integration.backpack.BackpackOpenSession;

/** 验证开包边界与异步关闭顺序；容器内容由夹具同步，不把本测试说成已安装精妙菜单的实机操作。 */
public final class BackpackOpenSessionTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY);
            var unavailable = new BackpackOpenSession();
            check(unavailable.open(ClientRuntime.requireContext(h.player)) == BackpackOpenSession.Status.FAILED
                    && h.itemUses() == 0, "ordinary carried items never open a backpack provider");
            var session = new BackpackOpenSession();
            var menu = ChestMenu.threeRows(9, h.inventory, new SimpleContainer(27)); h.player.containerMenu = menu;
            ActorControlTestHarness.field(BackpackOpenSession.class, "owner").set(session, h.player);
            ActorControlTestHarness.field(BackpackOpenSession.class, "level").set(session, h.level);
            ActorControlTestHarness.field(BackpackOpenSession.class, "menu").set(session, menu);
            var close = new MenuReceipt(MenuReceipt.Kind.CLOSE, ClientRuntime.requireContext(h.player), 9, 0, 40, false,
                    (context, receipt) -> MenuConfirmation.Verdict.PENDING);
            int[] clicks = {0};
            MenuPort port = (MenuPort) Proxy.newProxyInstance(MenuPort.class.getClassLoader(), new Class<?>[]{MenuPort.class}, (proxy, method, argv) -> {
                if (method.getName().equals("close")) { clicks[0]++; h.player.containerMenu = h.player.inventoryMenu; return close; }
                if (method.getName().equals("poll")) return argv[1];
                throw new AssertionError("unexpected menu action: " + method.getName());
            });
            var live = ClientRuntime.requireContext(h.player);
            LocalPlayerContext context = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(),
                    new Class<?>[]{LocalPlayerContext.class}, (proxy, method, argv) -> method.getName().equals("menus") ? port : method.invoke(live, argv));
            check(session.close(context) == BackpackOpenSession.Status.RUNNING, "close submits once");
            check(session.close(context) == BackpackOpenSession.Status.RUNNING && clicks[0] == 1,
                    "native menu identity may change before the close receipt arrives");
            close.finish(MenuReceipt.Status.CONFIRMED_APPLIED, "close fixture confirmed");
            check(session.close(context) == BackpackOpenSession.Status.CLOSED, "confirmed close is settled after the GUI transition");
            var cursorSession = new BackpackOpenSession(); h.player.containerMenu = menu; menu.setCarried(new ItemStack(Items.QUARTZ));
            ActorControlTestHarness.field(BackpackOpenSession.class, "owner").set(cursorSession, h.player);
            ActorControlTestHarness.field(BackpackOpenSession.class, "level").set(cursorSession, h.level);
            ActorControlTestHarness.field(BackpackOpenSession.class, "menu").set(cursorSession, menu);
            check(cursorSession.close(context) == BackpackOpenSession.Status.FAILED && clicks[0] == 1
                    && menu.getCarried().is(Items.QUARTZ), "unsettled cursor stays visible instead of being dropped on close");
        }
        // 开包使用仍等待确认时取消，必须走物品停止接口，不能错误地退役为普通方块点击。
        try (var h = new InteractionWorldTestHarness()) {
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY);
            var session = new BackpackOpenSession(); var context = ClientRuntime.requireContext(h.player);
            var opening = context.actions().useItem(context, InteractionHand.MAIN_HAND, NativeConfirmation.pending(), 40);
            ActorControlTestHarness.field(BackpackOpenSession.class, "owner").set(session, h.player);
            ActorControlTestHarness.field(BackpackOpenSession.class, "level").set(session, h.level);
            ActorControlTestHarness.field(BackpackOpenSession.class, "opening").set(session, opening);
            h.nextTick(); session.cancel(ClientRuntime.requireContext(h.player));
            check(h.mode.releases == 1 && session.uncertain(), "cancellation releases native item use and preserves unknown open effects");
        }
        System.out.println("BackpackOpenSessionTest: passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
