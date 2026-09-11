// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;

/** Allocates one authored sided view; other sides may expose the same physical slots with different indices. */
final class ProductionSupplyStock {
    record View(long amount, String allocationKey, String fingerprint) {}
    private ProductionSupplyStock() {}

    static View read(JsonObject snapshot, BlockPos position, String side, String exactResource, JsonObject expectedIdentity) {
        if (snapshot.has("truncated") && snapshot.get("truncated").getAsBoolean())
            throw new IllegalStateException("production_source_snapshot_truncated");
        JsonArray observations = snapshot.getAsJsonArray("observations");
        if (observations == null) throw new IllegalStateException("production_source_snapshot_unreadable");
        JsonObject selected = null;
        for (var raw : observations) {
            JsonObject observation = raw.getAsJsonObject();
            if (ProductionRunPlan.position(position).equals(observation.get("position"))) {
                if (selected != null) throw new IllegalStateException("production_source_snapshot_duplicated");
                selected = observation;
            }
        }
        if (selected == null || !selected.has("resources") || !selected.has("ports"))
            throw new IllegalStateException("production_source_snapshot_unreadable");
        String medium = text(expectedIdentity, "kind");
        boolean observed = false;
        for (var raw : selected.getAsJsonArray("ports")) {
            JsonObject port = raw.getAsJsonObject();
            if (text(port, "side").equals(side) && text(port, "medium").equals(medium)
                    && text(port, "status").equals("observed")) observed = true;
        }
        if (!observed) throw new IllegalStateException("production_source_port_unreadable: " + side + "/" + medium);
        Map<String, Long> slots = new HashMap<>();
        Map<String, String> identities = new HashMap<>();
        var memberships = new TreeSet<String>();
        var storages = new TreeSet<String>();
        for (var raw : selected.getAsJsonArray("resources")) {
            JsonObject resource = raw.getAsJsonObject();
            if (!text(resource, "side").equals(side) || !resource.has("identity")) continue;
            JsonObject identity = resource.getAsJsonObject("identity");
            if (!text(identity, "kind").equals(medium)) continue;
            String storage = text(resource, "storage_id"), exact = text(resource, "resource_id");
            if (storage.isBlank() || exact.isBlank() || !identity.has("components"))
                throw new IllegalStateException("production_source_identity_unreadable");
            storages.add(storage);
            if (!text(resource, "membership").isBlank()) memberships.add(text(resource, "membership"));
            if (resource.has("slot")) {
                String previousIdentity = identities.putIfAbsent(storage, exact);
                if (previousIdentity != null && !previousIdentity.equals(exact))
                    throw new IllegalStateException("production_source_inconsistent_identity");
            }
            if (!exact.equals(exactResource)) continue;
            if (!expectedIdentity.equals(identity)) throw new IllegalStateException("production_source_resource_identity_changed");
            long amount = resource.get("amount").getAsBigDecimal().longValueExact();
            if (amount < 0) throw new IllegalStateException("production_source_negative_stock");
            Long previous = slots.putIfAbsent(storage, amount);
            if (previous != null && previous != amount) throw new IllegalStateException("production_source_inconsistent_stock");
        }
        long total = 0;
        for (long amount : slots.values()) total = Math.addExact(total, amount);
        String network = aeMembership(selected, side);
        if (network != null) memberships = new TreeSet<>(java.util.Set.of(network));
        if (memberships.size() > 1) throw new IllegalStateException("production_source_ambiguous_membership");
        String owner = memberships.isEmpty() ? "storage:" + storages : "membership:" + memberships.first();
        if (storages.isEmpty() && memberships.isEmpty()) owner = "empty_position:" + position.toShortString();
        // Contents/count may change normally. A changed block, inventory topology or network invalidates this source view.
        String fingerprint = text(selected, "block_id") + "/" + owner + "/" + side + "/" + storages;
        return new View(total, owner + "/" + medium + "/" + exactResource, fingerprint);
    }

    private static String aeMembership(JsonObject observation, String side) {
        if (!observation.has("native") || !observation.getAsJsonObject("native").has("ae2")) return null;
        JsonObject ae = observation.getAsJsonObject("native").getAsJsonObject("ae2");
        if (!text(ae, "status").equals("observed") || !ae.has("nodes"))
            throw new IllegalStateException("production_source_ae_membership_unreadable");
        var memberships = new TreeSet<String>();
        var allMemberships = new TreeSet<String>();
        for (var raw : ae.getAsJsonArray("nodes")) {
            JsonObject node = raw.getAsJsonObject();
            if (!text(node, "membership").isBlank()) allMemberships.add(text(node, "membership"));
            if (text(node, "side").equals(side) && !text(node, "membership").isBlank()) memberships.add(text(node, "membership"));
        }
        if (memberships.isEmpty()) memberships = allMemberships;
        if (memberships.size() > 1) throw new IllegalStateException("production_source_ambiguous_ae_network");
        if (memberships.isEmpty()) throw new IllegalStateException("production_source_ae_membership_unreadable");
        return memberships.first();
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
}
