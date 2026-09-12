// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Locale;
import static org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogModels.*;

/** Public views omit absolute positions unless the caller explicitly requests internal location data. */
final class CatalogViews {
    private CatalogViews() {}
    static JsonObject position(Position position) {
        var result = new JsonObject(); result.addProperty("x", position.x()); result.addProperty("y", position.y()); result.addProperty("z", position.z()); return result;
    }
    static JsonObject device(DeviceView view, boolean includeLocation) {
        Device device = view.device(); var result = new JsonObject();
        result.addProperty("id", device.id()); result.addProperty("label", device.label()); result.addProperty("dimension", device.dimension());
        result.addProperty("last_known_block_id", device.blockId()); result.addProperty("block_entity_type", device.blockEntityType());
        result.addProperty("current_state", view.currentState().name().toLowerCase(Locale.ROOT)); result.addProperty("last_observed_present", device.lastObservedPresent());
        result.addProperty("observation_evidence", device.evidenceStatus().name().toLowerCase(Locale.ROOT)); result.addProperty("observation_provenance", device.provenance());
        result.addProperty("last_observed_at_ms", device.lastObservedAtMillis()); result.addProperty("last_observed_game_tick", device.lastObservedGameTick());
        var roles = new JsonArray();
        for (RoleEvidence role : device.roles()) { var row = new JsonObject(); row.addProperty("role", role.role()); row.addProperty("evidence", role.status().name().toLowerCase(Locale.ROOT)); row.addProperty("provenance", role.provenance()); roles.add(row); }
        result.add("roles", roles); result.addProperty("operation_authorized", false);
        if (includeLocation) result.add("position", position(device.position())); return result;
    }
    static JsonObject node(NodeView view, boolean includeLocation) {
        var result = new JsonObject(); result.addProperty("line_id", view.lineId()); result.addProperty("node_id", view.nodeId());
        result.addProperty("declared_kind", view.declaredKind()); if (view.recipeId() != null) result.addProperty("recipe_id", view.recipeId());
        var ports = new JsonArray();
        for (PortView port : view.ports()) { var row = new JsonObject(); row.addProperty("id", port.id()); row.addProperty("medium", port.medium()); row.addProperty("direction", port.direction()); row.addProperty("face", port.face()); if (includeLocation) row.add("position", position(port.position())); ports.add(row); }
        result.add("ports", ports); if (view.device() != null) result.add("observed_device", device(view.device(), includeLocation));
        result.addProperty("operation_authorized", false); if (includeLocation) result.add("position", position(view.position())); return result;
    }
    static JsonObject line(Line line, boolean includeLocation) {
        var result = new JsonObject(); result.addProperty("id", line.id()); result.addProperty("label", line.label()); result.addProperty("dimension", line.dimension());
        result.addProperty("manifest_fingerprint", line.manifestFingerprint()); result.addProperty("operation_authorized", false);
        result.addProperty("current_production_verified", false);
        var manifest = line.manifest(); var nodes = new JsonArray(); var links = new JsonArray();
        manifest.getAsJsonArray("nodes").forEach(value -> { var node = value.getAsJsonObject(); var row = new JsonObject(); for (String key : new String[]{"id", "kind", "recipe_id"}) if (node.has(key)) row.add(key, node.get(key).deepCopy()); nodes.add(row); });
        manifest.getAsJsonArray("links").forEach(value -> { var link = value.getAsJsonObject(); var row = new JsonObject(); for (String key : new String[]{"id", "from", "to", "medium", "resource", "amount"}) if (link.has(key)) row.add(key, link.get(key).deepCopy()); links.add(row); });
        result.add("nodes", nodes); result.add("links", links); result.addProperty("port_count", manifest.getAsJsonArray("ports").size());
        if (line.commission() != null) {
            var proof = CatalogCodec.commission(line.commission()); proof.addProperty("historical_only", true);
            proof.addProperty("matches_current_manifest", line.commissionMatchesManifest()); result.add("last_commission", proof);
        }
        if (includeLocation) { result.add("anchor", position(line.anchor())); result.add("manifest", manifest); }
        return result;
    }
}
