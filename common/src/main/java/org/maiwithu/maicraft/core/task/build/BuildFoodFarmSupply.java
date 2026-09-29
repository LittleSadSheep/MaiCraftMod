// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.core.inventory.FoodMaterialBudget;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** 现货不能作为口粮时才查附近农田；普通采收与必要面包合成沿用授权来源，完成后自动回原工位。 */
final class BuildFoodFarmSupply {
    private final SemanticMaterialSupplyCoordinator supply = new SemanticMaterialSupplyCoordinator();
    private long retryAt;
    private Map<String, Object> receipt = Map.of();
    boolean active() { return supply.active(); }
    boolean canAttempt(LocalPlayer player, BuildTaskRecord owner, FoodMaterialBudget budget) {
        if (active()) return true;
        var policy = owner.toolSupply();
        return player.level().getGameTime() >= retryAt && policy.policy() != MaterialPolicy.INVENTORY_ONLY
                && SemanticMaterialSupplyCoordinator.resolveSources(policy.policy(), policy.sources()).contains(Source.HARVEST)
                && !choices(budget).isEmpty();
    }
    private static List<ResourceLocation> choices(FoodMaterialBudget budget) {
        // 这是当前已接通的农作物及面包加工出口；AE 现货选择不受这份农田能力清单限制。
        return List.of(Items.CARROT, Items.POTATO, Items.BEETROOT, Items.BREAD).stream()
                .filter(item -> budget.complete() && !budget.held().contains(item) && budget.reserved().getOrDefault(item, 0L) == 0)
                .map(BuiltInRegistries.ITEM::getKey).toList();
    }
    SemanticMaterialSupplyCoordinator.Tick tick(LocalPlayer player, BuildTaskRecord owner, FoodMaterialBudget budget,
            Function<Task, TaskState> runner) {
        if (!active()) {
            // 每次只备四份；限定为原任务许可中的采收/合成，不能借补食去挖矿、狩猎或花钱。
            var allowed = SemanticMaterialSupplyCoordinator.resolveSources(owner.toolSupply().policy(), owner.toolSupply().sources());
            var sources = List.of(Source.INVENTORY, Source.HARVEST, Source.CRAFT).stream().filter(allowed::contains).toList();
            supply.begin(player, owner.getToolCallId() + "/food-farm", owner.getDeadlineGameTime(),
                    new SemanticMaterialSupplyCoordinator.Demand(choices(budget), 4, "ordinary crop food after replanting"),
                    MaterialPolicy.ORDINARY, sources, false, owner.toolSupply().protectedLabels(),
                    owner.materialSupplyProtection(), SemanticMaterialSupplyCoordinator.ReturnPolicy.RETURN_ORIGIN);
        }
        var result = supply.tick(player, runner);
        if (result.status() != SemanticMaterialSupplyCoordinator.Status.RUNNING) {
            receipt = result.receipt(); retryAt = player.level().getGameTime() + 1200;
        }
        return result;
    }
    void stop(LocalPlayer player) { if (active()) { supply.cancel(player); retryAt = player.level().getGameTime() + 1200; } }
    void pause(LocalPlayer player) { supply.pause(player); }
    Map<String, Object> progress() { return Map.of("active", active(), "progress", supply.progress(), "receipt", receipt); }
}
