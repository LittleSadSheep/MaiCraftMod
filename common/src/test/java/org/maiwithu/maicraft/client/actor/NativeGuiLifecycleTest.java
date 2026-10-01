// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.InBedChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.sleep.SleepCompanionTask;
import org.maiwithu.maicraft.core.task.sleep.SleepTaskRecord;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import static org.maiwithu.maicraft.client.actor.ActorControlTestHarness.*;

/** 使用原版床上界面及真实起床包入口，回放上床任务终局、世界准备和父流程借用页面的归属。 */
public final class NativeGuiLifecycleTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        sleepCompletionDoesNotWake(); worldPreparationLeavesSleepAlone(); finishedChildDoesNotCloseParentMenu();
        System.out.println("NativeGuiLifecycleTest: passed");
    }

    // 只确认躺下的任务结束后睡眠仍在继续；不得由公共收尾调用床上界面的 onClose 发起起床。
    private static void sleepCompletionDoesNotWake() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            InBedChatScreen screen = bedScreen(world);
            field(world.player.getClass(), "sleeping").setBoolean(world.player, true);
            var task = new SleepCompanionTask(world.player, new SleepTaskRecord("sleep-ui", 1000, BlockPos.ZERO));
            check(task.tick(world.player) == TaskState.SUCCESS && task.keepsGuiOnCompletion(), "已上床的短任务保留原生睡眠");
            task.result(TaskState.SUCCESS);
            check(!world.actor.requestGuiCleanup(world.player), "床上界面不登记自动退出");
            world.nextTick(); world.actor.advanceGuiCleanup(world.h.context);
            check(world.h.minecraft.screen == screen && world.player.isSleeping() && wakePackets(world) == 0,
                    "上床任务完成后不发起床包，界面交给自然醒处理");
        }
    }

    // 床上聊天框不是普通聊天框；任何自动世界准备都不得退出它，睡眠状态包提前到来也保持静止。
    private static void worldPreparationLeavesSleepAlone() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            InBedChatScreen screen = bedScreen(world);
            for (boolean sleeping : new boolean[]{false, true}) {
                field(world.player.getClass(), "sleeping").setBoolean(world.player, sleeping);
                check(!new GuiPreparation().ready(world.h.context, false)
                        && !world.h.actor.menus().ensureWorldVisible(world.h.context), "床上页面不被聊天或移动的自动准备关闭");
                check(!DefaultBodyControlPort.permitsWorldMovement(screen), "床上聊天框不放行旧导航输入");
                check(wakePackets(world) == 0 && world.h.minecraft.screen == screen, "界面退出不会被当作安全的普通清理");
                world.nextTick();
            }
        }
    }

    // 村民或机器菜单可能刚由子动作打开；连续完成子步骤后仍须保留页面，直到取放物品并由所属流程明确关闭。
    private static void finishedChildDoesNotCloseParentMenu() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            world.enableCraftingTransactions(); world.inventory.setItem(0, new ItemStack(Items.STONE, 5));
            Screen parentScreen = world.h.minecraft.screen;
            var parentMenu = world.player.containerMenu;
            for (int i = 0; i < 8; i++) {
                finishChildAndAdvance(world, parentScreen); world.h.context.menus().ensureVisible(world.h.context);
            }
            check(world.h.context.menus().ensureVisible(world.h.context), "父菜单连续保持可见后可继续点击");
            // 真正进入原版菜单点击：先拿起快捷栏石头，再等待版本同步和绘制，子动作终局不能触发关页重开。
            var pickup = world.h.context.menus().click(world.h.context, 36, 0, ClickType.PICKUP,
                    (context, receipt) -> context.player().containerMenu.getCarried().is(Items.STONE)
                            && context.player().containerMenu.getCarried().getCount() == 5
                            && context.player().containerMenu.getSlot(36).getItem().isEmpty()
                            ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING, 20);
            for (int i = 0; i < 8; i++) finishChildAndAdvance(world, parentScreen);
            check(pickup.status() == MenuReceipt.Status.CONFIRMED_APPLIED, "页面保留到原生拿取确认");
            var replace = world.h.context.menus().click(world.h.context, 36, 0, ClickType.PICKUP,
                    (context, receipt) -> context.player().containerMenu.getCarried().isEmpty()
                            && context.player().containerMenu.getSlot(36).getItem().is(Items.STONE)
                            && context.player().containerMenu.getSlot(36).getItem().getCount() == 5
                            ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING, 20);
            for (int i = 0; i < 8; i++) finishChildAndAdvance(world, parentScreen);
            check(replace.status() == MenuReceipt.Status.CONFIRMED_APPLIED && world.mode.menuClicks == 2
                    && world.player.containerMenu == parentMenu, "连续取放各点击一次，同一菜单未被关闭后重开");
            // 取放完成后，下一步确实要回到世界；保留旧修复的原生关闭并续做能力。
            boolean ready = false;
            for (int i = 0; i < 16 && !ready; i++) {
                ready = world.h.context.menus().ensureWorldVisible(world.h.context);
                if (!ready) {
                    world.nextTick(); MenuVisibility.rendered(world.h.minecraft.screen);
                    world.actor.menus().advance(world.h.context);
                }
            }
            check(ready && world.h.minecraft.screen == null && world.inventory.getItem(0).getCount() == 5,
                    "仅在明确回到世界的阶段关闭遗留页面，物品完整保留并继续原流程");
        }
    }

    // 每刻模拟一个默认不声明保留界面的子任务结算，再推进真实菜单端口；用持续回放发现秒关、重开的循环。
    private static void finishChildAndAdvance(InteractionWorldTestHarness world, Screen parentScreen) throws Exception {
        new FinishedStep(world).result(TaskState.SUCCESS);
        world.nextTick(); MenuVisibility.rendered(parentScreen);
        world.actor.menus().advance(world.h.context); world.actor.advanceGuiCleanup(world.h.context);
        check(world.h.minecraft.screen == parentScreen, "子步骤完成后父页面持续保留，不进入反复开关循环");
    }

    private static InBedChatScreen bedScreen(InteractionWorldTestHarness world) throws Exception {
        // 只省略窗口初始化；onClose 仍是原版实现，可直接发现任何意外 STOP_SLEEPING 包。
        var screen = new InBedChatScreen(); field(Screen.class, "minecraft").set(screen, world.h.minecraft);
        world.h.minecraft.screen = screen; return screen;
    }
    private static long wakePackets(InteractionWorldTestHarness world) {
        return world.h.connection.packets.stream().filter(packet -> packet instanceof ServerboundPlayerCommandPacket command
                && command.getAction() == ServerboundPlayerCommandPacket.Action.STOP_SLEEPING).count();
    }
    private static final class Record extends TaskRecord { Record() { super("gui-lifecycle", "gui-lifecycle", 1000); } }
    private static final class FinishedStep extends AbstractCompanionTask<Record> {
        FinishedStep(InteractionWorldTestHarness world) { super(world.player, new Record()); }
        @Override protected TaskState onTick() { return TaskState.SUCCESS; }
        @Override protected String successMessage() { return "native child completed"; }
    }
}
