// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.task.TaskState;

/** 炉子原料与成品包可以分开到达；只读等待补齐，不用“迟到的一半状态”认领或拒绝整炉。 */
public final class CookingSynchronizationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        verify(true, false); verify(false, false); verify(true, true);
        System.out.println("CookingSynchronizationTest: passed");
    }

    private static void verify(boolean inputFirst, boolean neverMatches) throws Exception {
        try (var world = new CookingTestWorld()) {
            world.inventory(0, 16, 2);
            var task = new SemanticCookCompanionTask(world.game.player, world.request(16));
            task.start(world.game.player); task.tick(world.game.player); task.tick(world.game.player);
            var contents = new SimpleContainer(3);
            contents.setItem(0, new ItemStack(Items.RAW_IRON, inputFirst ? 15 : 16));
            if (!inputFirst) contents.setItem(2, new ItemStack(Items.IRON_INGOT));
            var menu = new CookingTestWorld.Furnace(world, contents, new SimpleContainerData(4));
            world.game.player.containerMenu = menu;
            CookingTestWorld.set(task, "ownedMenu", menu); CookingTestWorld.set(task, "openedMenu", true);
            CookingTestWorld.set(task, "stationClaimed", true); CookingTestWorld.set(task, "effectsStarted", true);
            CookingTestWorld.set(task, "batchOutstanding", true); CookingTestWorld.ledger(task).begin(16);
            CookingTestWorld.phase(task, "RECONCILE");
            check(task.tick(world.game.player) == TaskState.RUNNING && CookingTestWorld.read(task, "activeChild") == null
                    && CookingTestWorld.read(task, "failureCode") == null, "第一份包不完整时只等待，不关菜单、不拿东西");
            if (neverMatches) {
                for (int i = 0; i < 5 && CookingTestWorld.read(task, "failureCode") == null; i++) {
                    world.game.nextTick(); task.tick(world.game.player);
                }
                check("workstation_output_externally_changed".equals(CookingTestWorld.read(task, "failureCode")),
                        "持续不守恒时仍须拒绝，等待不能变成绕过归属检查");
            } else {
                contents.setItem(0, new ItemStack(Items.RAW_IRON, 15));
                contents.setItem(2, new ItemStack(Items.IRON_INGOT));
                world.game.nextTick(); task.tick(world.game.player);
                check(CookingTestWorld.read(task, "failureCode") == null
                        && CookingTestWorld.read(task, "activePurpose").toString().equals("TAKE_OUTPUT"),
                        "两份包守恒后继续正常收货，不误报外部改动");
            }
            check(world.game.blockUses() == 0 && world.game.itemUses() == 0, "这些观察本身不操作物品");
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
