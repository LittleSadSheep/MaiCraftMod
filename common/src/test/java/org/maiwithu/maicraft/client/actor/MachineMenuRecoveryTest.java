// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.integration.machine.MachineMenu;
import org.maiwithu.maicraft.core.integration.machine.MachineMenuTransferTask;
import org.maiwithu.maicraft.core.integration.machine.MachineMenuTransferTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 真实菜单点击回放：同一箱子库存同步后继续投料，失效身份和真正缺料仍交回完整现场。 */
public final class MachineMenuRecoveryTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        movedSourceContinues(); splitStacksAndCapacity(true); splitStacksAndCapacity(false);
        knownPartialStaysKnown(); rejectedPickupStaysUnapplied();
        missingMaterialIncludesMenu(); replacedMenuCannotBorrowReceipt();
        System.out.println("MachineMenuRecoveryTest: passed");
    }

    private static ChestMenu open(InteractionWorldTestHarness h) throws Exception {
        h.enableCraftingTransactions();
        var menu = ChestMenu.threeRows(4, h.inventory);
        h.player.containerMenu = menu;
        h.h.minecraft.screen = new ContainerScreen(menu, h.inventory, Component.literal("测试箱子"));
        BlockPos at = new BlockPos(2, 1, 2); h.set(at, Blocks.CHEST.defaultBlockState());
        MachineMenu.rememberNativeOpened(h.player, menu, at);
        return menu;
    }

    private static MachineMenuTransferTask task(InteractionWorldTestHarness h, String receipt) {
        return new MachineMenuTransferTask(h.player, new MachineMenuTransferTaskRecord("transfer", 1000,
                receipt, "deposit", 0, ResourceLocation.parse("minecraft:stone"), 3));
    }

    private static void movedSourceContinues() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            ChestMenu menu = open(h); h.inventory.setItem(0, new ItemStack(Items.STONE, 5));
            String receipt = MachineMenu.inspect(h.player).get("menu_receipt_id").getAsString();
            // 普通生产同步改变其他格，等待超过旧期限也没有换机器；原目标仍可按当前背包完成。
            menu.getSlot(1).set(new ItemStack(Items.DIRT)); menu.incrementStateId();
            for (int i = 0; i < 610; i++) h.nextTick();
            var task = task(h, receipt); task.start(h.player);
            check(task.tick(h.player) == TaskState.RUNNING, "原菜单观察不因普通同步或等待过期而失败");
            h.inventory.setItem(0, ItemStack.EMPTY); h.inventory.setItem(9, new ItemStack(Items.STONE, 5));
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 100 && !state.isTerminal(); tick++) {
                h.nextTick(); MenuVisibility.rendered(h.h.minecraft.screen); state = task.tick(h.player);
            }
            check(state == TaskState.SUCCESS && menu.getSlot(0).getItem().getCount() == 3
                    && h.inventory.countItem(Items.STONE) == 2 && menu.getCarried().isEmpty(), "移槽后同任务完成精确搬运");
            check(task.result(state).success() && h.mode.menuClicks == 5, "原生拾取、逐件放入和返还各执行一次");
        }
    }

    private static void missingMaterialIncludesMenu() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            open(h); String receipt = MachineMenu.inspect(h.player).get("menu_receipt_id").getAsString();
            var task = task(h, receipt); task.start(h.player);
            TaskState state = task.tick(h.player); var result = task.result(state);
            check(state == TaskState.FAILED && h.mode.menuClicks == 0 && result.data().containsKey("menu_report"),
                    "真正缺料带出全部当前菜单，不先关闭现场再要求模型补查");
            check(Boolean.FALSE.equals(result.data().get("outcome_uncertain")), "未提交动作不是未知消费");
        }
    }

    private static void splitStacksAndCapacity(boolean deposit) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            ChestMenu menu = open(h);
            if (deposit) {
                h.inventory.setItem(0, new ItemStack(Items.STONE, 20)); h.inventory.setItem(1, new ItemStack(Items.STONE, 20));
            } else {
                for (int i = 0; i < 36; i++) h.inventory.setItem(i, new ItemStack(Items.DIRT, 64));
                h.inventory.setItem(0, new ItemStack(Items.STONE, 63)); h.inventory.setItem(1, new ItemStack(Items.STONE, 62));
                menu.getSlot(0).set(new ItemStack(Items.STONE, 3));
            }
            String receipt = MachineMenu.inspect(h.player).get("menu_receipt_id").getAsString();
            var task = new MachineMenuTransferTask(h.player, new MachineMenuTransferTaskRecord("split", 1000,
                    receipt, deposit ? "deposit" : "withdraw", 0, ResourceLocation.parse("minecraft:stone"), deposit ? 40 : 3));
            task.start(h.player); TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 100 && !state.isTerminal(); tick++) {
                h.nextTick(); MenuVisibility.rendered(h.h.minecraft.screen); state = task.tick(h.player);
            }
            // 两叠材料或两格剩余容量都在同一请求内结清，实际背包与机器合计守恒。
            check(state == TaskState.SUCCESS && task.result(state).success() && menu.getCarried().isEmpty(), "分堆搬运无需重发任务");
            check(menu.getSlot(0).getItem().getCount() == (deposit ? 40 : 0)
                    && h.inventory.countItem(Items.STONE) == (deposit ? 0 : 128), "精确数量经过真实点击守恒");
        }
    }

    private static void replacedMenuCannotBorrowReceipt() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            open(h); String receipt = MachineMenu.inspect(h.player).get("menu_receipt_id").getAsString();
            open(h); h.inventory.setItem(0, new ItemStack(Items.STONE, 5));
            var task = task(h, receipt); task.start(h.player); TaskState state = task.tick(h.player);
            check(state == TaskState.FAILED && h.mode.menuClicks == 0, "重新打开的另一菜单不能复用旧槽位身份");
            task.result(state);
        }
    }

    private static void knownPartialStaysKnown() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            ChestMenu menu = open(h); h.inventory.setItem(0, new ItemStack(Items.STONE, 20)); h.inventory.setItem(1, new ItemStack(Items.STONE, 20));
            var task = new MachineMenuTransferTask(h.player, new MachineMenuTransferTaskRecord("partial", 1000,
                    MachineMenu.inspect(h.player).get("menu_receipt_id").getAsString(), "deposit", 0, ResourceLocation.parse("minecraft:stone"), 40));
            task.start(h.player); TaskState state = TaskState.RUNNING;
            var placed = task.getClass().getDeclaredField("placed"); placed.setAccessible(true);
            for (int tick = 0; tick < 100 && !state.isTerminal(); tick++) {
                // 第一叠结清后机器被外部填满，第二叠尚未拿起；已确认二十件仍是确定效果。
                if (placed.getInt(task) == 20) menu.getSlot(0).set(new ItemStack(Items.STONE, 64));
                h.nextTick(); MenuVisibility.rendered(h.h.minecraft.screen); state = task.tick(h.player);
            }
            var result = task.result(state);
            check(state == TaskState.FAILED && result.data().get("confirmed_destination_count").equals(20)
                    && result.data().get("actual_player_delta").equals(-20) && Boolean.FALSE.equals(result.data().get("outcome_uncertain")), "已确认前缀不因后续容量不足变成未知");
            check(Boolean.FALSE.equals(result.data().get("mechanical_retry_allowed")) && menu.getCarried().isEmpty(), "整单不能盲重试已完成部分");
        }
    }

    private static void rejectedPickupStaysUnapplied() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            ChestMenu menu = open(h); h.inventory.setItem(0, new ItemStack(Items.STONE, 5));
            var task = task(h, MachineMenu.inspect(h.player).get("menu_receipt_id").getAsString()); task.start(h.player);
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 20 && h.mode.menuClicks == 0; tick++) {
                MenuVisibility.rendered(h.h.minecraft.screen); state = task.tick(h.player); h.nextTick();
            }
            // 服务端同步撤销本地预测，原槽与游标回到点击前；这不是超时未知，也不自动重发拒绝的点击。
            h.inventory.setItem(0, new ItemStack(Items.STONE, 5)); menu.setCarried(ItemStack.EMPTY); menu.incrementStateId();
            for (int tick = 0; tick < 50 && !state.isTerminal(); tick++) { h.nextTick(); state = task.tick(h.player); }
            var result = task.result(state);
            check(state == TaskState.FAILED && h.mode.menuClicks == 1 && Boolean.FALSE.equals(result.data().get("outcome_uncertain"))
                    && result.data().get("native_receipt_status").equals("confirmed_not_applied"), "原生明确未执行的点击不能报未知");
        }
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
}
