// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.connectivity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.Set;

/** Keep every edge verdict while fitting the protocol's receipt envelope. */
final class ConnectionResponseBudget {
    static final int MAX_CHARS = 48_000;
    private static final Set<String> COMPACT_FIELDS = Set.of("index", "from_index", "to_index", "status", "reason",
            "native_support", "verified_connection", "operational");

    private ConnectionResponseBudget() {}

    static JsonObject fit(JsonObject result) {
        result.addProperty("detail_truncated", false);
        if (result.toString().length() <= MAX_CHARS) return result;
        result.addProperty("detail_truncated", true);
        Set<String> sources = new LinkedHashSet<>();
        for (String name : new String[]{"edges", "intermediate"}) {
            for (var value : result.getAsJsonArray(name)) {
                JsonObject row = value.getAsJsonObject();
                if (row.has("provenance")) sources.add(row.get("provenance").getAsString());
                if (name.equals("edges")) {
                    int index = row.get("index").getAsInt();
                    row.addProperty("from_index", index); row.addProperty("to_index", index + 1);
                }
                row.keySet().removeIf(key -> !COMPACT_FIELDS.contains(key));
            }
        }
        JsonArray provenance = new JsonArray(); sources.forEach(provenance::add);
        result.add("evidence_provenance", provenance);
        if (result.toString().length() > MAX_CHARS && result.has("item_route")) {
            JsonObject route = result.getAsJsonObject("item_route");
            if (route.remove("sample_identity") != null) route.addProperty("sample_identity_omitted", true);
        }
        if (result.toString().length() > MAX_CHARS) {
            // Individual status/native_support/reason remain complete; aggregate flags remain at the top level.
            for (String name : new String[]{"edges", "intermediate"}) {
                for (var value : result.getAsJsonArray(name)) {
                    value.getAsJsonObject().remove("operational");
                    value.getAsJsonObject().remove("verified_connection");
                }
            }
        }
        if (result.toString().length() > MAX_CHARS) {
            throw new IllegalStateException("Connection response exceeds its bounded evidence schema");
        }
        return result;
    }
}
