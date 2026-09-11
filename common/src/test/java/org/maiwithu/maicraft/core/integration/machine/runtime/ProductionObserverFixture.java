// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashSet;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.server.machine.ProductionEventJournal;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

final class ProductionObserverFixture implements ProductionWork {
    final ProductionRunPlan plan;
    final ProductionOutputMonitor monitor;
    ProductionEventJournal journal = new ProductionEventJournal("minecraft:overworld");
    long tick = 100, stock;
    int eventRequests;
    final Object connection = new Object();
    final java.util.List<JsonObject> eventBodies = new java.util.ArrayList<>();
    BlockPos observed;
    boolean delay;
    String pending;
    int navigationDelay, navigationRemaining;
    BlockPos navigationGoal;

    ProductionObserverFixture(JsonObject manifest) {
        plan = new ProductionRunPlan(BlockPos.ZERO, "minecraft:overworld", manifest);
        monitor = new ProductionOutputMonitor(plan, this);
    }

    static JsonObject manifest(int processes) {
        JsonObject result = JsonParser.parseString("""
                {"schema_version":1,"nodes":[],"ports":[],"links":[],"configurations":[],
                 "target":{"node":"sink","medium":"items","resource":"test:sheet"},
                 "observation":{"window_ticks":20,"minimum_output":3,"minimum_events":3,"max_idle_ticks":20}}
                """).getAsJsonObject();
        for (int i = 0; i < processes; i++) {
            node(result, "p" + i, "process", i * 20);
            port(result, "out" + i, "p" + i, i * 20, "output");
            link(result, "delivery" + i, "out" + i, "sink-in");
        }
        node(result, "sink", "sink", 100); port(result, "sink-in", "sink", 100, "input");
        return result;
    }

    static void node(JsonObject manifest, String id, String kind, int x) {
        JsonObject node = new JsonObject(); node.addProperty("id", id); node.addProperty("kind", kind);
        node.add("offset", JsonParser.parseString("[" + x + ",0,0]"));
        if (kind.equals("process")) { node.addProperty("recipe_id", "test:press"); node.addProperty("batches", 3); }
        manifest.getAsJsonArray("nodes").add(node);
    }

    static void port(JsonObject manifest, String id, String node, int x, String direction) {
        JsonObject port = new JsonObject(); port.addProperty("id", id); port.addProperty("node", node);
        port.add("offset", JsonParser.parseString("[" + x + ",0,0]")); port.addProperty("face", "up");
        port.addProperty("medium", "items"); port.addProperty("direction", direction); manifest.getAsJsonArray("ports").add(port);
    }

    static void link(JsonObject manifest, String id, String from, String to) {
        JsonObject link = new JsonObject(); link.addProperty("id", id); link.addProperty("from", from); link.addProperty("to", to);
        link.addProperty("medium", "items"); link.addProperty("resource", "test:sheet"); link.addProperty("amount", 3);
        manifest.getAsJsonArray("links").add(link);
    }

    void baseline() { for (int i = 0; i < 20; i++) if (monitor.baseline()) return; throw new AssertionError("Baseline did not settle"); }
    boolean settle() { for (int i = 0; i < 100; i++) if (monitor.tick()) return true; return false; }

    void output(int x, long at, String recipe) {
        JsonObject event = new JsonObject(); event.addProperty("kind", "recipe_output"); event.addProperty("producer", x + ",0,0");
        event.addProperty("recipe_id", recipe); event.addProperty("tick", at); event.addProperty("provenance", "native_recipe_output");
        JsonArray outputs = new JsonArray(); outputs.add(resource("sheet#plain", 1)); event.add("outputs", outputs); journal.append(event);
    }

    void transfer(int from, int to, long at, String exact, long amount) {
        transfer(from, to, at, exact, amount, journal.markExtraction(at));
    }

