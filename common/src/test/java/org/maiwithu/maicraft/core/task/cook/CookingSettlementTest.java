// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireCompanionTask;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;

/** 父目标完成只允许收尾已开始的炉次；取消等待中的炉次必须报告仍在炉内的材料。 */
public final class CookingSettlementTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        parentSatisfactionStopsNewBatches();
        try (var world = new CookingTestWorld()) {
            world.inventory(0, 16, 2);
            var task = new SemanticCookCompanionTask(world.game.player, world.request(16));
            task.start(world.game.player);
            task.requestSatisfiedSettlement();
            check(task.tick(world.game.player) == TaskState.RUNNING, "先经过无新批次的收尾");
            check(task.tick(world.game.player) == TaskState.SUCCESS, "父目标满足后不再坚持烧足自己的旧数量");
            var settled = task.result(TaskState.SUCCESS);
            check(Boolean.FALSE.equals(settled.data().get("goal_satisfied"))
                    && Boolean.TRUE.equals(settled.data().get("stopped_because_parent_satisfied")),
                    "完成收尾不等于这炉真的烧出了原要求数量");
            check(CookingTestWorld.read(task, "candidate") == null && world.game.blockUses() == 0,
                    "尚未开始的炉次不能在收尾请求之后才选配方或打开炉子");

            var waiting = new SemanticCookCompanionTask(world.game.player, world.request(16));
            CookingTestWorld.phase(waiting, "WAIT_CLOSED");
            CookingTestWorld.set(waiting, "effectsStarted", true);
            CookingTestWorld.set(waiting, "stationClaimed", true);
            CookingTestWorld.set(waiting, "batchOutstanding", true);
            CookingTestWorld.set(waiting, "ownedInputLoaded", 16);
            var result = waiting.result(TaskState.CANCELLED);
            check(Boolean.TRUE.equals(result.data().get("outcome_uncertain"))
                    && Boolean.TRUE.equals(result.data().get("batch_outstanding"))
                    && result.data().get("owned_input_loaded").equals(16), "取消不能抹掉关着炉子里的未结材料");

            var menu = new FurnaceMenu(9, world.game.inventory, new SimpleContainer(3), new SimpleContainerData(4));
            menu.setCarried(new ItemStack(Items.IRON_INGOT, 3));
            world.game.player.containerMenu = menu;
            var interrupted = new SemanticCookCompanionTask(world.game.player, world.request(16));
            CookingTestWorld.set(interrupted, "ownedMenu", menu);
            CookingTestWorld.set(interrupted, "openedMenu", true);
            interrupted.result(TaskState.CANCELLED);
            check(world.game.player.containerMenu == menu && menu.getCarried().getCount() == 3,
                    "父任务取消不能关闭子任务为保留鼠标物品而留下的菜单");
            check(((List<?>) CookingTestWorld.read(world.game.player.connection, "packets")).isEmpty(),
                    "这些收尾边界不应再发送新的原生操作");
        }
        System.out.println("CookingSettlementTest: passed");
    }

    private static void parentSatisfactionStopsNewBatches() throws Exception {
        try (var world = new CookingTestWorld()) {
            world.inventory(256, 44, 6);
            var request = new SemanticAcquireTaskRecord("alternative-cook", 100000,
                    List.of(CookingTestWorld.id("iron_ingot"), CookingTestWorld.id("gold_ingot")),
                    300, List.of(Source.COOK), false, SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
            var parent = new SemanticAcquireCompanionTask(world.game.player, request);
            parent.start(world.game.player);
            for (int i = 0; i < 4; i++) { parent.tick(world.game.player); world.game.nextTick(); }
            var cook = (SemanticCookCompanionTask) CookingTestWorld.read(parent, "activeChild");
            // 本批装料已确认并关着炉子等待时，玩家又得到另一种可替代成品，父目标因此提前满足。
            CookingTestWorld.phase(cook, "WAIT_CLOSED");
            CookingTestWorld.set(cook, "stationClaimed", true);
            CookingTestWorld.set(cook, "effectsStarted", true);
            CookingTestWorld.set(cook, "batchOutstanding", true);
            CookingTestWorld.set(cook, "ownedInputLoaded", 44);
            world.inventory(256, 0, 6);
            world.game.inventory.setItem(10, new ItemStack(Items.GOLD_INGOT, 44));
            check(parent.tick(world.game.player) == TaskState.RUNNING
                    && Boolean.TRUE.equals(CookingTestWorld.read(cook, "parentSatisfied")),
                    "真实取物父任务应要求原炉次收尾，而不是让烹饪继续追赶铁锭数量");
            // 在剩料归还和关闭已确认的控制器边界继续，不能再创建第二批或误报烧足了三百铁锭。
            world.inventory(256, 44, 6);
            world.game.inventory.setItem(10, new ItemStack(Items.GOLD_INGOT, 44));
            CookingTestWorld.invoke(cook, "finishCleanup");
            check(parent.tick(world.game.player) == TaskState.SUCCESS
                    && !((Boolean) CookingTestWorld.read(cook, "batchOutstanding")), "结清原炉次后父任务才成功");
            check(world.game.blockUses() == 0 && world.game.itemUses() == 0, "该场景只验证协调，不冒充原生加工回执");
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
