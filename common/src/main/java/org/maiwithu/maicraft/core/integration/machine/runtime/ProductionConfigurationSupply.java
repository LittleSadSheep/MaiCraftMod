// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.client.server.ServerAssistClient;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Configuration;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;

/** Prepares real tools and consumables using the declared material policy after current configuration was checked. */
final class ProductionConfigurationSupply {
    private final LocalPlayer player;
    private final MachineProductionTaskRecord owner;
    private final ProductionWork work;
    private final SemanticMaterialSupplyCoordinator supply = new SemanticMaterialSupplyCoordinator();

    ProductionConfigurationSupply(LocalPlayer player, MachineProductionTaskRecord owner, ProductionWork work) {
        this.player = player; this.owner = owner; this.work = work;
    }

    boolean prepare(Configuration configuration) {
        if (supply.active()) {
            var state = supply.tick(player, work::advanceChild);
            work.extendDeadlineTo(supply.childDeadline());
            if (state.status() == SemanticMaterialSupplyCoordinator.Status.FAILED)
                throw new IllegalArgumentException("production_configuration_material_unavailable: " + state.message());
            if (supply.active()) return false;
        }
        JsonObject operations = ServerAssistClient.capabilityReport().getAsJsonObject("server_operations");
        JsonObject operation = operations == null ? null : operations.getAsJsonObject(configuration.operation());
        JsonObject limits = operation == null ? null : operation.getAsJsonObject("limits");
        JsonObject tools = limits == null ? null : limits.getAsJsonObject("required_tools");
        String action = configuration.arguments().get("action").getAsString();
        if (tools != null && tools.has(action)
                && !require(tools.get(action).getAsString(), 1, true, "machine configuration tool")) return false;
        JsonObject materials = limits == null ? null : limits.getAsJsonObject("required_materials");
        if (materials == null || !materials.has(action)) return true;
        JsonObject demand = materials.getAsJsonObject(action);
        int amount;
        try { amount = demand.get("amount").getAsBigDecimal().intValueExact(); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("production_configuration_material_amount_invalid"); }
        if (amount < 1 || amount > 64) throw new IllegalArgumentException("production_configuration_material_amount_invalid");
        return require(demand.get("item_id").getAsString(), amount, false, "machine configuration material");
    }

    private boolean require(String id, int amount, boolean offhand, String purpose) {
        ResourceLocation item = ResourceLocation.tryParse(id);
        if (item == null || !BuiltInRegistries.ITEM.containsKey(item))
            throw new IllegalArgumentException("production_configuration_material_unknown");
        int available = 0;
        for (var stack : player.getInventory().items)
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(item)) available += stack.getCount();
        if (offhand && !player.getOffhandItem().isEmpty()
                && BuiltInRegistries.ITEM.getKey(player.getOffhandItem().getItem()).equals(item)) available += player.getOffhandItem().getCount();
        if (available >= amount) return true;
        work.stopMovement();
        supply.begin(player, owner.getToolCallId(), owner.getDeadlineGameTime(),
                new SemanticMaterialSupplyCoordinator.Demand(List.of(item), amount, purpose),
                owner.toolPolicy, List.of(), false, owner.protectedLabels);
        return false;
    }

    void cancel() { supply.cancel(player); }
    boolean active() { return supply.active(); }
}
