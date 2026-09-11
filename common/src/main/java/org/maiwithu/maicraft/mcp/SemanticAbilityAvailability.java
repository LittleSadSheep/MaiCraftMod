// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** Catalog registration describes an implementation; a goal still needs its live prerequisites checked. */
final class SemanticAbilityAvailability {
    private SemanticAbilityAvailability() {}

    static void describe(JsonObject ability, String id, boolean createInstalled, boolean alive) {
        boolean readOnly = switch (id) {
            case "maicraft:remember_place", "maicraft:inspect_machine", "maicraft:design_machine",
                    "maicraft:design_build", "maicraft:wait_for_condition" -> true;
            default -> false;
        };
        boolean supported = !id.equals("maicraft:connect_mechanical_power") || createInstalled;
        ability.addProperty("registered", true);
        ability.addProperty("supported", supported);
        JsonArray preconditions = new JsonArray();
        preconditions.add("valid_goal_arguments");
        if (!readOnly) preconditions.add("current_body_control_and_native_action_conditions");
        if (id.contains("machine") || id.equals("maicraft:connect_mechanical_power"))
            preconditions.add("selected_machine_resources_range_and_permissions");
        ability.add("preconditions", preconditions);
        if (!supported) {
            ability.addProperty("available", false);
            ability.addProperty("unavailable_reason", "create_integration_not_installed");
        } else if (!readOnly && !alive) {
            ability.addProperty("available", false);
            ability.addProperty("unavailable_reason", "player_unavailable");
        } else {
            ability.add("available", JsonNull.INSTANCE);
            ability.addProperty("availability_status", "requires_goal_precondition_check");
        }
    }

    static void production(JsonObject ability, String id, JsonObject assistance) {
        if (!id.equals("maicraft:build_machine") && !id.equals("maicraft:design_machine")
                && !id.equals("maicraft:operate_machine")) return;
        JsonObject production = new JsonObject();
        production.addProperty("when", id.equals("maicraft:operate_machine") ? "operation=run_production" : "production supplied");
        JsonArray required = new JsonArray();
        boolean supported = true;
        JsonObject operations = assistance.getAsJsonObject("operations");
        for (String operation : new String[]{"machine.snapshot", "machine.recipe", "machine.connections",
                "machine.production_events", "inventory.quote", "inventory.transfer"}) {
            required.add(operation);
            JsonObject live = operations == null ? null : operations.getAsJsonObject(operation);
            supported &= live != null && live.get("supported").getAsBoolean()
                    && live.get("backend").getAsString().equals("server");
        }
        production.add("required_server_operations", required);
        production.addProperty("supported", supported);
        production.addProperty("additional_requirements", "manifest configuration operations must also be supported; materials, permissions and sustained output are verified during execution");
        ability.add("production_enhancement", production);
    }
}