    void transfer(int from, int to, long at, String exact, long amount, ProductionEventJournal.OrderingMarker extraction) {
        JsonObject event = resource(exact, amount); event.addProperty("kind", "resource_transferred");
        event.addProperty("producer", from + ",0,0"); event.addProperty("source", from + ",0,0"); event.addProperty("destination", to + ",0,0");
        event.addProperty("tick", at); event.addProperty("provenance", "mekanism.transporter.native_forward_delivery");
        if (extraction != null) {
            event.addProperty("extraction_scope", extraction.scope()); event.addProperty("extraction_sequence", extraction.sequence());
            event.addProperty("extraction_tick", extraction.tick());
        }
        journal.append(event);
    }

    static JsonObject resource(String exact, long amount) {
        JsonObject identity = new JsonObject(); identity.addProperty("kind", "items"); identity.addProperty("id", "test:sheet");
        JsonObject components = new JsonObject(); if (!exact.equals("sheet#plain")) components.addProperty("test:name", exact);
        identity.add("components", components);
        JsonObject value = new JsonObject(); value.add("identity", identity); value.addProperty("resource_id", exact); value.addProperty("amount", amount);
        return value;
    }

    @Override public boolean approach(BlockPos position) { throw new AssertionError("Observer must not request interaction navigation"); }
    @Override public boolean observe(BlockPos position) {
        if (!position.equals(navigationGoal)) { navigationGoal = position; navigationRemaining = navigationDelay; }
        if (navigationRemaining > 0) { navigationRemaining--; return false; }
        observed = position; return true;
    }
    @Override public TaskState advanceChild(Task task) { throw new AssertionError("Observer must not create world-operation tasks"); }
    @Override public void extendDeadlineTo(long gameTick) {}

    @Override public JsonObject request(String operation, JsonObject arguments, boolean mutating) {
        check(!mutating, "Production observation must be read-only");
        if (delay) {
            String key = operation + arguments;
            if (pending == null) { pending = key; return null; }
            check(pending.equals(key), "Observer changed a request while its receipt was pending"); pending = null;
        }
        if (operation.equals("machine.production_events") && arguments.has("release_watch") && arguments.get("release_watch").getAsBoolean())
            return journal.release(connection, endpoints(arguments), arguments.has("scope") ? arguments.get("scope").getAsString() : null);
        for (var raw : arguments.getAsJsonArray("positions")) {
            JsonObject p = raw.getAsJsonObject(); BlockPos position = new BlockPos(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
            check(observed.distSqr(position) <= 16, "Group extends beyond the guaranteed observation range");
        }
        if (operation.equals("machine.production_events")) {
            eventRequests++; eventBodies.add(arguments.deepCopy()); var positions = endpoints(arguments);
            boolean baseline = arguments.has("baseline") && arguments.get("baseline").getAsBoolean();
            long after = baseline ? journal.latestSequence() : arguments.get("after_sequence").getAsLong();
            check(journal.retain(connection, positions), "Fixture exceeded native retention quota");
            JsonObject result = journal.page(after, positions, tick, arguments.has("scope") ? arguments.get("scope").getAsString() : null);
            result.add("retention", journal.retention(connection, positions)); return result;
        }
        check(operation.equals("machine.snapshot"), "Unexpected observer operation");
        JsonObject result = new JsonObject(); result.addProperty("dimension", "minecraft:overworld"); result.addProperty("tick", tick);
        result.addProperty("truncated", false); JsonArray observations = new JsonArray(); JsonObject observation = new JsonObject();
        observation.add("position", arguments.getAsJsonArray("positions").get(0));
        JsonArray resources = new JsonArray(); JsonObject resource = resource("sheet#plain", stock);
        resource.addProperty("storage_id", "sink-up-0"); resource.addProperty("side", "up"); resources.add(resource); observation.add("resources", resources);
        JsonArray ports = new JsonArray(); ports.add(JsonParser.parseString("{\"side\":\"up\",\"medium\":\"items\",\"status\":\"observed\"}"));
        observation.add("ports", ports); observations.add(observation); result.add("observations", observations); return result;
    }

    static java.util.Set<String> endpoints(JsonObject arguments) {
        var positions = new LinkedHashSet<String>();
        arguments.getAsJsonArray("positions").forEach(raw -> { var p = raw.getAsJsonObject(); positions.add(p.get("x") + "," + p.get("y") + "," + p.get("z")); });
        return positions;
    }

    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
