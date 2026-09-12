// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogModels.*;

/** Strict bounded file format. Current-session observations and operation permissions are never serialized. */
final class CatalogCodec {
    record Snapshot(String identityKey, List<Device> devices, List<Line> lines) {
        Snapshot { devices = List.copyOf(devices); lines = List.copyOf(lines); }
    }
    private CatalogCodec() {}
    static String encode(Snapshot snapshot) {
        if (snapshot.devices().size() > CatalogLimits.DEVICES || snapshot.lines().size() > CatalogLimits.LINES)
            throw new IllegalArgumentException("Catalog entry count exceeds budget");
        var root = new JsonObject(); root.addProperty("version", 1); root.addProperty("identity_key", snapshot.identityKey());
        var devices = new JsonArray(); snapshot.devices().forEach(device -> devices.add(device(device)));
        var lines = new JsonArray(); snapshot.lines().forEach(line -> lines.add(line(line)));
        root.add("devices", devices); root.add("lines", lines); return root.toString();
    }
    static Snapshot decode(String json, String identityKey) {
        CatalogLimits.jsonDepth(json);
        var root = JsonParser.parseString(json).getAsJsonObject(); keys(root, "version", "identity_key", "devices", "lines");
        if (number(root, "version") != 1 || !identityKey.equals(text(root, "identity_key"))) throw new IllegalArgumentException("Catalog version or identity mismatch");
        var devices = new ArrayList<Device>(); var lines = new ArrayList<Line>(); Set<String> ids = new HashSet<>();
        for (var raw : array(root, "devices", CatalogLimits.DEVICES)) {
            var row = raw.getAsJsonObject();
            keys(row, "id", "label", "custom_label", "dimension", "position", "block_id", "block_entity_type", "roles", "evidence", "provenance",
                    "present", "first_observed_at_ms", "last_observed_at_ms", "last_observed_game_tick");
            var roles = new ArrayList<RoleEvidence>();
            for (var value : array(row, "roles", CatalogLimits.ROLES)) {
                var role = value.getAsJsonObject(); keys(role, "role", "evidence", "provenance");
                roles.add(new RoleEvidence(text(role, "role"), EvidenceStatus.valueOf(text(role, "evidence")), text(role, "provenance")));
            }
            var device = new Device(text(row, "id"), text(row, "label"), bool(row, "custom_label"), text(row, "dimension"), position(row.getAsJsonObject("position")),
                    text(row, "block_id"), text(row, "block_entity_type"), roles, EvidenceStatus.valueOf(text(row, "evidence")), text(row, "provenance"),
                    bool(row, "present"), number(row, "first_observed_at_ms"), number(row, "last_observed_at_ms"), number(row, "last_observed_game_tick"));
            if (!device.id().equals(deviceId(identityKey, device.dimension(), device.position())) || !ids.add(device.id())) throw new IllegalArgumentException("Duplicate or foreign catalog device");
            devices.add(device);
        }
        ids.clear();
        for (var raw : array(root, "lines", CatalogLimits.LINES)) {
            var row = raw.getAsJsonObject(); keys(row, "id", "label", "dimension", "anchor", "manifest", "manifest_fingerprint", "registered_at_ms", "commission");
            var line = new Line(text(row, "id"), text(row, "label"), text(row, "dimension"), position(row.getAsJsonObject("anchor")),
                    row.getAsJsonObject("manifest").toString(), text(row, "manifest_fingerprint"), number(row, "registered_at_ms"),
                    row.has("commission") ? commission(row.getAsJsonObject("commission")) : null);
            if (!line.id().equals(lineId(identityKey, line.dimension(), line.anchor())) || !ids.add(line.id())) throw new IllegalArgumentException("Duplicate or foreign catalog line");
            lines.add(line);
        }
        return new Snapshot(identityKey, devices, lines);
    }
    private static JsonObject device(Device device) {
        var row = new JsonObject(); row.addProperty("id", device.id()); row.addProperty("label", device.label()); row.addProperty("custom_label", device.customLabel());
        row.addProperty("dimension", device.dimension()); row.add("position", CatalogViews.position(device.position())); row.addProperty("block_id", device.blockId());
        row.addProperty("block_entity_type", device.blockEntityType()); row.addProperty("evidence", device.evidenceStatus().name()); row.addProperty("provenance", device.provenance());
        row.addProperty("present", device.lastObservedPresent()); row.addProperty("first_observed_at_ms", device.firstObservedAtMillis());
        row.addProperty("last_observed_at_ms", device.lastObservedAtMillis()); row.addProperty("last_observed_game_tick", device.lastObservedGameTick());
        var roles = new JsonArray(); for (RoleEvidence role : device.roles()) { var value = new JsonObject(); value.addProperty("role", role.role()); value.addProperty("evidence", role.status().name()); value.addProperty("provenance", role.provenance()); roles.add(value); }
        row.add("roles", roles); return row;
    }
    private static JsonObject line(Line line) {
        var row = new JsonObject(); row.addProperty("id", line.id()); row.addProperty("label", line.label()); row.addProperty("dimension", line.dimension());
        row.add("anchor", CatalogViews.position(line.anchor())); row.add("manifest", line.manifest()); row.addProperty("manifest_fingerprint", line.manifestFingerprint());
        row.addProperty("registered_at_ms", line.registeredAtMillis()); if (line.commission() != null) row.add("commission", commission(line.commission())); return row;
    }
    static JsonObject commission(Commission commission) {
        var row = new JsonObject(); var proof = commission.evidence(); row.addProperty("manifest_fingerprint", commission.manifestFingerprint());
        row.addProperty("provenance", proof.provenance()); row.addProperty("resource_id", proof.resourceId()); row.addProperty("from_tick", proof.fromTick()); row.addProperty("through_tick", proof.throughTick());
        row.addProperty("native_events", proof.nativeEvents()); row.addProperty("produced", proof.produced()); row.addProperty("delivered", proof.delivered()); row.addProperty("observed_at_ms", proof.observedAtMillis()); return row;
    }
    private static Commission commission(JsonObject row) {
        keys(row, "manifest_fingerprint", "provenance", "resource_id", "from_tick", "through_tick", "native_events", "produced", "delivered", "observed_at_ms");
        return new Commission(new CommissionEvidence(text(row, "provenance"), text(row, "resource_id"), number(row, "from_tick"), number(row, "through_tick"),
                number(row, "native_events"), number(row, "produced"), number(row, "delivered"), number(row, "observed_at_ms")), text(row, "manifest_fingerprint"));
    }
    private static Position position(JsonObject row) { keys(row, "x", "y", "z"); return new Position(Math.toIntExact(number(row, "x")), Math.toIntExact(number(row, "y")), Math.toIntExact(number(row, "z"))); }
    private static String text(JsonObject row, String key) { var value = row.get(key); if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Invalid catalog string " + key); return value.getAsString(); }
    private static long number(JsonObject row, String key) { var value = row.get(key); if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Invalid catalog number " + key); return value.getAsBigDecimal().longValueExact(); }
    private static boolean bool(JsonObject row, String key) { var value = row.get(key); if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Invalid catalog boolean " + key); return value.getAsBoolean(); }
    private static JsonArray array(JsonObject row, String key, int maximum) { var value = row.getAsJsonArray(key); if (value == null || value.size() > maximum) throw new IllegalArgumentException("Catalog array exceeds budget: " + key); return value; }
    private static void keys(JsonObject row, String... allowed) { if (row == null || !Set.of(allowed).containsAll(row.keySet())) throw new IllegalArgumentException("Unknown catalog fields"); }
}
