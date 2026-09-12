// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.production;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.maiwithu.maicraft.core.integration.machine.MachinePlanningBudget;

/** Declarative production intent shared by authored and Ponder-derived layouts; no native handles or world access. */
public record ProductionManifest(List<Node> nodes, List<Port> ports, List<Link> links,
                                 List<Configuration> configurations, Target target, Observation observation) {
    public static final Set<String> MEDIA = Set.of("items", "fluids", "chemicals", "energy", "kinetic");
    public static final Set<String> FACES = Set.of("north", "south", "east", "west", "up", "down");
    public record Point(int x, int y, int z) {
        public Point step(String face) { return switch (face) {
            case "north" -> new Point(x, y, z - 1); case "south" -> new Point(x, y, z + 1);
            case "west" -> new Point(x - 1, y, z); case "east" -> new Point(x + 1, y, z);
            case "up" -> new Point(x, y + 1, z); case "down" -> new Point(x, y - 1, z);
            default -> throw new IllegalArgumentException("Unknown face " + face);
        }; }
        public long distance(Point other) { return Math.abs((long) x - other.x) + Math.abs((long) y - other.y) + Math.abs((long) z - other.z); }
        public JsonArray json() { JsonArray a = new JsonArray(); a.add(x); a.add(y); a.add(z); return a; }
    }
    public record Resource(String medium, String id) {}
    public record Node(String id, String kind, Point offset, String recipeId, long batches, String materialPolicy) {}
    public record Port(String id, String node, Point offset, String face, String medium, String direction) {}
    public record Link(String id, String from, String to, Resource resource, long amount, List<Point> path, List<String> configurations) {
        public Link { path = List.copyOf(path); configurations = List.copyOf(configurations); }
    }
    public record Configuration(String id, String node, String operation, String stage, JsonObject arguments) {
        public Configuration { arguments = arguments.deepCopy(); }
        @Override public JsonObject arguments() { return arguments.deepCopy(); }
    }
    public record Target(String node, Resource resource) {}
    public record Observation(long windowTicks, long minimumOutput, int minimumEvents, long maxIdleTicks) {}
    public ProductionManifest {
        nodes = List.copyOf(nodes); ports = List.copyOf(ports); links = List.copyOf(links); configurations = List.copyOf(configurations);
    }

    public static ProductionManifest parse(JsonObject input) {
        keys(input, "schema_version", "nodes", "ports", "links", "configurations", "target", "observation");
        number(input, "schema_version", 1, 1);
        var budget = MachinePlanningBudget.current();
        List<Node> nodes = new ArrayList<>(); List<Port> ports = new ArrayList<>(); List<Link> links = new ArrayList<>();
        List<Configuration> configurations = new ArrayList<>();
        Set<String> nodeIds = new HashSet<>(), portIds = new HashSet<>(), linkIds = new HashSet<>(), configIds = new HashSet<>();
        for (var raw : array(input, "nodes", 1, budget.maxComponents())) {
            JsonObject n = object(raw, "node"); keys(n, "id", "kind", "offset", "recipe_id", "batches", "material_policy");
            String kind = choice(n, "kind", Set.of("source", "process", "sink", "transport"));
            if (!kind.equals("process") && (n.has("recipe_id") || n.has("batches"))) throw bad("Only process nodes declare recipes/batches");
            if (!kind.equals("source") && n.has("material_policy")) throw bad("Only sources declare material_policy");
            String recipe = kind.equals("process") ? string(n, "recipe_id") : null;
            long batches = kind.equals("process") ? number(n, "batches", 1, 1_000_000) : 0;
            String policy = n.has("material_policy") ? choice(n, "material_policy", Set.of("ordinary", "storage_available", "inventory_only")) : "inventory_only";
            nodes.add(new Node(unique(n, nodeIds), kind, point(n.get("offset")), recipe, batches, policy));
        }
        for (var raw : array(input, "ports", 1, budget.maxConnections())) {
            JsonObject p = object(raw, "port"); keys(p, "id", "node", "offset", "face", "medium", "direction");
            ports.add(new Port(unique(p, portIds), reference(p, "node", nodeIds), point(p.get("offset")),
                    choice(p, "face", FACES), choice(p, "medium", MEDIA), choice(p, "direction", Set.of("input", "output"))));
        }
        if (input.has("configurations")) for (var raw : array(input, "configurations", 0, budget.maxConnections())) {
            JsonObject c = object(raw, "configuration"); keys(c, "id", "node", "operation", "stage", "arguments");
            JsonObject arguments = object(c.get("arguments"), "configuration.arguments");
            keys(arguments, "action", "value", "clear", "item_id", "components", "resource_id", "side", "transmission", "relative_side", "data_type", "enabled", "mode", "recipe_id");
            string(arguments, "action");
            for (String text : Set.of("item_id","resource_id","side","transmission","relative_side","data_type","mode","recipe_id")) if (arguments.has(text)) string(arguments,text);
            for (String bool : Set.of("clear","enabled")) if (arguments.has(bool) && (!arguments.get(bool).isJsonPrimitive() || !arguments.getAsJsonPrimitive(bool).isBoolean())) throw bad(bool+" must be a boolean");
            if (arguments.has("value")) number(arguments,"value",Integer.MIN_VALUE,Integer.MAX_VALUE);
            if (arguments.has("components") && !arguments.get("components").isJsonObject()) throw bad("components must be an identity object");
            if (arguments.has("side")) choice(arguments,"side",FACES);
            configurations.add(new Configuration(unique(c, configIds), reference(c, "node", nodeIds), choice(c, "operation", Set.of("machine.configure")),
                    c.has("stage") ? choice(c, "stage", Set.of("configure", "start")) : "configure", arguments));
        }
        long pathCells = 0;
        for (var raw : array(input, "links", 1, budget.maxConnections())) {
            JsonObject l = object(raw, "link"); keys(l, "id", "from", "to", "medium", "resource", "amount", "path", "configurations");
            List<Point> path = new ArrayList<>(); List<String> configs = new ArrayList<>();
            if (l.has("path")) for (var p : array(l, "path", 2, budget.maxTargets())) {
                if (++pathCells > budget.maxTargets()) throw bad("Production paths exceed the physical target budget"); path.add(point(p));
            }
            if (l.has("configurations")) for (var c : array(l, "configurations", 0, budget.maxConnections())) {
                if (!c.isJsonPrimitive() || !c.getAsJsonPrimitive().isString() || !configIds.contains(c.getAsString()))
                    throw bad("Link refers to unknown configuration");
                if (configs.contains(c.getAsString())) throw bad("Duplicate link configuration"); configs.add(c.getAsString());
            }
            links.add(new Link(unique(l, linkIds), reference(l, "from", portIds), reference(l, "to", portIds),
                    resource(l), number(l, "amount", 1, Long.MAX_VALUE), path, configs));
        }
        JsonObject target = object(input.get("target"), "target"); keys(target, "node", "medium", "resource");
        JsonObject observe = object(input.get("observation"), "observation");
        keys(observe, "window_ticks", "minimum_output", "minimum_events", "max_idle_ticks");
        long window = number(observe, "window_ticks", 20, 72_000);
        Observation observation = new Observation(window, number(observe, "minimum_output", 1, Long.MAX_VALUE),
                (int) number(observe, "minimum_events", 2, 10_000), number(observe, "max_idle_ticks", 1, 72_000));
        return new ProductionManifest(nodes, ports, links, configurations,
                new Target(reference(target, "node", nodeIds), resource(target)), observation);
    }

    private static Resource resource(JsonObject value) { return new Resource(choice(value, "medium", MEDIA), string(value, "resource")); }
    private static Point point(JsonElement element) {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 3) throw bad("offset/path position requires [x,y,z]");
        int[] xyz = new int[3]; int radius = MachinePlanningBudget.current().maxRadius();
        for (int i = 0; i < 3; i++) xyz[i] = (int) integer(element.getAsJsonArray().get(i), -radius, radius, "coordinate");
        return new Point(xyz[0], xyz[1], xyz[2]);
    }
    private static String unique(JsonObject row, Set<String> used) {
        String id = string(row, "id"); if (!used.add(id)) throw bad("Duplicate identifier " + id); return id;
    }
    private static String reference(JsonObject row, String key, Set<String> ids) {
        String id = string(row, key); if (!ids.contains(id)) throw bad("Unknown " + key + " reference " + id); return id;
    }
    private static String choice(JsonObject row, String key, Set<String> choices) {
        String value = string(row, key); if (!choices.contains(value)) throw bad("Unsupported " + key + ": " + value); return value;
    }
    static String string(JsonObject row, String key) {
        JsonElement value = row.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank() || value.getAsString().length() > 1024) throw bad("Invalid " + key);
        return value.getAsString();
    }
    private static long number(JsonObject row, String key, long min, long max) { return integer(row.get(key), min, max, key); }
    private static long integer(JsonElement value, long min, long max, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw bad(field + " requires an integer");
        try { long n = value.getAsBigDecimal().longValueExact(); if (n < min || n > max) throw bad(field + " outside allowed bounds"); return n; }
        catch (ArithmeticException | NumberFormatException invalid) { throw bad(field + " requires a bounded integer"); }
    }
    private static JsonArray array(JsonObject row, String key, int min, int max) {
        JsonElement value = row.get(key);
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() < min || value.getAsJsonArray().size() > max)
            throw bad(key + " must contain " + min + ".." + max + " entries");
        return value.getAsJsonArray();
    }
    private static JsonObject object(JsonElement value, String label) { if (value == null || !value.isJsonObject()) throw bad(label + " must be an object"); return value.getAsJsonObject(); }
    private static void keys(JsonObject object, String... names) {
        if (object == null) throw bad("Production manifest is required"); Set<String> allowed = Set.of(names);
        for (String key : object.keySet()) if (!allowed.contains(key)) throw bad("Unknown production field " + key);
    }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}
