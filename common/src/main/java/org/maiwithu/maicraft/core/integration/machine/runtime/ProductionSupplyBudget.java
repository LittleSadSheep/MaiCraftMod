// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest;

/** Finite allocation survives refill passes; consumed source stock is never credited again. */
final class ProductionSupplyBudget {
    record Key(String source, String medium, String resource) {}
    private final long total;
    private long initial, injected;
    private boolean initialized;

    ProductionSupplyBudget(long total) {
        if (total < 1) throw new IllegalArgumentException("Production supply amount must be positive");
        this.total = total;
    }

    static Map<Key, Long> demands(ProductionManifest manifest) {
        var nodes = new LinkedHashMap<String, ProductionManifest.Node>();
        var ports = new LinkedHashMap<String, ProductionManifest.Port>();
        manifest.nodes().forEach(node -> nodes.put(node.id(), node));
        manifest.ports().forEach(port -> ports.put(port.id(), port));
        Map<Key, Long> result = new LinkedHashMap<>();
        for (var link : manifest.links()) {
            var source = nodes.get(ports.get(link.from()).node());
            if (!source.kind().equals("source")) continue;
            Key key = new Key(source.id(), link.resource().medium(), link.resource().id());
            result.merge(key, link.amount(), key.medium().equals("kinetic") ? Math::max : Math::addExact);
        }
        return result;
    }

    long initialize(long available) {
        if (initialized || available < 0) throw new IllegalStateException("Invalid initial source allocation");
        initialized = true;
        initial = Math.min(total, available);
        return initial;
    }

    int confirm(JsonObject receipt, int requested, String identity) {
        if (!initialized || requested < 1 || requested > Math.min(64, remaining()))
            throw new IllegalStateException("Transfer exceeds the remaining production budget");
        String status = receipt.get("status").getAsString();
        if (!java.util.Set.of("applied", "partial", "no_change").contains(status)
                || !identity.equals(receipt.get("resource_id").getAsString()))
            throw new IllegalStateException("Production transfer identity/status changed; inspect the settled operation");
        int moved = receipt.get("transferred").getAsBigDecimal().intValueExact();
        if (moved < 0 || moved > requested || status.equals("applied") && moved != requested
                || status.equals("no_change") && moved != 0 || status.equals("partial") && (moved == 0 || moved >= requested))
            throw new IllegalStateException("Production transfer receipt violates its quantity contract");
        injected = Math.addExact(injected, moved);
        return moved;
    }

    boolean initialized() { return initialized; }
    long total() { return total; }
    long allocated() { return total - remaining(); }
    long remaining() { return total - initial - injected; }
    long injected() { return injected; }
    Map<String, Object> report() {
        return Map.of("allocated_total", total, "initial_stock_credited", initial,
                "confirmed_injected", injected, "remaining_to_inject", remaining(), "initialized", initialized);
    }
}
