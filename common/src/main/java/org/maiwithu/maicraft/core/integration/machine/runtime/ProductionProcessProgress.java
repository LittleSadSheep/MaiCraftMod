// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Counts real completed recipe-output events for each declared process, including upstream intermediates. */
final class ProductionProcessProgress {
    private record Process(String producer, String recipe) {}
    private final Map<Process, List<String>> processes = new LinkedHashMap<>();
    private final Map<String, Long> counts = new LinkedHashMap<>();
    private String scope;
    private long sequence;
    private long latestTick = -1;

    ProductionProcessProgress(ProductionRunPlan plan) {
        plan.manifest().nodes().stream().filter(node -> node.kind().equals("process")).forEach(node -> {
            Process key = new Process(ProductionFlowPaths.key(plan.at(node)), node.recipeId());
            processes.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(node.id());
            counts.put(node.id(), 0L);
        });
    }

    void bind(String scope) {
        if (scope == null || scope.isBlank() || this.scope != null && !this.scope.equals(scope))
            throw new IllegalStateException("production_process_scope_changed");
        this.scope = scope;
    }

    void accept(JsonObject event) {
        if (scope == null || !scope.equals(ProductionFlowPaths.text(event, "scope"))
                || !ProductionFlowPaths.text(event, "kind").equals("recipe_output")) return;
        long next = ProductionEventCursor.number(event, "sequence");
        if (next <= sequence) return;
        sequence = next;
        if (ProductionEventCursor.number(event, "tick") < 0
                || !ProductionFlowPaths.text(event, "provenance").equals("native_recipe_output") || !completed(event)) return;
        String producer = ProductionFlowPaths.text(event, "producer"), recipe = ProductionFlowPaths.text(event, "recipe_id");
        List<String> matching = processes.getOrDefault(new Process(producer, recipe), List.of());
        // Aliased identical processes cannot both claim ownership of one physical completion.
        if (matching.size() != 1) return;
        String id = matching.getFirst(); counts.put(id, Math.incrementExact(counts.get(id)));
        latestTick = Math.max(latestTick, ProductionEventCursor.number(event, "tick"));
    }

    Map<String, Long> snapshot() { return Map.copyOf(counts); }
    long latestTick() { return latestTick; }

    private static boolean completed(JsonObject event) {
        if (positiveResources(event, "outputs")) return true;
        return event.has("completed") && event.get("completed").isJsonPrimitive()
                && event.getAsJsonPrimitive("completed").isBoolean() && event.get("completed").getAsBoolean()
                && event.has("outputs") && event.get("outputs").isJsonArray() && positiveResources(event, "inputs");
    }

    private static boolean positiveResources(JsonObject event, String field) {
        if (!event.has(field) || !event.get(field).isJsonArray()) return false;
        for (var raw : event.getAsJsonArray(field)) {
            if (!raw.isJsonObject()) continue;
            JsonObject output = raw.getAsJsonObject();
            if (!output.has("identity") || !output.get("identity").isJsonObject() || !output.has("amount")
                    || !output.get("amount").isJsonPrimitive() || !output.getAsJsonPrimitive("amount").isNumber()
                    || ProductionFlowPaths.text(output, "resource_id").isBlank()) continue;
            JsonObject identity = output.getAsJsonObject("identity");
            if (!ProductionFlowPaths.text(identity, "kind").isBlank() && !ProductionFlowPaths.text(identity, "id").isBlank()
                    && identity.has("components") && identity.get("components").isJsonObject()
                    && output.get("amount").getAsBigDecimal().signum() > 0) return true;
        }
        return false;
    }
}
