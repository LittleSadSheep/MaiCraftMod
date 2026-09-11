// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionEvidence;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Resource;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** Native wire shapes for orchestration tests; these fixtures never claim a running Minecraft server. */
final class ProductionConnectionFixture implements ProductionWork {
    static final String WORLD = "minecraft:overworld";
    final List<JsonObject> sent = new ArrayList<>();
    final List<BlockPos> observations = new ArrayList<>();
    final ProductionRunPlan plan;
    final ProductionConnectionSurvey survey;
    String dimension = WORLD, adapter;
    String exact = "opaque:component-key#this-is-not-a-registry-id";
    String registry = "minecraft:iron_ingot";
    boolean bindingVerified = true;
    long tick = 100;
    int navDelay, remainingNavigation, replyInterval, serverLead;
    BlockPos observed, navigationTarget;
    JsonObject pending;

    ProductionConnectionFixture(List<BlockPos> path, String medium, String adapter) {
        this.adapter = adapter;
        plan = new ProductionRunPlan(BlockPos.ZERO, WORLD, manifest(path, medium));
        survey = new ProductionConnectionSurvey(plan, this, this::binding, () -> dimension, () -> tick, ignored -> this.adapter);
    }

    ProductionEvidence.Binding binding(Resource selector) {
        JsonObject identity = new JsonObject(); identity.addProperty("kind", selector.medium()); identity.addProperty("id", registry);
        identity.add("components", new JsonObject());
        var check = bindingVerified ? new ProductionEvidence.Check(ProductionEvidence.Status.VERIFIED, "fixture_native_binding", "exact fixture identity")
                : ProductionEvidence.Check.unknown("fixture binding absent");
        return new ProductionEvidence.Binding(check, new Resource(selector.medium(), exact), identity);
    }

    JsonObject settle() {
        for (int i = 0; i < 20_000; i++) {
            tick++;
            JsonObject result = survey.tick(plan.manifest().links().getFirst());
            if (result != null) return result;
        }
        throw new AssertionError("Connection survey never settled");
    }

    @Override public boolean approach(BlockPos position) { throw new AssertionError("Connection queries must only observe"); }
    @Override public boolean observe(BlockPos position) {
        check(pending == null, "Navigation changed while a request was in flight");
        if (!position.equals(navigationTarget)) { navigationTarget = position; remainingNavigation = navDelay; }
        if (remainingNavigation-- > 0) return false;
        observed = position; observations.add(position); return true;
    }
    @Override public JsonObject request(String operation, JsonObject body, boolean mutating) {
        check(operation.equals("machine.connections") && !mutating, "Unexpected connection operation or mutation");
        if (pending == null) {
            pending = body.deepCopy(); sent.add(body.deepCopy());
            for (var raw : body.getAsJsonArray("path")) {
                BlockPos point = position(raw.getAsJsonObject());
                check(ProductionConnectionPath.near(observed, point), "Path point exceeds four blocks from observation target");
                // work.observe may settle at the outer 12-block edge, not at the machine.
                double x = point.getX() - observed.getX() + 12.0, y = point.getY() - observed.getY(), z = point.getZ() - observed.getZ();
                check(x * x + y * y + z * z <= 256.0000001, "A legal observe result would put a query point beyond server range");
            }
            return null;
        }
        check(pending.equals(body), "Pending request arguments changed");
        pending = null; tick += replyInterval;
        return reply(body, tick + serverLead, exact);
    }
    @Override public TaskState advanceChild(Task task) { throw new AssertionError("Connection survey created a native action"); }
    @Override public void extendDeadlineTo(long gameTick) { check(gameTick >= tick, "Deadline moved behind current observation"); }

