// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.FurnaceScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.SimpleContainerData;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.maiwithu.maicraft.core.mixin.MenuDataSlotsAccessor;
import org.maiwithu.maicraft.task.TaskState;

/** 同类菜单被替换时，烹饪不能重新认领它；待执行搬运也必须在读新槽位前停止。 */
public final class CookingMenuOwnershipTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        replacementStops(false);
        replacementStops(true);
        System.out.println("CookingMenuOwnershipTest: passed");
    }

    private static void replacementStops(boolean transferPrepared) throws Exception {
        try (var world = new CookingTestWorld()) {
            world.inventory(0, 16, 2);
            var task = new SemanticCookCompanionTask(world.game.player, world.request(16));
            task.start(world.game.player);
            task.tick(world.game.player);
            task.tick(world.game.player);
            // 从“原生开炉动作已结束，正在等同步菜单”进入；这里只模拟界面到达，不冒充一次真实鼠标点击。
            CookingTestWorld.phase(task, "WAIT_MENU");
            CookingTestWorld.set(task, "openRequested", true);
            var original = show(world);
            for (int i = 0; i < 5; i++) {
                MenuVisibility.rendered(Minecraft.getInstance().screen);
                check(task.tick(world.game.player) == TaskState.RUNNING, "等待菜单实际显示后继续");
                world.game.nextTick();
            }
            check(CookingTestWorld.read(task, "ownedMenu") == original, "菜单在等待显示期间只绑定一次");
            if (transferPrepared) {
                task.tick(world.game.player); // 确认原炉子为空。
                task.tick(world.game.player); // 创建绑定原菜单编号的装料子任务，尚未执行它。
                check(CookingTestWorld.read(task, "activeChild") != null, "应已准备装料子任务");
            }
            var replacement = show(world);
            check(task.tick(world.game.player) == TaskState.RUNNING, "先保存菜单丢失原因");
            check(task.tick(world.game.player) == TaskState.FAILED, "必须停止旧炉子工作");
            var result = task.result(TaskState.FAILED);
            check(CookingTestWorld.read(task, "activeChild") == null, "旧装料子任务不能继续操作替代菜单");
            check(world.game.player.containerMenu == replacement, "烹饪收尾不能关闭新菜单");
            check(((List<?>) CookingTestWorld.read(world.game.player.connection, "packets")).isEmpty(),
                    "菜单替换后不得发送点击或关闭请求");
            check(result.data().get("outcome_uncertain").equals(transferPrepared),
                    "只有已经进入待确认搬运阶段才留下可能影响库存的不确定性");
        }
    }

    private static TestMenu show(CookingTestWorld world) {
        var menu = new TestMenu(world, new SimpleContainerData(4));
        world.game.player.containerMenu = menu;
        Minecraft.getInstance().screen = new FurnaceScreen(menu, world.game.inventory, Component.literal("test furnace"));
        return menu;
    }

    private static final class TestMenu extends FurnaceMenu implements MenuDataSlotsAccessor {
        private final List<DataSlot> synchronizedData;
        TestMenu(CookingTestWorld world, SimpleContainerData data) {
            super(7, world.game.inventory, new SimpleContainer(3), data);
            synchronizedData = List.of(DataSlot.forContainer(data, 0), DataSlot.forContainer(data, 1),
                    DataSlot.forContainer(data, 2), DataSlot.forContainer(data, 3));
        }
        @Override public List<DataSlot> maicraft$dataSlots() { return synchronizedData; }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
