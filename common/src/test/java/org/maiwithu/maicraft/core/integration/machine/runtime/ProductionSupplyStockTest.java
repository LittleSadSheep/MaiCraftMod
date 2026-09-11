// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

public final class ProductionSupplyStockTest {
    public static void main(String[] args) {
        var snapshot = snapshot();
        JsonArray resources = snapshot.getAsJsonArray("observations").get(0).getAsJsonObject().getAsJsonArray("resources");
        resources.add(resource("up", "slot-0-up", "opaque-plain", 10));
        resources.add(resource("north", "slot-0-north", "opaque-plain", 10));
        check(available(snapshot) == 10, "Sided aliases cannot multiply native inventory");
        resources.add(resource("up", "slot-0-up", "opaque-plain", 10));
        check(available(snapshot) == 10, "Duplicate storage evidence cannot multiply credit");
        resources.add(resource("up", "slot-1-up", "opaque-components", 3));
        check(available(snapshot) == 10, "Same registry id with other components cannot satisfy the bound resource");
        resources.add(resource("up", "slot-0-up", "opaque-components", 10));
        reject(() -> available(snapshot), "One storage slot cannot have two current identities");
        resources.remove(resources.size() - 1);
        snapshot.addProperty("truncated", true);
        reject(() -> available(snapshot), "Incomplete initial evidence cannot silently imply missing stock");
        snapshot.addProperty("truncated", false);
        JsonObject port = snapshot.getAsJsonArray("observations").get(0).getAsJsonObject().getAsJsonArray("ports").get(0).getAsJsonObject();
        port.addProperty("status", "unknown");
        reject(() -> available(snapshot), "Unknown native interface is not an empty source");
        aliasesShareAllocation();
        System.out.println("ProductionSupplyStockTest: passed");
    }

    private static long available(JsonObject snapshot) {
        return ProductionSupplyStock.read(snapshot, BlockPos.ZERO, "up", "opaque-plain", identity("opaque-plain")).amount();
    }
    private static JsonObject snapshot() {
        var result = new JsonObject(); var observations = new JsonArray(); var observation = new JsonObject();
        observation.add("position", ProductionRunPlan.position(BlockPos.ZERO));
        observation.addProperty("block_id", "minecraft:barrel");
        var ports = new JsonArray(); var port = new JsonObject();
        port.addProperty("side", "up"); port.addProperty("medium", "items"); port.addProperty("status", "observed"); ports.add(port);
        observation.add("ports", ports); observation.add("resources", new JsonArray());
        observations.add(observation); result.add("observations", observations); return result;
    }
    private static JsonObject resource(String side, String storage, String exact, long amount) {
        var resource = new JsonObject();
        resource.add("identity", identity(exact));
        resource.addProperty("resource_id", exact); resource.addProperty("storage_id", storage);
        resource.addProperty("membership", "physical-barrel"); resource.addProperty("slot", storage.contains("slot-1") ? 1 : 0);
        resource.addProperty("side", side); resource.addProperty("amount", amount); return resource;
    }
    private static JsonObject identity(String exact) {
        var identity = new JsonObject();
        identity.addProperty("kind", "items"); identity.addProperty("id", "minecraft:iron_ingot");
        var components = new JsonObject();
        if (exact.equals("opaque-components")) components.addProperty("minecraft:custom_name", "fixture-name");
        identity.add("components", components); return identity;
    }
    private static void aliasesShareAllocation() {
        var first = snapshot();
        var observation = first.getAsJsonArray("observations").get(0).getAsJsonObject();
        observation.getAsJsonArray("resources").add(resource("up", "terminal-A-slot", "opaque-plain", 10));
        var nativeState = new JsonObject(); var ae = new JsonObject(); var nodes = new JsonArray(); var node = new JsonObject();
        node.addProperty("side", "up"); node.addProperty("membership", "ae-network-A"); nodes.add(node);
        ae.addProperty("status", "observed"); ae.add("nodes", nodes); nativeState.add("ae2", ae); observation.add("native", nativeState);
        var second = first.deepCopy();
        var secondPosition = new BlockPos(5, 0, 0);
        var secondObservation = second.getAsJsonArray("observations").get(0).getAsJsonObject();
        secondObservation.add("position", ProductionRunPlan.position(secondPosition));
        secondObservation.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("storage_id", "terminal-B-slot");
        var ledger = new ProductionSupplyAllocations();
        var sourceA = new ProductionSupplyBudget.Key("source-A", "items", "minecraft:iron_ingot");
        var sourceB = new ProductionSupplyBudget.Key("source-B", "items", "minecraft:iron_ingot");
        var viewA = ProductionSupplyStock.read(first, BlockPos.ZERO, "up", "opaque-plain", identity("opaque-plain"));
        var viewB = ProductionSupplyStock.read(second, secondPosition, "up", "opaque-plain", identity("opaque-plain"));
        check(ledger.observe(sourceA, viewA) == 10, "First terminal exposes ten actual items");
        ledger.charge(sourceA, 8);
        check(ledger.observe(sourceB, viewB) == 2, "Another terminal on the same network cannot credit the eight allocated items again");
        ledger.charge(sourceB, 2);
        check(ledger.observe(sourceA, viewA) == 0, "Refill observations never reset shared stock allocation");
        secondObservation.getAsJsonObject("native").getAsJsonObject("ae2").getAsJsonArray("nodes").get(0)
                .getAsJsonObject().addProperty("membership", "different-network");
        var changed = ProductionSupplyStock.read(second, secondPosition, "up", "opaque-plain", identity("opaque-plain"));
        reject(() -> ledger.observe(sourceB, changed), "Mid-run network changes require a new reviewed allocation");
        var mismatched = first.deepCopy();
        mismatched.getAsJsonArray("observations").get(0).getAsJsonObject().getAsJsonArray("resources").get(0)
                .getAsJsonObject().add("identity", identity("opaque-components"));
        reject(() -> ProductionSupplyStock.read(mismatched, BlockPos.ZERO, "up", "opaque-plain", identity("opaque-plain")),
                "An opaque key cannot silently change its complete native identity");
    }
    private static void reject(Runnable action, String message) {
        try { action.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError(message);
    }
    private static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
