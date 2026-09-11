// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Negotiated support is separate from registration and per-request execution conditions. */
public final class ServerCapabilityState {
    public enum State { DISCONNECTED, NEGOTIATING, READY, CLIENT_ONLY, DENIED, LOST }
    public record Scope(long connection, String sessionId, String dimension) {}
    public record Feature(int version, boolean mutating, boolean enabled, JsonObject limits) {
        public Feature { limits = limits.deepCopy(); }
        @Override public JsonObject limits() { return limits.deepCopy(); }
    }

    State state = State.DISCONNECTED;
    String reason = "no_connection";
    Scope scope;
    final Map<String, Feature> features = new LinkedHashMap<>();
    JsonObject limits = new JsonObject();

    public State state() { return state; }
    public String reason() { return reason; }
    public Scope scope() { return scope; }
    public Map<String, Feature> features() { return Map.copyOf(features); }
    public JsonObject limits() { return limits.deepCopy(); }

    public boolean supports(ClientOperation operation) {
        Feature feature = features.get(operation.id());
        return state == State.READY && feature != null && feature.enabled()
                && feature.version() == operation.version() && feature.mutating() == operation.mutating();
    }

    public boolean policyDenied(ClientOperation operation) {
        Feature feature = features.get(operation.id());
        return state == State.DENIED || (feature != null && !feature.enabled());
    }

    void reset(State next, String detail) {
        state = next;
        reason = detail;
        scope = null;
        features.clear();
        limits = new JsonObject();
    }

    void welcome(JsonObject envelope, long connection, String dimension) {
        var discovered = new LinkedHashMap<String, Feature>();
        JsonObject advertised = object(envelope, "features");
        if (advertised.size() > 256) throw new IllegalArgumentException("too many advertised operations");
        for (var entry : advertised.entrySet()) {
            JsonObject item = entry.getValue().getAsJsonObject();
            discovered.put(entry.getKey(), new Feature(item.get("version").getAsInt(),
                    item.get("mutating").getAsBoolean(), item.get("enabled").getAsBoolean(),
                    object(item, "limits")));
        }
        String sessionId = text(envelope, "sessionId");
        if (sessionId.isBlank()) throw new IllegalArgumentException("missing server session identity");
        scope = new Scope(connection, sessionId, dimension);
        features.clear();
        features.putAll(discovered);
        limits = object(envelope, "limits").deepCopy();
        state = State.READY;
        reason = "";
    }

    int limit(String name, int defaultValue, int maximum) {
        try { return Math.max(1, Math.min(maximum, limits.get(name).getAsInt())); }
        catch (RuntimeException missing) { return defaultValue; }
    }

    JsonObject report() {
        JsonObject report = new JsonObject();
        report.addProperty("state", state.name().toLowerCase(java.util.Locale.ROOT));
        report.addProperty("reason", reason);
        if (scope != null) {
            report.addProperty("session_id", scope.sessionId());
            report.addProperty("dimension", scope.dimension());
        }
        report.add("limits", limits.deepCopy());
        JsonObject operations = new JsonObject();
        features.forEach((id, feature) -> {
            JsonObject value = new JsonObject();
            value.addProperty("registered", true);
            value.addProperty("version", feature.version());
            value.addProperty("mutating", feature.mutating());
            value.addProperty("enabled", feature.enabled());
            value.add("limits", feature.limits());
            operations.add(id, value);
        });
        report.add("server_operations", operations);
        return report;
    }

    static String text(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
    }
    static JsonObject object(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonObject()
                ? object.getAsJsonObject(name) : new JsonObject();
    }
}
