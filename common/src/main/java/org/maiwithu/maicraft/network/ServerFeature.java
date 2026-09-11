// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import com.google.gson.JsonObject;

/** Version and policy at negotiation time; execution rechecks the live policy. */
public record ServerFeature(String operationId, int version, boolean mutating, boolean enabled,
                            JsonObject limits) {
    public ServerFeature {
        if (operationId == null || !operationId.matches("[a-z][a-z0-9_.]{0,79}") || version < 1)
            throw new IllegalArgumentException("Invalid operation descriptor");
        limits = limits.deepCopy();
        ProtocolJson.encode(limits);
    }

    @Override public JsonObject limits() { return limits.deepCopy(); }

    JsonObject json() {
        JsonObject json = new JsonObject();
        json.addProperty("version", version);
        json.addProperty("mutating", mutating);
        json.addProperty("enabled", enabled);
        json.add("limits", limits());
        return json;
    }
}
