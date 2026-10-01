// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.maiwithu.maicraft.core.integration.machine.MachineMenu;
import org.maiwithu.maicraft.core.inventory.InventoryComponentFacts;
import org.maiwithu.maicraft.task.TaskResult;

/** 真正结束任务时顺手读取当前身体、库存和可见菜单，不让下一次决策先消耗一轮工具调用补基础事实。 */
final class FailureObservation {
    private FailureObservation() {}

    static TaskResult attach(TaskResult failure, LocalPlayer player) {
        var data = new LinkedHashMap<String, Object>(failure.data() == null ? Map.of() : failure.data());
        var observed = new LinkedHashMap<String, Object>();
        try {
            // 这是结束当刻的只读观察，既不执行恢复动作，也不把当前库存差额冒充本任务的产出归因。
            observed.put("game_time", player.level().getGameTime());
            observed.put("dimension", player.level().dimension().location().toString());
            observed.put("position", Map.of("x", player.getX(), "y", player.getY(), "z", player.getZ()));
            observed.put("body", Map.of("health", player.getHealth(), "food", player.getFoodData().getFoodLevel(),
                    "experience_level", player.experienceLevel, "alive", player.isAlive()));
            observed.put("inventory", InventoryComponentFacts.inventory(player.getInventory().items, player.registryAccess()));
            observed.put("equipment", InventoryComponentFacts.inventory(player.getInventory().armor, player.registryAccess()));
            observed.put("offhand", InventoryComponentFacts.inventory(player.getInventory().offhand, player.registryAccess()));
        } catch (RuntimeException unavailable) {
            observed.put("body_or_inventory_unavailable", unavailable.toString());
        }
        try {
            // 工作台未关或游标未结清时，默认回执直接带出原生槽位；已经关闭的界面不为了观察再次打开。
            if (player.containerMenu != player.inventoryMenu || MenuVisibility.matches(Minecraft.getInstance(), player.containerMenu))
                observed.put("menu", MachineMenu.inspect(player));
        } catch (RuntimeException unavailable) {
            observed.put("menu_unavailable", unavailable.toString());
        }
        data.put("failure_observation", Map.copyOf(observed));
        return failure.withData(Map.copyOf(data));
    }
}
