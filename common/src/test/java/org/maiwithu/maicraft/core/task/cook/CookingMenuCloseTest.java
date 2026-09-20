// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.SimpleContainerData;
import org.maiwithu.maicraft.core.task.menu.CloseMenuCompanionTask;
import org.maiwithu.maicraft.core.task.menu.CloseMenuTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 请求关炉子到实际关界面之间可能换菜单，关闭任务必须仍绑定原来的对象。 */
public final class CookingMenuCloseTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try (var world = new CookingTestWorld()) {
            var first = new FurnaceMenu(7, world.game.inventory, new SimpleContainer(3), new SimpleContainerData(4));
            var replacement = new FurnaceMenu(7, world.game.inventory, new SimpleContainer(3), new SimpleContainerData(4));
            world.game.player.containerMenu = replacement;
            var close = new CloseMenuCompanionTask(world.game.player, new CloseMenuTaskRecord("old-furnace", 100, first));
            close.start(world.game.player);
            check(close.tick(world.game.player) == TaskState.FAILED, "同类同编号的新菜单也不能被旧关闭任务操作");
            close.result(TaskState.FAILED);
            check(world.game.player.containerMenu == replacement, "拒绝后仍保留新菜单");
            check(((List<?>) CookingTestWorld.read(world.game.player.connection, "packets")).isEmpty(),
                    "绑定检查失败前不能发送关闭包");
            world.game.player.containerMenu = world.game.player.inventoryMenu;
            var alreadyClosed = new CloseMenuCompanionTask(world.game.player, new CloseMenuTaskRecord("already-closed", 100, first));
            check(alreadyClosed.tick(world.game.player) == TaskState.SUCCESS, "目标菜单已经关好时不重复发送请求");
        }
        System.out.println("CookingMenuCloseTest: passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
