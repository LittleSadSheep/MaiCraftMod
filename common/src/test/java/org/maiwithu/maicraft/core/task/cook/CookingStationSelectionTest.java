// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.menu.CloseMenuTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 第一台被占用时，先关自己开的菜单，再试另一台；保护位置与已认领炉次不能被候选切换绕过。 */
public final class CookingStationSelectionTest {
    private static final BlockPos FIRST = new BlockPos(1, 1, 3), SECOND = new BlockPos(3, 1, 3);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var world = new CookingTestWorld()) {
            world.inventory(0, 16, 2); world.game.set(SECOND, Blocks.FURNACE.defaultBlockState());
            var task = new SemanticCookCompanionTask(world.game.player, world.request(16));
            task.start(world.game.player); task.tick(world.game.player); task.tick(world.game.player);
            check(FIRST.equals(CookingTestWorld.read(task, "stationPos")), "先观察最近的炉子");
            var contents = new SimpleContainer(3); contents.setItem(0, new ItemStack(Items.RAW_IRON, 8));
            var menu = new CookingTestWorld.Furnace(world, contents, new SimpleContainerData(4));
            world.game.player.containerMenu = menu;
            CookingTestWorld.set(task, "ownedMenu", menu); CookingTestWorld.set(task, "openedMenu", true);
            CookingTestWorld.phase(task, "VALIDATE");
            task.tick(world.game.player);
            var closing = (CloseMenuTaskRecord) CookingTestWorld.read(task, "activeRecord");
            check(closing.expectedMenu == menu && CookingTestWorld.read(task, "failureMessage") == null,
                    "忙炉只是候选不可用，先关闭这份菜单，不把整个加工目标判失败");
            check(contents.getItem(0).getCount() == 8, "不得取用忙炉里别人的原料");
            // 从原生关闭已经确认的边界继续，验证父任务选新位置而不是回到同一个被排除的炉子。
            world.game.player.containerMenu = world.game.player.inventoryMenu;
            CookingTestWorld.set(task, "activeChild", new ClosedMenu());
            task.tick(world.game.player); task.tick(world.game.player);
            check(SECOND.equals(CookingTestWorld.read(task, "stationPos")), "随后应选择另一台已加载炉子");
            check(CookingTestWorld.read(task, "phase").toString().equals("OPEN"), "近处空炉候选继续正常打开");
        }
        try (var world = new CookingTestWorld()) {
            world.inventory(0, 16, 2); world.game.set(SECOND, Blocks.FURNACE.defaultBlockState());
            var task = new SemanticCookCompanionTask(world.game.player, world.request(16));
            task.start(world.game.player); task.tick(world.game.player);
            NavigationSafetyContext.withProtectedArea(List.of(FIRST), List.of(), () -> task.tick(world.game.player));
            check(SECOND.equals(CookingTestWorld.read(task, "stationPos")), "不能因为最近就选择已明确禁止使用的炉子");
        }
        System.out.println("CookingStationSelectionTest: passed");
    }
    private static final class ClosedMenu implements Task {
        @Override public TaskState tick(LocalPlayer player) { return TaskState.SUCCESS; }
        @Override public void stop(LocalPlayer player, StopReason reason) {}
        @Override public TaskResult result(TaskState state) { return TaskResult.ok("the selected menu closed"); }
        @Override public String name() { return "confirmed workstation close"; }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
