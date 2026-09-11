// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.Set;

/** Bounded observation pages retain their native ticks and never turn inventory into production proof. */
final class MachineObservationPages {
    static final int MAX_CHARS = 98_304;
    final JsonArray pages = new JsonArray();
    final Set<String> incomplete = new LinkedHashSet<>();
    private int chars;

    boolean append(JsonObject page, String requestId) {
        if (!page.has("schema") || !page.get("schema").getAsString().equals("maicraft.machine_snapshot.v1"))
            throw new IllegalArgumentException("unexpected server machine snapshot schema");
        JsonObject frozen = page.deepCopy();
        frozen.addProperty("request_id", requestId);
        int size = frozen.toString().length();
        if (pages.size() >= 64 || chars + size > MAX_CHARS) {
            incomplete.add("report_budget_exhausted");
            return false;
        }
        chars += size;
        pages.add(frozen);
        if (!page.has("complete") || !page.get("complete").getAsBoolean()) incomplete.add("native_observation_incomplete");
        return true;
    }

    JsonObject report(int selected, int observed, int nextComponent, long anchorTick) {
        if (pages.isEmpty()) incomplete.add("no_native_observation_returned");
        JsonObject result = new JsonObject();
        result.addProperty("state", incomplete.isEmpty() ? "observed" : "partial");
        result.addProperty("provenance", pages.isEmpty() ? "no_server_observation" : "server_native");
        result.addProperty("complete", incomplete.isEmpty());
        result.addProperty("structural_anchor_tick", anchorTick);
        result.addProperty("atomic_snapshot", false);
        result.addProperty("scope", "loaded components within the original snapshot cube and 16 blocks of the player");
        result.addProperty("selected_components", selected);
        result.addProperty("observed_components", observed);
        result.addProperty("next_component_index", nextComponent);
        result.addProperty("flow_verified", false);
        result.addProperty("production_verified", false);
        result.addProperty("resource_scope", "pages and sided views may alias; do not sum overlapping resources");
        JsonArray gaps = new JsonArray();
        incomplete.forEach(gaps::add);
        result.add("incomplete_reasons", gaps);
        result.add("pages", pages.deepCopy());
        return result;
    }
}