    static JsonObject reply(JsonObject body, long tick, String exact) {
        JsonObject result = new JsonObject(); result.addProperty("schema", "maicraft.connection_inspection.v1");
        result.addProperty("dimension", WORLD); result.addProperty("tick", tick);
        result.addProperty("system", body.get("system").getAsString()); result.addProperty("medium", body.get("medium").getAsString());
        result.addProperty("complete", true); result.addProperty("status", "verified"); result.addProperty("verified_connection", true);
        result.addProperty("operational", true); result.addProperty("resource_compatibility", "verified");
        result.addProperty("flow_verified", false); result.addProperty("production_verified", false); result.addProperty("detail_truncated", false);
        result.addProperty("maicraft_request_id", "fixture-request-" + tick); result.add("path", body.get("path").deepCopy());
        JsonArray path = body.getAsJsonArray("path"), edges = new JsonArray(), middle = new JsonArray();
        for (int i = 0; i < path.size() - 1; i++) {
            JsonObject edge = verdict(i); edge.add("from", path.get(i).deepCopy()); edge.add("to", path.get(i + 1).deepCopy()); edges.add(edge);
        }
        if (body.get("system").getAsString().equals("ae2")) for (int i = 1; i < path.size() - 1; i++) middle.add(verdict(i));
        result.add("edges", edges); result.add("intermediate", middle);
        JsonObject sample = new JsonObject(); sample.addProperty("status", "verified"); sample.addProperty("sample_resource_id", exact);
        result.add("item_route", sample); return result;
    }

    static JsonObject verdict(int index) {
        JsonObject row = new JsonObject(); row.addProperty("index", index); row.addProperty("status", "verified");
        row.addProperty("native_support", true); row.addProperty("verified_connection", true); row.addProperty("operational", true);
        row.addProperty("reason", "fixture_explicit_native_edge"); row.addProperty("provenance", "fixture_native_api"); return row;
    }

    static List<BlockPos> line(int from, int to) {
        List<BlockPos> points = new ArrayList<>(); for (int x = from; x <= to; x++) points.add(new BlockPos(x, 0, 0)); return points;
    }

    private static JsonObject manifest(List<BlockPos> path, String medium) {
        JsonObject result = JsonParser.parseString("""
                {"schema_version":1,"nodes":[],"ports":[],"links":[],"configurations":[],
                 "target":{"node":"sink","medium":"items","resource":"minecraft:iron_ingot"},
                 "observation":{"window_ticks":20,"minimum_output":2,"minimum_events":2,"max_idle_ticks":20}}
                """).getAsJsonObject();
        node(result, "source", "source", path.getFirst()); node(result, "sink", "sink", path.getLast());
        port(result, "from", "source", "output", path.getFirst(), path.get(1), medium);
        port(result, "to", "sink", "input", path.getLast(), path.get(path.size() - 2), medium);
        JsonObject link = new JsonObject(); link.addProperty("id", "route"); link.addProperty("from", "from"); link.addProperty("to", "to");
        link.addProperty("medium", medium); link.addProperty("resource", medium.equals("kinetic") ? "rpm" : "minecraft:iron_ingot"); link.addProperty("amount", 1);
        JsonArray positions = new JsonArray(); path.forEach(point -> positions.add(offset(point))); link.add("path", positions); result.getAsJsonArray("links").add(link);
        return result;
    }
    private static void node(JsonObject root, String id, String kind, BlockPos point) {
        JsonObject node = new JsonObject(); node.addProperty("id", id); node.addProperty("kind", kind); node.add("offset", offset(point)); root.getAsJsonArray("nodes").add(node);
    }
    private static void port(JsonObject root, String id, String node, String direction, BlockPos point, BlockPos neighbor, String medium) {
        JsonObject port = new JsonObject(); port.addProperty("id", id); port.addProperty("node", node); port.addProperty("direction", direction);
        port.addProperty("medium", medium); String face = ProductionConnectionPath.face(point, neighbor); port.addProperty("face", face == null ? "east" : face);
        port.add("offset", offset(point)); root.getAsJsonArray("ports").add(port);
    }
    private static JsonArray offset(BlockPos point) { JsonArray value = new JsonArray(); value.add(point.getX()); value.add(point.getY()); value.add(point.getZ()); return value; }
    static BlockPos position(JsonObject point) { return new BlockPos(point.get("x").getAsInt(), point.get("y").getAsInt(), point.get("z").getAsInt()); }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
