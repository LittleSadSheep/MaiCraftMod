// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.production;

import static org.maiwithu.maicraft.core.integration.machine.production.ProductionNativeJson.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionEvidence.*;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Resource;

/** Binds public registry selectors to observed complete identities, never by splitting an opaque resource hash. */
final class ProductionResourceBindings {
    private final Map<Resource,JsonObject> identities = new LinkedHashMap<>();
    private final Map<Resource,Binding> pinned = new LinkedHashMap<>();
    void clear() { identities.clear(); pinned.clear(); }
    void clearObserved() { identities.clear(); }
    void observe(JsonElement value) {
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) return;
        if (value.isJsonArray()) { value.getAsJsonArray().forEach(this::observe); return; }
        JsonObject row = value.getAsJsonObject(); JsonObject identity = object(row,"identity");
        if (identity == null && text(row,"kind") != null && object(row,"components") != null) identity = row;
        if (identity != null) {
            String key = identityKey(identity), medium = text(identity,"kind");
            String declared = text(row,"resource_id");
            if (declared == null && text(row,"medium") != null) declared = text(row,"id");
            if (declared != null && !declared.equals(key)) return;
            if (key != null && (declared == null || declared.equals(key)) && ProductionManifest.MEDIA.contains(medium == null ? "" : medium))
                identities.put(new Resource(medium,key),identity.deepCopy());
        }
        row.entrySet().stream().filter(e -> !e.getKey().equals("components") && !e.getKey().equals("identity")).forEach(e -> observe(e.getValue()));
    }
    Binding resolve(Resource selector) {
        if (pinned.containsKey(selector)) return pinned.get(selector);
        for (Binding selected : pinned.values()) if (selected.resource().equals(selector)) return selected;
        if (selector.medium().equals("kinetic") && selector.id().equals("rpm"))
            return new Binding(new Check(Status.VERIFIED,"native_rotation_unit","RPM is a measured non-inventory resource"),selector,null);
        JsonObject exact = identities.get(selector);
        if (exact != null) return pin(selector,new Binding(new Check(Status.VERIFIED,"native_resource_identity","Exact component-sensitive identity selected for this window"),selector,exact));
        List<Map.Entry<Resource,JsonObject>> matches = identities.entrySet().stream().filter(e ->
                e.getKey().medium().equals(selector.medium()) && selector.id().equals(text(e.getValue(),"id"))).toList();
        if (matches.size() == 1) return pin(selector,new Binding(new Check(Status.VERIFIED,"native_resource_identity","Registry selector bound to one component identity for this window"),matches.getFirst().getKey(),matches.getFirst().getValue()));
        return new Binding(Check.unknown(matches.isEmpty() ? "No observed full identity matches " + selector.id()
                : "Multiple component variants match " + selector.id() + "; choose one observed resource_id"),selector,null);
    }
    private Binding pin(Resource selector, Binding binding) { pinned.put(selector,binding); return binding; }
}
