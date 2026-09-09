// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.core.integration.machine.MachineBlueprintDocument;

/** Typed structure data is inspected by its blueprint schema, not by execution-script key filters. */
public final class BlueprintGoalData {
    private BlueprintGoalData() {}

    /** Returns a detached inspection view. The actual goal keeps its complete blueprint and evidence. */
    public static JsonObject instructionView(Goal goal) {
        JsonObject view = goal.toJson();
        stripValidatedBlueprints(goal, view);
        return view;
    }

    private static void stripValidatedBlueprints(Goal goal, JsonObject view) {
        JsonObject parameters = view.getAsJsonObject("parameters");
        JsonElement operation = parameters.get("operation");
        boolean declared = MachineAbilityAdapter.DESIGN.equals(goal.ability())
                || MachineAbilityAdapter.BUILD.equals(goal.ability())
                || MachineAbilityAdapter.MODIFY.equals(goal.ability()) && operation != null
                    && operation.isJsonPrimitive() && operation.getAsJsonPrimitive().isString()
                    && "apply_blueprint".equals(operation.getAsString());
        if (declared && parameters.has("blueprint")) {
            JsonElement blueprint = parameters.get("blueprint");
            if (!blueprint.isJsonObject()) throw new IllegalArgumentException("blueprint must be an object");
            MachineBlueprintDocument.validateWire(blueprint.getAsJsonObject());
            parameters.remove("blueprint");
        }
        for (int i = 0; i < goal.children().size(); i++)
            stripValidatedBlueprints(goal.children().get(i), view.getAsJsonArray("children").get(i).getAsJsonObject());
    }
}
