// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.chat.ChatMessage;
import static org.maiwithu.maicraft.client.actor.ActorControlTestHarness.*;

/** 无窗口回放挡路页面退出、在途搬运结清和关箱确认；发送只计数，不连接游戏服务器。 */
public final class ChatGuiPreparationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        closesPauseAndOldChat();
        for (boolean inventory : new boolean[]{false, true}) waitsForContainerSettlement(inventory);
        waitsForScreenExitWithoutRepeatedClose();
        refusedScreenExitIsBounded();
        nativeCloseFailureDoesNotSend();
        System.out.println("ChatGuiPreparationTest: passed");
    }

    // 明确拒绝退出的模组页面不能让无总期限聊天永久等待，也不能反复关闭或偷偷发送。
    private static void refusedScreenExitIsBounded() throws Exception {
        var h = new ActorControlTestHarness();
        var screen = new RefusingPauseScreen(); h.minecraft.screen = screen;
        var view = new View();
        var session = new ChatSession(new ChatMessage("x", 50), view, () -> true);
        session.tick(h.context, 0);
        for (int i = 0; i < 40; i++) advance(h, session);
        check(session.status() == ChatSession.Status.FAILED && screen.closes == 1 && view.sent == 0
                && session.detail().contains("RefusingPauseScreen")
                && Boolean.FALSE.equals(session.evidence().get("mechanical_retry_allowed")), "真实拒绝退出有界结束且不重试或发送");
    }

    // 暂停菜单与旧草稿先各自退出，关页和开聊天不能同刻执行，完整消息仍只发送一次。
    private static void closesPauseAndOldChat() throws Exception {
        for (boolean oldChat : new boolean[]{false, true}) {
            var h = new ActorControlTestHarness();
            int[] closes = {0};
            h.minecraft.screen = oldChat ? new ChatScreen("未提交的旧草稿") {
                @Override public void onClose() { closes[0]++; h.minecraft.screen = null; }
            } : new PauseScreen(true) {
                @Override public void onClose() { closes[0]++; h.minecraft.screen = null; }
            };
            var view = new View();
            var session = new ChatSession(new ChatMessage("x", 50), view, () -> true);
            session.tick(h.context, 0);
            check(closes[0] == 1 && h.minecraft.screen == null && !view.active
                    && !h.context.mutationAvailable() && session.status() == ChatSession.Status.TYPING,
                    "先走页面退出并消耗本刻操作额度，不能把界面阻挡直接判为失败");
            advance(h, session);
            check(view.active && view.sent == 0 && closes[0] == 1, "下一刻继续原聊天，不重复退出页面");
            finish(h, session);
            check(session.status() == ChatSession.Status.SUBMITTED && view.sent == 1, "关页后只提交一次完整消息");
        }
    }

    // 箱子或背包鼠标上还有物品时，先等待旧搬运回执，再原生关闭；未结清关闭前不预约消息提交。
    private static void waitsForContainerSettlement(boolean inventory) throws Exception {
        var h = new ActorControlTestHarness();
        var menu = inventory ? h.player.inventoryMenu : menu();
        menu.setCarried(new ItemStack(Items.DIAMOND));
        h.player.containerMenu = menu;
        h.minecraft.screen = h.inventoryScreen();
        field(AbstractContainerScreen.class, "menu").set(h.minecraft.screen, menu);
        int[] closes = {0}, submissions = {0};
        field(h.player.getClass(), "menuClose").set(h.player, (Runnable) () -> {
            check(menu.getCarried().is(Items.DIAMOND), "执行器不能先清掉鼠标物品，必须交给原生关闭");
            closes[0]++; h.player.inventoryMenu.setCarried(ItemStack.EMPTY);
            h.player.containerMenu = h.player.inventoryMenu; h.minecraft.screen = null;
        });
        var port = h.actor.menus();
        var pending = MenuReceipt.forMenu(MenuReceipt.Kind.CLICK, h.context, menu,
                0, 20, false, MenuConfirmation.pending());
        field(DefaultMenuPort.class, "active").set(port, pending);
        var view = new View();
        var session = new ChatSession(new ChatMessage("x", 50), view, () -> { submissions[0]++; return true; });
        session.tick(h.context, 0);
        check(closes[0] == 0 && !view.active && h.context.mutationAvailable() && !pending.terminal(),
                "旧搬运尚未确认时只等待，不关箱、不终止旧回执");
        pending.finish(MenuReceipt.Status.CONFIRMED_APPLIED, "测试注入已确认搬运");
        advance(h, session);
        check(port.hasPendingTransaction() && closes[0] == 0 && !view.active,
                "旧交易结清后登记一次关闭，并等页面实际展示");
        for (int i = 0; i < 16 && !view.active; i++) {
            check(submissions[0] == 0 && view.sent == 0, "关箱准备不应预约或提交消息");
            advance(h, session);
        }
        check(view.active && closes[0] == 1 && !port.hasPendingTransaction(), "确认关箱后才打开同一聊天");
        check(((Map<?, ?>) session.evidence().get("gui_preparation")).get("menu_close_state").equals("confirmed_applied"),
                "回执保留已确认关闭，不能只给笼统界面阻挡错误");
        finish(h, session);
        check(view.sent == 1 && submissions[0] == 1 && closes[0] == 1, "延迟关箱不重发消息或原生关闭");
    }

    // 模组页面的退出需要后续更新时，保留原任务等待页面消失，不每刻再触发一次退出。
    private static void waitsForScreenExitWithoutRepeatedClose() throws Exception {
        var h = new ActorControlTestHarness();
        int[] closes = {0};
        h.minecraft.screen = new PauseScreen(true) {
            @Override public void onClose() { closes[0]++; }
        };
        var view = new View();
        var session = new ChatSession(new ChatMessage("x", 50), view, () -> true);
        session.tick(h.context, 0);
        advance(h, session); advance(h, session);
        check(closes[0] == 1 && !view.active && session.status() == ChatSession.Status.TYPING,
                "页面尚未退出时等待原退出操作，不反复关闭或返回界面阻挡失败");
        h.minecraft.screen = null; advance(h, session); finish(h, session);
        check(view.sent == 1, "退出完成后无需重建任务即可发送原消息");
    }

    // 原生关闭中菜单被替换，保留不确定回执；不能改写新菜单或声称消息已经发送。
    private static void nativeCloseFailureDoesNotSend() throws Exception {
        var h = new ActorControlTestHarness();
        h.player.containerMenu = menu(); h.minecraft.screen = h.inventoryScreen();
        field(AbstractContainerScreen.class, "menu").set(h.minecraft.screen, h.player.containerMenu);
        var view = new View();
        var session = new ChatSession(new ChatMessage("x", 50), view, () -> true);
        session.tick(h.context, 0);
        var replacement = menu(); h.player.containerMenu = replacement;
        advance(h, session);
        check(session.status() == ChatSession.Status.FAILED && !view.active && view.sent == 0
                && h.player.containerMenu == replacement && session.detail().contains("TestMenu")
                && Boolean.TRUE.equals(session.evidence().get("outcome_uncertain"))
                && Boolean.FALSE.equals(session.evidence().get("mechanical_retry_allowed")),
                "真实关闭失败应保留新菜单和未知效果，不强制成功或允许机械重试");
    }

    // 使用真实菜单回执和可见性等待；只把无窗口环境中的关箱与输入框展示替换为可计数的事实。
    private static AbstractContainerMenu menu() {
        return new TestMenu();
    }
    // 具名菜单让退出失败回执能指出当前容器，保留原生菜单身份变化与关闭确认的测试边界。
    private static final class TestMenu extends AbstractContainerMenu {
        private TestMenu() { super(null, 7); }
        @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return true; }
    }
    // 模拟模组暂停页收到原生退出请求后仍占用画面，核对超时诊断指向真正挡路的页面。
    private static final class RefusingPauseScreen extends PauseScreen {
        private int closes;
        private RefusingPauseScreen() { super(true); }
        @Override public void onClose() { closes++; }
    }
    private static void advance(ActorControlTestHarness h, ChatSession session) throws Exception {
        h.nextTick(true); MenuVisibility.rendered(h.minecraft.screen); h.actor.menus().advance(h.context);
        session.tick(h.context, h.tick * 100_000_000L);
    }
    private static void finish(ActorControlTestHarness h, ChatSession session) throws Exception {
        for (int i = 0; i < 8 && session.status() == ChatSession.Status.TYPING; i++) advance(h, session);
    }
    private static final class View implements ChatSession.View {
        boolean active;
        String text = "";
        int sent;
        public boolean canOpen(Minecraft minecraft) { return minecraft.screen == null && !active; }
        public void open(Minecraft minecraft, String draft) { active = true; text = draft; }
        public boolean active() { return active; }
        public String text() { return text; }
        public void write(String draft) { text = draft; }
        public void submit() { sent++; }
        public void close() { active = false; }
    }
}
