// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.task.base.BlockMenuCompanionTask;
import org.maiwithu.maicraft.core.task.base.BlockMenuFlow;
import org.maiwithu.maicraft.core.task.base.NativeSubmissionTaskRecord;
import org.maiwithu.maicraft.core.task.menu.VisibleMenuSession;
import org.maiwithu.maicraft.task.TaskState;
import static org.maiwithu.maicraft.client.actor.ActorControlTestHarness.*;

/** 原生界面回放：世界准备、所需菜单保留、终局收尾归属、延迟返料与工作站开局恢复。 */
public final class GuiRecoveryTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        worldRequestExitsPause(); requiredMenuIsKept(); cleanupWaitsForTransaction();
        oldCleanupLeavesNewScreen(); inventoryReturnIsNotRepeated(); workstationPreparesAfterClosing();
        System.out.println("GuiRecoveryTest: passed");
    }

    // 世界任务自行退出暂停，下一刻续做；不把页面占用交回模型，也不重复触发退出。
    private static void worldRequestExitsPause() throws Exception {
        var h = new ActorControlTestHarness(); int[] exits = {0};
        h.minecraft.screen = new PauseScreen(true) { @Override public void onClose() { exits[0]++; h.minecraft.screen = null; } };
        var menu = new VisibleMenuSession();
        check(!menu.worldReady(h.context) && exits[0] == 1 && !h.context.mutationAvailable(), "关页占用本刻操作机会");
        h.nextTick(true);
        check(menu.worldReady(h.context) && exits[0] == 1, "原世界任务取得关闭后的操作机会");
    }

    // 点击当前工作站的准备只等待绘制，不把正在存取的真实菜单误当作世界动作的挡路页面。
    private static void requiredMenuIsKept() throws Exception {
        var h = new ActorControlTestHarness(); var menu = menu(); show(h, menu);
        check(!h.actor.menus().ensureVisible(h.context), "工作站先等待绘制");
        for (int i = 0; i < 4; i++) h.nextTick(true);
        MenuVisibility.rendered(h.minecraft.screen);
        check(h.actor.menus().ensureVisible(h.context) && h.player.containerMenu == menu && h.minecraft.screen != null,
                "菜单操作保留当前容器，公共恢复不能每刻关掉它");
    }

    // 普通任务终局可排队收尾；旧搬运先结清，关闭一次后确认，再交给后继任务。
    private static void cleanupWaitsForTransaction() throws Exception {
        var h = new ActorControlTestHarness(); var menu = menu(); show(h, menu); int[] closes = {0};
        field(h.player.getClass(), "menuClose").set(h.player, (Runnable) () -> {
            closes[0]++; h.player.containerMenu = h.player.inventoryMenu; h.minecraft.screen = null;
        });
        var pending = MenuReceipt.forMenu(MenuReceipt.Kind.CLICK, h.context, menu, 0, 20, false, MenuConfirmation.pending());
        field(DefaultMenuPort.class, "active").set(h.actor.menus(), pending);
        check(h.actor.requestGuiCleanup(h.player), "登记终局收尾");
        h.nextTick(true); h.actor.advanceGuiCleanup(h.context);
        check(closes[0] == 0 && !pending.terminal(), "收尾不抢先结束原搬运回执");
        pending.finish(MenuReceipt.Status.CONFIRMED_APPLIED, "测试注入搬运确认");
        for (int i = 0; i < 16; i++) {
            h.nextTick(true); MenuVisibility.rendered(h.minecraft.screen); h.actor.menus().advance(h.context); h.actor.advanceGuiCleanup(h.context);
        }
        check(closes[0] == 1 && h.minecraft.screen == null
                && ((Map<?, ?>) h.actor.diagnosticState().get("gui_cleanup")).get("state").equals("completed"), "关页确认后终局收尾完成且不重复");
    }

    // 任务已经结束后玩家或下一流程打开了另一页，旧关闭请求撤销，不能落到新的页面上。
    private static void oldCleanupLeavesNewScreen() throws Exception {
        var h = new ActorControlTestHarness(); int[] exits = {0};
        h.minecraft.screen = new PauseScreen(true) { @Override public void onClose() { exits[0]++; } };
        check(h.actor.requestGuiCleanup(h.player), "旧页面登记收尾");
        Screen replacement = new PauseScreen(true) { @Override public void onClose() { exits[0]++; } };
        h.minecraft.screen = replacement; h.nextTick(true); h.actor.advanceGuiCleanup(h.context);
        check(exits[0] == 0 && h.minecraft.screen == replacement, "新页面归属变化使旧收尾失效");
    }

    // 页面消失早于服务器返料时仍等待同一次关闭；返料到达后才继续，新一轮余料可独立退出。
    private static void inventoryReturnIsNotRepeated() throws Exception {
        var h = new ActorControlTestHarness(); var contents = new SimpleContainer(5); NonNullList<Slot> slots = NonNullList.create();
        for (int i = 0; i < 5; i++) slots.add(new Slot(contents, i, 0, 0));
        field(AbstractContainerMenu.class, "slots").set(h.player.inventoryMenu, slots);
        h.player.inventoryMenu.getSlot(1).set(new ItemStack(Items.OAK_PLANKS));
        h.player.inventoryMenu.setCarried(new ItemStack(Items.DIAMOND)); h.minecraft.screen = h.inventoryScreen();
        int[] closes = {0}; field(h.player.getClass(), "menuClose").set(h.player, (Runnable) () -> { closes[0]++; h.minecraft.screen = null; });
        check(!h.actor.menus().ensureWorldVisible(h.context), "余料启动一次原生关闭");
        for (int i = 0; i < 8; i++) {
            h.nextTick(true); MenuVisibility.rendered(h.minecraft.screen); h.actor.menus().advance(h.context);
            check(!h.actor.menus().ensureWorldVisible(h.context), "画面已消失仍等鼠标和合成余料同步");
        }
        check(closes[0] == 1 && h.player.inventoryMenu.getCarried().is(Items.DIAMOND), "未返料时不重发关闭或清空物品");
        h.player.inventoryMenu.setCarried(ItemStack.EMPTY); h.player.inventoryMenu.getSlot(1).set(ItemStack.EMPTY);
        boolean ready = false;
        for (int i = 0; i < 8 && !ready; i++) { h.nextTick(true); h.actor.menus().advance(h.context); ready = h.actor.menus().ensureWorldVisible(h.context); }
        check(ready && closes[0] == 1, "同步结清后续同一世界操作");
        for (int i = 0; i < 45; i++) h.nextTick(true);
        h.player.inventoryMenu.getSlot(1).set(new ItemStack(Items.BIRCH_PLANKS));
        check(!h.actor.menus().ensureWorldVisible(h.context) && closes[0] == 2, "新余料使用新请求，不继承旧关闭期限");
    }

    // 附魔、切石共用的开局不能先以旧菜单失败；只在原生关页确认后盘点加工输入。
    private static void workstationPreparesAfterClosing() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var h = world.h; BlockPos at = new BlockPos(1, 1, 1); world.set(at, Blocks.STONE.defaultBlockState());
            var menu = menu(); show(h, menu); h.simulateMenuClose();
            var record = new Record(); record.setState(TaskState.RUNNING);
            var task = new Workstation(world, record, at); task.start(world.player);
            check(record.getState() == TaskState.RUNNING && task.prepared == 0, "接单时保留等待，尚不盘点输入");
            check(task.tick(world.player) == TaskState.RUNNING && task.prepared == 0, "首刻先安排关页");
            for (int i = 0; i < 16 && task.prepared == 0; i++) {
                MenuVisibility.rendered(h.minecraft.screen); world.nextTick(); h.actor.menus().advance(h.context); task.tick(world.player);
            }
            check(task.prepared == 1 && h.minecraft.screen == null, "返还并关闭后才准备本次加工材料");
            task.result(TaskState.CANCELLED);
        }
    }

    // 无窗口夹具保留真实菜单与原生端口，仅将窗口呈现及服务器关闭后的事实注入测试。
    private static AbstractContainerMenu menu() {
        return new AbstractContainerMenu(null, 7) {
            @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
            @Override public boolean stillValid(Player player) { return true; }
        };
    }
    private static void show(ActorControlTestHarness h, AbstractContainerMenu menu) throws Exception {
        h.player.containerMenu = menu; h.minecraft.screen = h.inventoryScreen(); field(AbstractContainerScreen.class, "menu").set(h.minecraft.screen, menu);
    }
    private static final class Record extends NativeSubmissionTaskRecord { Record() { super("gui-test", "gui-test", 1000, "gui-test"); } }
    private static final class Workstation extends BlockMenuCompanionTask<Record> {
        private final BlockPos at; private int prepared;
        Workstation(InteractionWorldTestHarness world, Record record, BlockPos at) { super(world.player, record); this.at = at; }
        @Override protected Names names() { return new Names("gui_", "gui", "workstation"); }
        @Override protected BlockPos targetPos() { return at; }
        @Override protected Block targetBlock() { return Blocks.STONE; }
        @Override protected String additionalStartBlocker() { return null; }
        @Override protected boolean prepareInputs() { prepared++; return true; }
        @Override protected boolean isTargetMenu(AbstractContainerMenu menu) { return false; }
        @Override protected boolean targetMenuOccupied(AbstractContainerMenu menu) { return false; }
        @Override protected boolean targetMenuEmpty(AbstractContainerMenu menu) { return true; }
        @Override protected BlockMenuFlow createFlow(AbstractContainerMenu menu) { throw new AssertionError("本回放不提交加工"); }
        @Override protected String boundaryLabel() { return "gui-test"; }
        @Override protected String successMessage() { return "gui-test"; }
    }
}
