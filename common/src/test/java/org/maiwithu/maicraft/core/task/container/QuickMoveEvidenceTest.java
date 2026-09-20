// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.container;

import java.lang.reflect.Proxy;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.actor.MenuPort;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.task.TaskState;

/** 用原版 ChestMenu 快速搬运验证部分容量；源格先同步也不能先报告整堆已经进背包。 */
public final class QuickMoveEvidenceTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var f = new Fixture()) {
            for (int slot = 0; slot < 36; slot++) f.world.inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
            f.world.inventory.setItem(0, new ItemStack(Items.STONE_BRICKS, 57));
            f.begin();
            f.menu.quickMoveStack(f.world.player, 0);
            check(f.confirmation.observe(f.context, f.receipt) == MenuConfirmation.Verdict.APPLIED, "原生七件搬运应能得到精确确认");
            f.finish();
            check(f.task.resultData().get("moved_counts").equals(List.of(7)), "背包只容七件时不能记成整堆六十四件");
            var phase = f.task.getClass().getDeclaredField("phase"); phase.setAccessible(true);
            check(phase.get(f.task).toString().equals("FAILING"), "部分快速搬运不能把完整要求报成成功");
            var active = f.task.getClass().getDeclaredField("receipt"); active.setAccessible(true); active.set(f.task, null);
            f.task.result(TaskState.FAILED);
            check(f.world.player.containerMenu == f.menu, "组合搬运失败后仍把菜单留给父任务核对部分数量");
        }
        try (var f = new Fixture()) {
            f.begin();
            f.stock.setItem(0, new ItemStack(Items.STONE_BRICKS, 57));
            check(f.confirmation.observe(f.context, f.receipt) == MenuConfirmation.Verdict.PENDING,
                    "源格先减少不能替代玩家背包增加的确认");
            f.world.inventory.setItem(0, new ItemStack(Items.STONE_BRICKS, 7));
            check(f.confirmation.observe(f.context, f.receipt) == MenuConfirmation.Verdict.APPLIED, "两侧七件变化齐备后再确认");
        }
        try (var f = new Fixture()) {
            f.stock.setItem(0, ItemStack.EMPTY);
            f.world.inventory.setItem(0, new ItemStack(Items.STONE_BRICKS, 64));
            var evidence = new QuickMoveEvidence(f.world.player, f.menu, 54);
            f.menu.quickMoveStack(f.world.player, 54);
            check(evidence.observe() == MenuConfirmation.Verdict.APPLIED && evidence.moved() == 64,
                    "玩家向箱子存入也应核对两侧实际数量");
        }
        try (var world = new InteractionWorldTestHarness()) {
            var slots = new SimpleContainer(3);
            slots.setItem(2, new ItemStack(Items.IRON_INGOT));
            var menu = new FurnaceMenu(8, world.inventory, slots, new SimpleContainerData(4));
            world.player.containerMenu = menu;
            var evidence = new QuickMoveEvidence(world.player, menu, 2);
            // 模拟服务端把成品放进背包后又补出了下一件；源格仍是一件，不等于这一笔没有搬动。
            world.inventory.setItem(0, new ItemStack(Items.IRON_INGOT, 4));
            check(evidence.observe() == MenuConfirmation.Verdict.APPLIED && evidence.moved() == 4,
                    "生产结果槽按真实背包增量记账，不能被下一件产出遮住");
            world.inventory.clearContent();
            slots.setItem(0, new ItemStack(Items.RAW_IRON, 16));
            var returned = new QuickMoveEvidence(world.player, menu, 0);
            slots.setItem(0, ItemStack.EMPTY);
            slots.setItem(2, new ItemStack(Items.IRON_INGOT, 2));
            world.inventory.setItem(0, new ItemStack(Items.RAW_IRON, 14));
            check(returned.observe() == MenuConfirmation.Verdict.APPLIED && returned.moved() == 14 && returned.remaining() == 0,
                    "退料时两份已经烧成，实际返回十四份由父炉次账继续核对");
            world.inventory.clearContent();
            // 原版换区会先查炉子是否能处理泥土；提供真正的空配方表，不以未初始化客户端代替这项判断。
            var recipes = ClientPacketListener.class.getDeclaredField("recipeManager"); recipes.setAccessible(true);
            recipes.set(world.player.connection, new RecipeManager(RegistryAccess.EMPTY));
            var connection = ClientLevel.class.getDeclaredField("connection"); connection.setAccessible(true);
            connection.set(world.level, world.player.connection);
            world.inventory.setItem(9, new ItemStack(Items.DIRT, 12));
            var reordered = new QuickMoveEvidence(world.player, menu, 3);
            menu.quickMoveStack(world.player, 3);
            check(reordered.observe() == MenuConfirmation.Verdict.APPLIED && reordered.moved() == 12,
                    "原版拒绝把泥土放入炉子而改为背包内换区时，也要按真实十二件变化报告");
        }
        System.out.println("QuickMoveEvidenceTest: passed");
    }

    private static final class Fixture implements AutoCloseable {
        final InteractionWorldTestHarness world = new InteractionWorldTestHarness();
        final SimpleContainer stock = new SimpleContainer(27);
        final ChestMenu menu = ChestMenu.threeRows(55, world.inventory, stock);
        final ContainerTransferTaskRecord.Move move = new ContainerTransferTaskRecord.Move(0, -1, 0);
        final ContainerTransferCompanionTask task;
        final LocalPlayerContext context;
        MenuConfirmation confirmation;
        MenuReceipt receipt;
        Fixture() throws Exception {
            world.player.containerMenu = menu;
            stock.setItem(0, new ItemStack(Items.STONE_BRICKS, 64));
            task = new ContainerTransferCompanionTask(world.player,
                    new ContainerTransferTaskRecord("quick-capacity", 1000, 55, List.of(move), false));
            MenuPort port = (MenuPort) Proxy.newProxyInstance(MenuPort.class.getClassLoader(), new Class<?>[]{MenuPort.class},
                    (proxy, method, args) -> {
                        check(method.getName().equals("click") && args[3] == ClickType.QUICK_MOVE, "只允许原来这一笔快速移动");
                        confirmation = (MenuConfirmation) args[4];
                        var constructor = MenuReceipt.class.getDeclaredConstructor(MenuReceipt.Kind.class, LocalPlayerContext.class,
                                int.class, int.class, int.class, boolean.class, MenuConfirmation.class); constructor.setAccessible(true);
                        receipt = (MenuReceipt) constructor.newInstance(MenuReceipt.Kind.CLICK, args[0], 55, 0, 20, false, confirmation);
                        return receipt;
                    });
            context = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(), new Class<?>[]{LocalPlayerContext.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "player" -> world.player;
                        case "menus" -> port;
                        case "bodyEpoch", "controlRevision", "tickRevision" -> 1L;
                        default -> throw new AssertionError(method.getName());
                    });
        }
        void begin() throws Exception {
            var bound = task.getClass().getDeclaredField("menu"); bound.setAccessible(true); bound.set(task, menu);
            var begin = task.getClass().getDeclaredMethod("beginMove", ContainerTransferTaskRecord.Move.class);
            begin.setAccessible(true); begin.invoke(task, move);
            var quick = task.getClass().getDeclaredMethod("submitQuick", LocalPlayerContext.class, ContainerTransferTaskRecord.Move.class);
            quick.setAccessible(true); quick.invoke(task, context, move);
        }
        void finish() throws Exception {
            var after = task.getClass().getDeclaredMethod("afterConfirmedClick"); after.setAccessible(true); after.invoke(task);
        }
        @Override public void close() throws Exception { world.close(); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
