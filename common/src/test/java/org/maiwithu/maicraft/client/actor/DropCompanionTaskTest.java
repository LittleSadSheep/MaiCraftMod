// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.ArrayList;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.inventory.DropCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.DropItemsTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 以真实菜单端口驱动丢弃任务，确认低头时不出手、余量只投一次、朝向包先发、未知操作不重发，以及确认分歧时报不确定而非确定失败。 */
public final class DropCompanionTaskTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        run(false, false); run(true, false); run(true, true);
        divergedConfirmationReportsUncertain();
        System.out.println("DropCompanionTaskTest: actual view, single partial toss, rotation order, cancellation and diverged confirmation passed");
    }

    /** 投掷已执行但菜单状态对不上冻结前后两侧时，回执必须标注不确定并说明可能已丢出，不能与背包实物相反。 */
    private static void divergedConfirmationReportsUncertain() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            world.enableCraftingTransactions(); world.position(new Vec3(8.5, 1, 8.5));
            var nativeDrops = world.h.simulateItemDrops();
            world.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            world.h.minecraft.screen = null; world.player.setXRot(80); world.player.setYRot(0);
            var record = new DropItemsTaskRecord("drop-test", 2000, Items.COBBLESTONE, 64, "cobblestone");
            var task = new DropCompanionTask(world.player, record); task.start(world.player);
            check(task.tick(world.player) == TaskState.RUNNING, "first look request cannot toss immediately");
            world.nextTick(); task.tick(world.player);
            // 夹具模拟镜头已实际到达抬头方向与背包已绘制；投掷走真实菜单端口与原生点击。
            world.player.setXRot(-15); world.player.setYRot(0); world.h.minecraft.screen = world.h.inventoryScreen();
            // 服务器侧同步一个既不等于提交前、也不等于预期后的第三种槽位状态，复刻"确认分歧但物品已消失"。
            world.mode.afterCraftingClick = () -> world.inventory.setItem(0, new ItemStack(Items.DIRT, 7));
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 400 && !state.isTerminal(); tick++) {
                world.nextTick(); MenuVisibility.rendered(world.h.minecraft.screen); DiscardedItems.observe(world.player);
                state = task.tick(world.player);
            }
            check(state == TaskState.FAILED, "diverged confirmation ends the task: " + task.result(state).toJson());
            var result = task.result(state).toJson();
            check(PlayerInv.count(world.inventory, Items.COBBLESTONE) == 0, "the stack really left the inventory before the divergence");
            check(nativeDrops.size() == 1 && nativeDrops.getFirst().getCount() == 64, "the native toss happened exactly once with the whole stack");
            check(result.contains("\"outcome_uncertain\":true"), "diverged drop is reported as uncertain, not a definite failure");
            check(result.contains("may have executed"), "the message states the click may have executed");
            check(!result.contains("was not confirmed"), "the old definite-failure wording is gone");
        } finally { DiscardedItems.observe(null); }
    }

    private static void run(boolean full, boolean cancel) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            world.enableCraftingTransactions(); world.position(new Vec3(8.5, 1, 8.5));
            var nativeDrops = world.h.simulateItemDrops();
            if (full) for (int slot = 0; slot < 36; slot++) world.inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
            world.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            world.h.minecraft.screen = null; world.player.setXRot(80); world.player.setYRot(0);
            var record = new DropItemsTaskRecord("drop-test", 2000, Items.COBBLESTONE, 40, "cobblestone");
            var task = new DropCompanionTask(world.player, record); task.start(world.player);
            check(task.tick(world.player) == TaskState.RUNNING, "first look request cannot toss immediately");
            world.nextTick(); task.tick(world.player);
            check(world.mode.menuClicks == 0, "downward view cannot submit any drop or split");
            // 夹具模拟镜头已实际到达抬头方向与背包已绘制；任务仍使用真实的可见性、单刻预算及原生菜单点击。
            world.player.setXRot(-15); world.player.setYRot(0); world.h.minecraft.screen = world.h.inventoryScreen();
            var tosses = new ArrayList<Integer>();
            world.mode.afterCraftingClick = () -> {
                int outstanding = 64 - PlayerInv.count(world.inventory, Items.COBBLESTONE)
                        - world.player.containerMenu.getCarried().getCount();
                if (outstanding > 0 && tosses.isEmpty()) {
                    check(outstanding == 40, "all forty items leave in one native toss"); tosses.add(outstanding);
                    var packets = world.h.connection.packets;
                    check(packets.getLast() instanceof ServerboundMovePlayerPacket.Rot rotation
                                    && rotation.getXRot(90) == -15 && rotation.getYRot(90) == 0,
                            "server receives the actual distant view before native menu drop");
                }
            };
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 400 && !state.isTerminal(); tick++) {
                world.nextTick(); MenuVisibility.rendered(world.h.minecraft.screen); DiscardedItems.observe(world.player);
                state = task.tick(world.player);
                if (cancel && !tosses.isEmpty()) {
                    int clicks = world.mode.menuClicks;
                    var result = task.result(TaskState.CANCELLED).toJson();
                    check(result.contains("\"outcome_uncertain\":true"), "unsettled drop remains explicitly uncertain on cancellation");
                    check(world.mode.menuClicks == clicks, "cancellation does not submit a second drop");
                    return;
                }
            }
            check(state == TaskState.SUCCESS, "drop task completes: " + task.result(state).toJson());
            var result = task.result(state).toJson();
            check(tosses.size() == 1 && PlayerInv.count(world.inventory, Items.COBBLESTONE) == 24, "one precise partial stack was dropped");
            check(nativeDrops.size() == 1 && nativeDrops.getFirst().getCount() == 40, "native drop entry received one stack of forty");
            check(result.contains("\"dropped\":40") && result.contains("\"drop_batches\":1"), "receipt reports confirmed amount and one batch");
            check(world.h.minecraft.screen == null && world.player.containerMenu.getCarried().isEmpty(), "successful task closes the inventory with no cursor residue");
        } finally { DiscardedItems.observe(null); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
