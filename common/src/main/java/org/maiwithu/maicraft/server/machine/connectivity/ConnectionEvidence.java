// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.connectivity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

/** Native topology, readiness, resource compatibility and temporal flow are separate claims. */
record ConnectionEvidence(String status, boolean connected, boolean operational, String reason,
                          String provenance, JsonObject details) {
    static ConnectionEvidence of(String status, boolean connected, boolean operational, String reason,
                                 String provenance) {
        return new ConnectionEvidence(status, connected, operational, reason, provenance, new JsonObject());
    }

    JsonObject json() {
        JsonObject value = details.deepCopy();
        value.addProperty("status", status);
        value.addProperty("native_support", !status.equals("unsupported"));
        value.addProperty("verified_connection", connected);
        value.addProperty("operational", operational);
        value.addProperty("reason", reason);
        value.addProperty("provenance", provenance);
        value.addProperty("flow_verified", false);
        return value;
    }

    static JsonObject summarize(List<ConnectionEvidence> evidence) {
        JsonObject result = new JsonObject();
        boolean connected = !evidence.isEmpty() && evidence.stream().allMatch(ConnectionEvidence::connected);
        boolean operational = connected && evidence.stream().allMatch(ConnectionEvidence::operational);
        String status = evidence.isEmpty() ? "unknown" : "verified";
        for (ConnectionEvidence item : evidence) {
            if (rank(item.status) > rank(status)) status = item.status;
        }
        if (!connected && status.equals("verified")) status = "unknown";
        result.addProperty("status", status);
        result.addProperty("verified_connection", connected);
        result.addProperty("operational", operational);
        result.addProperty("resource_compatibility", "unknown");
        result.addProperty("flow_verified", false);
        result.addProperty("production_verified", false);
        result.addProperty("scope", "explicit_path_native_topology");
        JsonArray requirements = new JsonArray();
        requirements.add("Temporal resource flow and sustained production require separate observations");
        if (!operational) requirements.add("Resolve per-edge and intermediate-node readiness before starting production");
        result.add("remaining_evidence", requirements);
        return result;
    }

    private static int rank(String status) {
        return switch (status) {
            case "verified" -> 0;
            case "planned" -> 1;
            case "unknown" -> 2;
            case "unsupported" -> 3;
            default -> throw new IllegalArgumentException("Unrecognized evidence status");
        };
    }
}
