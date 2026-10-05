// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.integration.backpack.BackpackMenuAccess;
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
            var close = new MenuReceipt(MenuReceipt.Kind.CLOSE, ClientRuntime.requireContext(h.player), 9, 0, -1, 40, false,
                    (context, receipt) -> MenuConfirmation.Verdict.PENDING);
            int[] clicks = {0};
            MenuPort port = (MenuPort) Proxy.newProxyInstance(MenuPort.class.getClassLoader(), new Class<?>[]{MenuPort.class}, (proxy, method, argv) -> {
                // 此回放没有在途搬运；鼠标余物仍须完整交给原生关闭，不能由执行器先清空。
                if (method.getName().equals("hasPendingTransaction")) return false;
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
            check(cursorSession.close(context) == BackpackOpenSession.Status.RUNNING && clicks[0] == 2
                    && menu.getCarried().is(Items.QUARTZ), "cursor remains intact until the native close owns its return");
            check(cursorSession.close(context) == BackpackOpenSession.Status.CLOSED && clicks[0] == 2,
                    "the same cursor close is settled without another request");
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
        // 穿戴包走模组原生协议；取消时退役该次请求，不能误发持物松手或重新提交开包。
        try (var h = new InteractionWorldTestHarness()) {
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY);
            var session = new BackpackOpenSession(); var context = ClientRuntime.requireContext(h.player);
            int[] submitted = {0};
            var opening = context.actions().submitControlProtocol(context, "backpack-open-fixture", () -> submitted[0]++, NativeConfirmation.pending(), 40);
            ActorControlTestHarness.field(BackpackOpenSession.class, "owner").set(session, h.player);
            ActorControlTestHarness.field(BackpackOpenSession.class, "level").set(session, h.level);
            ActorControlTestHarness.field(BackpackOpenSession.class, "opening").set(session, opening);
            h.nextTick(); session.cancel(ClientRuntime.requireContext(h.player));
            check(submitted[0] == 1 && h.mode.releases == 0 && opening.status() == NativeActionReceipt.Status.UNCERTAIN,
                    "cancelled worn open stays one unconfirmed protocol effect");
        }
        wrongBackpackIsSettledAndClosed(false);
        wrongBackpackIsSettledAndClosed(true);
        System.out.println("BackpackOpenSessionTest: passed");
    }
    // 开错了包且鼠标上有物品：先放进主背包空格并等放置回执，再原生关包；确认回到世界后以确定失败返回，不在那只包里取存。
    // 放置被明确拒绝时不再点第二次，直接原生关包交由原版返还，仍是确定结果，不会停在开错的包里。
    private static void wrongBackpackIsSettledAndClosed(boolean rejectPlacement) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY);
            var stray = ChestMenu.threeRows(9, h.inventory, new SimpleContainer(27)); h.player.containerMenu = stray;
            stray.setCarried(new ItemStack(Items.QUARTZ, 5)); h.inventory.setItem(0, new ItemStack(Items.STONE));
            var players = new LinkedHashMap<Integer, Integer>();
            for (int slot = 0; slot < 36; slot++) players.put(slot, slot < 9 ? 54 + slot : 27 + slot - 9);
            var view = new BackpackMenuAccess.Snapshot(stray, "backpack_menu:wrong", ItemStack.EMPTY, List.of(0), players, Map.of(), Map.of(), Set.of(), 0);
            var session = new BackpackOpenSession();
            ActorControlTestHarness.field(BackpackOpenSession.class, "owner").set(session, h.player);
            ActorControlTestHarness.field(BackpackOpenSession.class, "level").set(session, h.level);
            ActorControlTestHarness.field(BackpackOpenSession.class, "stray").set(session, stray);
            ActorControlTestHarness.field(BackpackOpenSession.class, "strayView").set(session, view);
            ActorControlTestHarness.field(BackpackOpenSession.class, "rejection").set(session, "native menu opened main::35 instead of main::8");
            var live = ClientRuntime.requireContext(h.player); int[] clicks = {0}, closes = {0};
            MenuPort port = (MenuPort) Proxy.newProxyInstance(MenuPort.class.getClassLoader(), new Class<?>[]{MenuPort.class}, (proxy, method, argv) -> {
                switch (method.getName()) {
                    case "hasPendingTransaction": return false;
                    case "ensureVisible": return true;
                    case "poll": return argv[1];
                    case "click": {
                        // 原生左键放下整叠：落点得到鼠标物品、鼠标清空，再用会话给出的确认条件结算这次放置。
                        clicks[0]++; int slot = (int) argv[1];
                        var receipt = new MenuReceipt(MenuReceipt.Kind.CLICK, live, 9, 0, slot, 20, false, (MenuConfirmation) argv[4]);
                        if (rejectPlacement) { receipt.finish(MenuReceipt.Status.CONFIRMED_NOT_APPLIED, "placement rejected fixture"); return receipt; }
                        stray.getSlot(slot).set(stray.getCarried().copy()); stray.setCarried(ItemStack.EMPTY);
                        var verdict = ((MenuConfirmation) argv[4]).observe(live, receipt);
                        receipt.finish(verdict == MenuConfirmation.Verdict.APPLIED ? MenuReceipt.Status.CONFIRMED_APPLIED : MenuReceipt.Status.DIVERGED, "placement fixture");
                        return receipt;
                    }
                    case "close": {
                        closes[0]++; h.player.containerMenu = h.player.inventoryMenu;
                        var receipt = new MenuReceipt(MenuReceipt.Kind.CLOSE, live, 9, 0, -1, 40, false, (c, r) -> MenuConfirmation.Verdict.PENDING);
                        receipt.finish(MenuReceipt.Status.CONFIRMED_APPLIED, "close fixture confirmed"); return receipt;
                    }
                    default: throw new AssertionError("unexpected menu action: " + method.getName());
                }
            });
            LocalPlayerContext context = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(),
                    new Class<?>[]{LocalPlayerContext.class}, (proxy, method, argv) -> method.getName().equals("menus") ? port : method.invoke(live, argv));
            var status = BackpackOpenSession.Status.RUNNING;
            for (int i = 0; i < 6 && status == BackpackOpenSession.Status.RUNNING; i++) status = session.open(context);
            check(status == BackpackOpenSession.Status.FAILED && session.wrongMenuClosed() && !session.uncertain(),
                    "a wrong backpack ends as a settled, closed and certain failure");
            check(clicks[0] == 1 && closes[0] == 1, "exactly one placement click precedes one native close");
            if (rejectPlacement) check(h.inventory.getItem(1).isEmpty() && "minecraft:quartzx5".equals(session.settlement().get("cursor_left_for_native_close"))
                    && String.valueOf(session.settlement().get("cursor_placement_unconfirmed")).startsWith("confirmed_not_applied"),
                    "a rejected placement hands the cursor to the native close and says so");
            else check(h.inventory.getItem(1).is(Items.QUARTZ) && h.inventory.getItem(1).getCount() == 5
                    && session.settlement().get("cursor_placements") instanceof List<?> placed && placed.size() == 1,
                    "the cursor stack lands in the first empty main slot and is listed as a confirmed placement");
            check(session.failure().contains("instead of main::8") && Boolean.TRUE.equals(session.settlement().get("wrong_menu_closed")),
                    "the receipt keeps the rejection detail and the confirmed close");
            check(session.open(context) == BackpackOpenSession.Status.FAILED && clicks[0] == 1 && closes[0] == 1, "a settled rejection is never replayed");
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
