// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.network.ServerOperationRegistry;
import org.maiwithu.maicraft.server.inventory.Ae2Access;
import org.maiwithu.maicraft.server.inventory.Ae2NetworkSnapshot;
import org.maiwithu.maicraft.server.inventory.Ae2Supply;
import org.maiwithu.maicraft.server.inventory.Ae2Crafting;
import org.maiwithu.maicraft.server.inventory.InventoryQuote;
import org.maiwithu.maicraft.server.inventory.InventoryTransfer;
import org.maiwithu.maicraft.server.machine.connectivity.ServerConnectionInspection;
import org.maiwithu.maicraft.server.machine.ae2.Ae2MachineConfiguration;

/** Common loader bootstrap; each advertised operation has a callable authoritative implementation. */
public final class ServerMachineOperations {
    private static boolean registered;
    private ServerMachineOperations() {}

    public static synchronized void register() {
        if (registered) return;
        JsonObject observation = new JsonObject();
        observation.addProperty("radius", ServerAccess.OBSERVATION_RADIUS);
        observation.addProperty("max_positions", 4);
        observation.addProperty("max_resources", 128);
        observation.addProperty("max_resource_offset", 4096);
        ServerOperationRegistry.register("machine.snapshot", 1, false, observation, ServerMachineSnapshot::inspect);
        ServerOperationRegistry.register("machine.recipe", 1, false, observation, ServerMachineRecipe::inspect);
        JsonObject events = observation.deepCopy();
        events.addProperty("max_events_per_page", 64);
        events.addProperty("max_event_chars", ProductionJournalEvent.MAX_CHARS);
        events.addProperty("retained_events_per_endpoint", ProductionHistoryIndex.RETAINED_EVENTS);
        events.addProperty("retained_chars_per_endpoint", ProductionHistoryIndex.RETAINED_CHARS);
        events.addProperty("retained_endpoints_per_connection", ProductionHistoryIndex.ENDPOINTS_PER_OWNER);
        events.addProperty("retained_endpoints_per_level", ProductionHistoryIndex.RETAINED_ENDPOINTS);
        events.addProperty("release_watch_supported", true);
        ServerOperationRegistry.register("machine.production_events", 1, false, events, ServerProductionEvents::inspect);
        JsonObject watch = new JsonObject();
        watch.addProperty("max_authorize_positions", 4); watch.addProperty("background_read_only", true);
        watch.addProperty("registration_requires_nearby_native_access", true);
        ServerOperationRegistry.register("machine.watch", 1, false, watch,
                org.maiwithu.maicraft.server.machine.watch.MachineWatchService::execute);
        ServerOperationRegistry.register("machine.connections", 1, false, observation, ServerConnectionInspection::inspect);
        JsonObject inventory = new JsonObject();
        inventory.addProperty("max_amount", 64);
        inventory.addProperty("max_machine_slots", 128);
        inventory.addProperty("real_player_inventory", true);
        inventory.addProperty("native_interaction_reach", true);
        ServerOperationRegistry.register("inventory.quote", 1, false, inventory, InventoryQuote::inspect);
        ServerOperationRegistry.register("inventory.transfer", 1, true, inventory, InventoryTransfer::execute);
        if (NativeApi.present(Ae2Access.ITEM)) {
            ServerOperationRegistry.register("inventory.ae2_network", 1, false, observation, Ae2NetworkSnapshot::inspect);
            ServerOperationRegistry.register("inventory.ae2_supply", 1, true, inventory, Ae2Supply::execute);
            JsonObject craft = new JsonObject();
            craft.addProperty("max_amount", 4096);
            craft.addProperty("max_active_jobs_per_player", 8);
            craft.addProperty("max_cpus_tracked", 128);
            craft.addProperty("native_interaction_reach", true);
            ServerOperationRegistry.register("inventory.ae2_craft_plan", 1, true, craft, Ae2Crafting::plan);
            ServerOperationRegistry.register("inventory.ae2_craft_status", 1, false, craft, Ae2Crafting::status);
            ServerOperationRegistry.register("inventory.ae2_craft_start", 1, true, craft, Ae2Crafting::start);
            ServerOperationRegistry.register("inventory.ae2_craft_cancel", 1, true, craft, Ae2Crafting::cancel);
        }
        JsonObject configuration = new JsonObject();
        configuration.addProperty("native_interaction_reach", true);
        JsonArray actions = new JsonArray();
        if (NativeApi.present("com.simibubi.create.foundation.blockEntity.SmartBlockEntity")) {
            actions.add("create.speed"); actions.add("create.filter");
        }
        if (NativeApi.present("mekanism.common.tile.base.TileEntityMekanism")) {
            for (String action : new String[]{"mekanism.side", "mekanism.eject", "mekanism.redstone",
                    "mekanism.connection", "mekanism.sorter_filter", "mekanism.sorter_auto_eject", "mekanism.sorter_single_item", "mekanism.sorter_round_robin"}) actions.add(action);
            JsonObject tools = new JsonObject(); tools.addProperty("mekanism.connection", "mekanism:configurator");
            configuration.add("required_tools", tools);
        }
        configuration.add("actions", actions);
        if (NativeApi.present(Ae2Access.ITEM)) {
            actions.add("ae2.pattern_install"); actions.add("ae2.bus_filter");
            JsonObject blank = new JsonObject(); blank.addProperty("item_id", "ae2:blank_pattern"); blank.addProperty("amount", 1);
            JsonObject materials = new JsonObject(); materials.add("ae2.pattern_install", blank);
            configuration.add("required_materials", materials);
        }
        if (!actions.isEmpty()) ServerOperationRegistry.register("machine.configure", 1, true, configuration, ServerMachineOperations::configure);
        JsonObject reading = observation.deepCopy(); reading.add("actions", actions.deepCopy());
        reading.addProperty("requires_materials", false); reading.addProperty("dispatches_interaction", false);
        ServerOperationRegistry.register("machine.configuration", 1, false, reading, ServerMachineConfiguration::inspect);
        registered = true;
    }

    private static JsonObject configure(ServerPlayer player, JsonObject body) {
        String action = ServerAccess.text(body, "action");
        BlockEntity entity = ServerAccess.check(player, ServerAccess.position(body.getAsJsonObject("position")), true);
        if (entity == null) throw ServerAccess.denied("target_missing", "Target has no machine block entity");
        JsonObject result;
        if (action.startsWith("create.")) result = CreateMachineAdapter.configure(player, entity, body);
        else if (action.startsWith("mekanism.")) result = MekanismMachineAdapter.configure(player, entity, body);
        else if (action.startsWith("ae2.")) result = Ae2MachineConfiguration.configure(player, entity, body);
        else throw ServerAccess.denied("unsupported", "Unknown machine configuration action");
        result.addProperty("operation", "machine.configure");
        result.addProperty("action", action);
        result.add("position", body.get("position").deepCopy());
        result.addProperty("dimension", player.serverLevel().dimension().location().toString());
        result.addProperty("tick", player.serverLevel().getGameTime());
        return result;
    }
}
