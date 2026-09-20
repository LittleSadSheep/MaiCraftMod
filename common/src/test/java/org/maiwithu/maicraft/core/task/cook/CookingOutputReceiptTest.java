// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 收货量来自原生搬运回执；等待点击期间继续烧好的物品和背包只装下一部分都要如实记账。 */
public final class CookingOutputReceiptTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        verify(12, false);
        verify(3, true);
        inputReturnWhileCooking();
        System.out.println("CookingOutputReceiptTest: passed");
    }

    private static void verify(int moved, boolean partial) throws Exception {
        try (var world = new CookingTestWorld()) {
            world.inventory(0, 16, 2);
            var task = new SemanticCookCompanionTask(world.game.player, world.request(16));
            task.start(world.game.player); task.tick(world.game.player); task.tick(world.game.player);
            var data = new SimpleContainerData(4); data.set(0, 1000); data.set(3, 200);
            var contents = new SimpleContainer(3);
            contents.setItem(0, new ItemStack(Items.RAW_IRON, 8));
            contents.setItem(2, new ItemStack(Items.IRON_INGOT, 8));
            var menu = new CookingTestWorld.Furnace(world, contents, data);
            world.game.player.containerMenu = menu;
            CookingTestWorld.set(task, "ownedMenu", menu); CookingTestWorld.set(task, "openedMenu", true);
            CookingTestWorld.set(task, "stationClaimed", true); CookingTestWorld.set(task, "effectsStarted", true);
            CookingTestWorld.set(task, "batchOutstanding", true); CookingTestWorld.ledger(task).begin(16);
            CookingTestWorld.phase(task, "RECONCILE");
            task.tick(world.game.player);
            // 此边界代表低层已完成原生确认；十二件场景比最初看到的八件更多，三件场景则受容量限制。
            int produced = partial ? 8 : 12;
            contents.setItem(0, new ItemStack(Items.RAW_IRON, 16 - produced));
            contents.setItem(2, produced == moved ? ItemStack.EMPTY : new ItemStack(Items.IRON_INGOT, produced - moved));
            world.inventory(moved, 0, 0);
            CookingTestWorld.set(task, "activeChild", new ConfirmedOutput(moved, partial));
            task.tick(world.game.player);
            task.tick(world.game.player);
            check(CookingTestWorld.ledger(task).collected() == moved, "父任务必须记录真正收到的数量");
            if (partial) {
                check(CookingTestWorld.read(task, "failureCode").equals("cooking_output_partial"), "保留部分收货并说明余量仍在炉里");
                check(Boolean.TRUE.equals(CookingTestWorld.read(task, "batchOutstanding")), "收到三件已明确，但剩余炉次仍未结清");
            }
            else {
                check(Boolean.FALSE.equals(CookingTestWorld.read(task, "outcomeUncertain")), "完整确认收货不应产生额外不确定性");
                task.tick(world.game.player);
                check(CookingTestWorld.read(task, "phase").toString().equals("CLOSE_WAIT"), "新增十二件仍符合本炉守恒，应继续等待剩余四件");
            }
        }
    }

    private record ConfirmedOutput(int moved, boolean partial) implements Task {
        @Override public TaskState tick(LocalPlayer player) { return partial ? TaskState.FAILED : TaskState.SUCCESS; }
        @Override public void stop(LocalPlayer player, StopReason reason) {}
        @Override public TaskResult result(TaskState state) {
            var data = Map.<String, Object>of("moved_counts", List.of(moved), "outcome_uncertain", false);
            return partial ? TaskResult.fail("native inventory capacity exhausted", data) : TaskResult.ok("confirmed output", data);
        }
        @Override public String name() { return "confirmed output transfer"; }
    }

    private static void inputReturnWhileCooking() throws Exception {
        try (var world = new CookingTestWorld()) {
            world.inventory(0, 16, 2);
            var task = new SemanticCookCompanionTask(world.game.player, world.request(16));
            task.start(world.game.player); task.tick(world.game.player); task.tick(world.game.player);
            var values = new SimpleContainerData(4);
            var contents = new SimpleContainer(3);
            contents.setItem(0, new ItemStack(Items.RAW_IRON, 16));
            var menu = new CookingTestWorld.Furnace(world, contents, values); world.game.player.containerMenu = menu;
            CookingTestWorld.set(task, "ownedMenu", menu); CookingTestWorld.set(task, "openedMenu", true);
            CookingTestWorld.set(task, "stationClaimed", true); CookingTestWorld.set(task, "effectsStarted", true);
            CookingTestWorld.set(task, "batchOutstanding", true); CookingTestWorld.ledger(task).begin(16);
            CookingTestWorld.set(task, "cleanupSnapshotReady", true);
            CookingTestWorld.set(task, "cleanupInputExpected", contents.getItem(0).copy());
            task.requestSatisfiedSettlement(); CookingTestWorld.phase(task, "CLEANUP");
            world.inventory(0, 0, 0); task.tick(world.game.player);
            // 原生退料真正确认前又烧好两份，只退回十四份；两种去向加起来仍是原来的十六份。
            contents.setItem(0, ItemStack.EMPTY); contents.setItem(2, new ItemStack(Items.IRON_INGOT, 2));
            world.inventory(0, 14, 0);
            CookingTestWorld.set(task, "activeChild", new ConfirmedOutput(14, false));
            task.tick(world.game.player); task.tick(world.game.player); task.tick(world.game.player);
            check(CookingTestWorld.ledger(task).returned() == 14, "按回执记实际退料十四份");
            check(CookingTestWorld.read(task, "activePurpose").toString().equals("TAKE_OUTPUT")
                    && CookingTestWorld.read(task, "failureMessage") == null, "另外两件成品应继续正常收回，不能误判数量被外部改动");
        }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
