// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Resource;

/** One sided native view is a sink-stock measurement, never a production or transfer event. */
final class ProductionFlowStock {
    private ProductionFlowStock() {}

    static Map<String, BigDecimal> read(JsonObject snapshot, BlockPos position, String face, Resource target) {
        if (snapshot.get("truncated").getAsBoolean()) throw new IllegalStateException("production_sink_snapshot_truncated");
        JsonObject selected = null;
        for (var raw : snapshot.getAsJsonArray("observations")) {
            JsonObject observation = raw.getAsJsonObject();
            if (ProductionRunPlan.position(position).equals(observation.get("position"))) {
                if (selected != null) throw new IllegalStateException("production_sink_duplicate_observation");
                selected = observation;
            }
        }
        if (selected == null) throw new IllegalStateException("production_sink_missing_observation");
        boolean observed = false;
        for (var raw : selected.getAsJsonArray("ports")) {
            JsonObject port = raw.getAsJsonObject();
            if (face.equals(ProductionFlowPaths.text(port, "side")) && target.medium().equals(ProductionFlowPaths.text(port, "medium"))
                    && ProductionFlowPaths.text(port, "status").equals("observed")) observed = true;
        }
        if (!observed) throw new IllegalStateException("production_sink_native_port_unavailable");
        Map<String, JsonObject> slots = new LinkedHashMap<>();
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (var raw : selected.getAsJsonArray("resources")) {
            JsonObject resource = raw.getAsJsonObject();
            if (!face.equals(ProductionFlowPaths.text(resource, "side")) || !ProductionFlowPaths.matches(target, resource)) continue;
            String storage = ProductionFlowPaths.text(resource, "storage_id"), exact = ProductionFlowPaths.text(resource, "resource_id");
            if (storage.isBlank() || exact.isBlank()) throw new IllegalStateException("production_sink_identity_missing");
            BigDecimal amount = resource.get("amount").getAsBigDecimal();
            if (amount.signum() < 0) throw new IllegalStateException("production_sink_negative_amount");
            JsonObject previous = slots.putIfAbsent(storage, resource);
            if (previous != null) {
                if (!previous.equals(resource)) throw new IllegalStateException("production_sink_inconsistent_slot");
                continue;
            }
            totals.merge(exact, amount, BigDecimal::add);
        }
        return Map.copyOf(totals);
    }

    static BigDecimal total(Map<String, BigDecimal> amounts) { return amounts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add); }
}
