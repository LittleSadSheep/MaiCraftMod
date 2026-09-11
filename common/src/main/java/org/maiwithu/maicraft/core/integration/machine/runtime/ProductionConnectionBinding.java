// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import java.util.function.Function;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionEvidence;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Resource;

/** Freeze one verified native identity; opaque resource keys are never parsed into registry IDs. */
record ProductionConnectionBinding(String exactId, String registryId, String status, String reason) {
    static ProductionConnectionBinding resolve(Resource selector, Function<Resource, ProductionEvidence.Binding> resolver) {
        if (selector.medium().equals("kinetic")) return new ProductionConnectionBinding(null, null, "not_required", "kinetic_unit_has_no_inventory_identity");
        try {
            ProductionEvidence.Binding binding = resolver.apply(selector);
            if (binding == null || binding.check() == null || binding.check().status() != ProductionEvidence.Status.VERIFIED) {
                return unknown("native_resource_identity_not_verified");
            }
            JsonObject identity = binding.identity();
            String kind = string(identity, "kind"), registry = string(identity, "id");
            if (binding.resource() == null || !selector.medium().equals(binding.resource().medium()) || !selector.medium().equals(kind)
                    || registry == null || registry.length() > 512 || binding.resource().id() == null
                    || binding.resource().id().isBlank() || binding.resource().id().length() > 1024) {
                return unknown("native_resource_identity_does_not_match_medium");
            }
            return new ProductionConnectionBinding(binding.resource().id(), registry, "verified", "native_binding_identity_id");
        } catch (RuntimeException unavailable) { return unknown("native_resource_binding_unavailable"); }
    }

    void addQuery(JsonObject query) {
        if (registryId != null) query.addProperty("resource", registryId);
        if (exactId != null) query.addProperty("resource_id", exactId);
    }

    JsonObject report() {
        JsonObject result = new JsonObject(); result.addProperty("status", status); result.addProperty("reason", reason);
        if (exactId != null) result.addProperty("resource_id", exactId);
        if (registryId != null) result.addProperty("registry_id", registryId);
        return result;
    }

    private static ProductionConnectionBinding unknown(String reason) { return new ProductionConnectionBinding(null, null, "unknown", reason); }
    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive() && object.getAsJsonPrimitive(key).isString()
                && !object.get(key).getAsString().isBlank() ? object.get(key).getAsString() : null;
    }
}
