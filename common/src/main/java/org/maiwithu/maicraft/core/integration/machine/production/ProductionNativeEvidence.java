// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.production;

import static org.maiwithu.maicraft.core.integration.machine.production.ProductionNativeJson.*;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.*;

/** Owner-thread native-response accumulator. Protocol/session/authorization checks remain the runtime router's responsibility. */
public final class ProductionNativeEvidence implements ProductionEvidence {
    private static final long MAX_AGE = 1200;
    private final ProductionManifest manifest;
    private final ProductionGraph graph;
    private final ProductionSupplyEvidence supply;
    private final ProductionResourceBindings bindings = new ProductionResourceBindings();
    private boolean bindingsDirty = true;
    private final Map<String,JsonObject> nodes = new LinkedHashMap<>(), ports = new LinkedHashMap<>(), recipes = new LinkedHashMap<>();
    private final Map<String,JsonObject> links = new LinkedHashMap<>(), configurations = new LinkedHashMap<>();
    private final Map<String,JsonObject> configurationReads = new LinkedHashMap<>();
    private final Map<String,String> recipeIds = new LinkedHashMap<>();
    private Set<String> operations = Set.of();
    private String dimension;
    private Point anchor;
    private long now, changedAt;

    public ProductionNativeEvidence(ProductionManifest manifest) { this.manifest = manifest; graph = new ProductionGraph(manifest); supply = new ProductionSupplyEvidence(manifest,this::normalized); }
    public void bind(String dimension, Point anchor) {
        if (dimension == null || dimension.isBlank() || anchor == null) throw new IllegalArgumentException("Production evidence requires a fixed world anchor");
        if (!dimension.equals(this.dimension) || !anchor.equals(this.anchor)) {
            nodes.clear(); ports.clear(); recipes.clear(); links.clear(); configurations.clear(); configurationReads.clear(); supply.clear(); now = 0; changedAt = 0;
            bindings.clear(); bindingsDirty = true;
            recipeIds.clear();
        }
        this.dimension = dimension; this.anchor = anchor;
    }
    public void supportedOperations(Set<String> available) { operations = Set.copyOf(available); }
    public void advance(long serverTick) { if (serverTick < 0) throw new IllegalArgumentException("Negative server tick"); if (serverTick > now) bindingsDirty = true; now = Math.max(now, serverTick); }
    public void observeNode(String nodeId, JsonObject observation) { putObservation(nodes, nodeId, observation, graph.nodes.get(nodeId).offset()); }
    public void observePort(String portId, JsonObject observation) { putObservation(ports, portId, observation, graph.ports.get(portId).offset()); }
    public void observeRecipe(String nodeId, JsonObject result) {
        Node node = graph.nodes.get(nodeId); if (node == null || !node.kind().equals("process")) throw new IllegalArgumentException("Unknown process node");
        recipes.remove(nodeId); bindingsDirty = true;
        String actual = text(result,"recipe_id"), requested = text(result,"requested_recipe_id"), pinned = recipeIds.get(nodeId);
        boolean matches = node.recipeId().equals(actual) || node.recipeId().equals(requested) || pinned != null && pinned.equals(actual);
        if (bound(result,node.offset()) && matches && (pinned == null || pinned.equals(actual))) { recipes.put(nodeId,result.deepCopy()); advance(number(result,"tick")); }
    }
    public void observeLink(String linkId, JsonObject result) {
        Link link = linkById(linkId); links.remove(linkId);
        if (sameWorld(result) && number(result,"tick") != null && "maicraft.connection_inspection.v1".equals(text(result,"schema"))) {
            links.put(link.id(),result.deepCopy()); advance(number(result,"tick"));
        }
    }
    public void configured(String configurationId, JsonObject result) {
        Configuration configuration = manifest.configurations().stream().filter(c -> c.id().equals(configurationId)).findFirst().orElseThrow();
        configurations.remove(configurationId);
        configurationReads.remove(configurationId);
        bindingsDirty = true;
        if (!bound(result, graph.nodes.get(configuration.node()).offset())) return;
        configurations.put(configurationId,result.deepCopy()); advance(number(result,"tick"));
        if (configurationResult(configuration, result, false).status() == Status.VERIFIED) {
            changedAt = Math.max(changedAt,number(result,"tick"));
            // Direction/filter changes invalidate earlier path proofs, even if their geometry remains unchanged.
            links.clear();
        }
    }
    /** Current configuration readback is evidence only: it neither replays the action nor invalidates other observations. */
    public void observeConfiguration(String id, JsonObject result) {
        Configuration configuration = manifest.configurations().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
        configurationReads.put(id,new JsonObject());
        if (bound(result,graph.nodes.get(configuration.node()).offset()) && "machine.configuration".equals(text(result,"operation"))) {
            configurationReads.put(id,result.deepCopy()); advance(number(result,"tick"));
        }
    }
    public boolean configurationActionConfirmed(String id) {
        Configuration c = manifest.configurations().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
        JsonObject result = configurations.get(id);
        return sameWorld(result) && configurationResult(c,result,false).status() == Status.VERIFIED;
    }
    /** Only a router-confirmed deposit into the declared ingress may call this; withdrawal/quotes are not injection evidence. */
    public void confirmedSupply(String linkId, String requestId, JsonObject result) {
        if (anchor == null || number(result,"tick") == null) throw new IllegalArgumentException("Supply evidence requires a bound native result tick");
        supply.confirmed(linkId,requestId,result); advance(number(result,"tick")); changedAt = Math.max(changedAt,number(result,"tick"));
    }
    public JsonObject confirmedSupplies() { return supply.report(); }
    public void initialSupply(String sourceId, Resource selector, long credited, JsonObject snapshot) {
        Node source = graph.nodes.get(sourceId);
        if (source == null || !source.kind().equals("source")) throw new IllegalArgumentException("Unknown production source");
        for (Port port : manifest.ports()) if (port.node().equals(sourceId) && port.direction().equals("output")) {
            boolean present = absolute(port.offset()).equals(point(snapshot.get("position"))) || array(snapshot,"observations").asList().stream()
                    .anyMatch(v -> v.isJsonObject() && absolute(port.offset()).equals(point(v.getAsJsonObject().get("position"))));
            if (present) observePort(port.id(),snapshot);
        }
        supply.initial(source,normalized(selector),credited,sourceObservations());
    }
    public void confirmedSourceSupply(String sourceId, Resource selector, String requestId, JsonObject result) {
        Node source = graph.nodes.get(sourceId);
        if (source == null || anchor == null || number(result,"tick") == null) throw new IllegalArgumentException("Supply requires bound source/native tick");
        supply.confirmedSource(source,normalized(selector),requestId,result); advance(number(result,"tick"));
        if (number(result,"transferred") > 0) { changedAt = Math.max(changedAt,number(result,"tick")); bindingsDirty = true; }
    }

    @Override public Binding resolve(Resource selector) {
        if (bindingsDirty) {
            bindings.clearObserved();
            for (var map : List.of(nodes,ports,recipes)) map.values().stream().filter(this::fresh).forEach(bindings::observe);
            configurations.values().stream().filter(this::freshReceipt).forEach(bindings::observe); bindingsDirty = false;
        }
        return bindings.resolve(selector);
    }
    private Resource normalized(Resource resource) { Binding binding = resolve(resource); return binding.check().status() == Status.VERIFIED ? binding.resource() : resource; }
    @Override public RecipeBinding bindRecipe(Node node) {
        JsonObject result = recipes.get(node.id()); String actual = text(result,"recipe_id");
        if (fresh(result) && actual != null && (node.recipeId().equals(actual) || node.recipeId().equals(text(result,"requested_recipe_id")))) {
            String pinned = recipeIds.putIfAbsent(node.id(),actual);
            if (pinned == null || pinned.equals(actual)) return new RecipeBinding(verified("Native recipe query binds this requested alias to the actual recipe"),node.recipeId(),actual);
        }
        return new RecipeBinding(unknown("Observe this requested recipe at the anchored process before binding its native identity"),node.recipeId(),node.recipeId());
    }

    @Override public Recipe recipe(String recipeId) {
        JsonObject latest = null;
        for (JsonObject result : recipes.values()) if (fresh(result) && (recipeId.equals(text(result,"recipe_id")) || recipeId.equals(text(result,"requested_recipe_id")))
                && (latest == null || number(result,"tick") > number(latest,"tick"))) latest = result;
        return latest == null ? null : ProductionNativeRecipes.decode(latest);
    }
    @Override public Check geometry(Node node) {
        JsonObject observation = nodes.get(node.id());
        if (!fresh(observation)) return unknown("Observe the current anchored node");
        String block = text(observation,"block_id");
        if (block == null) return unknown("Snapshot omitted the node's block identity");
        return block.equals("minecraft:air") ? planned("The declared node has not been installed") : verified("Native server block observed; full assembly remains separately checked");
    }
    @Override public Check process(Node node, Recipe recipe) {
        JsonObject result = recipes.get(node.id());
        if (!fresh(result) || !node.recipeId().equals(text(result,"recipe_id"))) return unknown("Read this recipe at the actual machine position");
        JsonObject obstruction = object(result,"input_obstruction");
        if ("blocked".equals(text(obstruction,"status"))
                && "native_create_depot_held_item_and_press_recipe_selection".equals(text(obstruction,"provenance"))) {
            JsonObject identity = object(obstruction,"identity"); Long amount = number(obstruction,"amount");
            Point receiver = absolute(new Point(node.offset().x(), node.offset().y() - 2, node.offset().z()));
            if (!receiver.equals(point(obstruction.get("receiver_position"))) || amount == null || amount <= 0
                    || !"items".equals(text(identity,"kind")) || identityKey(identity) == null
                    || !identityKey(identity).equals(text(obstruction,"resource_id")))
                return unknown("Native press input obstruction is not bound to this depot and item");
            return planned("Create press depot is blocked by " + amount + " " + text(identity,"id")
                    + "; inspect the existing item before supplying this process");
        }
        return Boolean.TRUE.equals(bool(result,"compatible")) && "verified".equals(text(result,"compatibility"))
                ? verified("Native machine selects the declared installed recipe") : unknown("Native machine/recipe compatibility is not verified");
    }
    @Override public Check port(Port port) {
        JsonObject observation = ports.get(port.id());
        if (!fresh(observation)) return unknown("Observe the exact native port position");
        if (port.medium().equals("kinetic")) {
            JsonObject create = object(object(observation,"native"),"create");
            if (array(create,"shaft_faces").asList().stream().anyMatch(v -> v.isJsonPrimitive() && port.face().equals(v.getAsString())))
                return verified("Native IRotate exposes a shaft on this exact face");
        }
        for (var raw : array(observation,"ports")) if (raw.isJsonObject()) {
            JsonObject row = raw.getAsJsonObject();
            if (!port.face().equals(text(row,"side")) || !port.medium().equals(text(row,"medium"))) continue;
            if ("observed".equals(text(row,"status")) && (Boolean.TRUE.equals(bool(row,port.direction())) || "verified".equals(text(row,port.direction()))))
                return verified("Native sided capability confirms the requested direction");
        }
        // Some devices (e.g. sorters) expose behavior rather than an IItemHandler at that face.
        List<Link> incident = manifest.links().stream().filter(l -> l.from().equals(port.id()) || l.to().equals(port.id())).toList();
        if (!incident.isEmpty() && incident.stream().allMatch(l -> link(l,graph.ports.get(l.from()),graph.ports.get(l.to())).status() == Status.VERIFIED))
            return verified("Exact native path and resource checks establish this endpoint direction");
        return unknown("A handler's existence does not prove its input/output direction or exact-resource admission");
    }
    @Override public Check link(Link link, Port from, Port to) {
        return connection(link,from,to,true);
    }
    @Override public Check topology(Link link, Port from, Port to) { return connection(link,from,to,false); }
    @Override public Check operational(Link link, Port from, Port to) {
        Check topology = topology(link,from,to); if (topology.status() != Status.VERIFIED) return topology;
        return Boolean.TRUE.equals(bool(links.get(link.id()),"operational")) ? verified("Native path operating conditions are verified") : planned("Native path is connected but not operational yet");
    }
    private Check connection(Link link, Port from, Port to, boolean resourceCheck) {
        JsonObject result = links.get(link.id());
        if (!fresh(result)) return unknown("Read a current native connection path");
        if ("unsupported".equals(text(result,"status"))) return new Check(Status.UNSUPPORTED,"native_connection_inspection","Native connection adapter is unsupported");
        if (!Boolean.TRUE.equals(bool(result,"complete")) || !Boolean.TRUE.equals(bool(result,"verified_connection"))
                || !link.resource().medium().equals(text(result,"medium"))) return unknown("Complete native topology is not verified");
        var edges = array(result,"edges");
        if (edges.isEmpty()) return unknown("Connection evidence has no bound edges");
        if (!edges.get(0).isJsonObject() || !edges.get(edges.size()-1).isJsonObject()
                || !absolute(from.offset().step(from.face())).equals(point(edges.get(0).getAsJsonObject().get("to")))
                || !absolute(to.offset().step(to.face())).equals(point(edges.get(edges.size()-1).getAsJsonObject().get("from"))))
            return unknown("Native path does not enter/leave the declared endpoint faces");
        Point previous = absolute(from.offset());
        if (!link.path().isEmpty() && edges.size() != link.path().size()-1) return unknown("Connection path length differs from the manifest");
        for (int i = 0; i < edges.size(); i++) {
            if (!edges.get(i).isJsonObject()) return unknown("Malformed native edge");
            JsonObject edge = edges.get(i).getAsJsonObject(); Point a = point(edge.get("from")), b = point(edge.get("to"));
            if (!previous.equals(a) || b == null || !Boolean.TRUE.equals(bool(edge,"verified_connection"))) return unknown("Unverified/discontinuous native edge");
            if (!link.path().isEmpty() && !b.equals(absolute(link.path().get(i+1)))) return unknown("Native path differs from declared path"); previous = b;
        }
        if (!previous.equals(absolute(to.offset()))) return unknown("Connection ends at a different target");
        if (!resourceCheck) return verified("Native directed topology and anchored path verified; operation/resource checks remain separate");
        if (!Boolean.TRUE.equals(bool(result,"operational"))) return planned("Native topology exists; running conditions are not yet ready");
        if (link.resource().medium().equals("kinetic") && link.resource().id().equals("rpm")) {
            JsonObject observation = ports.get(to.id()); Double rpm = decimal(object(object(observation,"native"),"create"),"getSpeed");
            return fresh(observation) && rpm != null && Math.abs(rpm) >= link.amount() ? verified("Native path and destination RPM requirement verified") : planned("Observe the requested destination RPM after starting the drive");
        }
        JsonObject itemRoute = object(result,"item_route");
        String observed = text(itemRoute,"sample_resource_id");
        if (observed == null) observed = text(result,"resource_id");
        if (!normalized(link.resource()).id().equals(observed) || !"verified".equals(text(result,"resource_compatibility")))
            return unknown("Topology is known but exact-resource compatibility is not");
        return verified("Native path and exact component-sensitive resource compatibility; temporal flow remains unverified");
    }
    @Override public Check configuration(Configuration configuration) {
        if (configurationReads.containsKey(configuration.id())) {
            JsonObject current = configurationReads.get(configuration.id());
            return fresh(current) ? configurationResult(configuration,current,true) : unknown("Refresh the read-only configuration observation; do not replay the writer");
        }
        JsonObject result = configurations.get(configuration.id());
        if (result != null && freshReceipt(result)) return configurationResult(configuration,result,false);
        return operations.contains(configuration.operation()) ? planned("Operation advertised; exact target/action/value still require a confirmed result") : unknown("Discover an executable backend for " + configuration.operation());
    }
    @Override public Check configurationAvailable(Configuration configuration) {
        if (operations.contains(configuration.operation()) || configurations.values().stream().anyMatch(r -> freshReceipt(r)
                && configuration.operation().equals(text(r,"operation")) && Boolean.TRUE.equals(bool(r,"verified_configuration"))))
            return verified("Native operation is available; the requested action must still return its own verified result");
        return unknown("No supported backend has been confirmed for " + configuration.operation());
    }
    @Override public boolean dynamicCondition(String condition) { return Set.of("create:nonzero_rotation","create:not_overstressed","create:millstone_output_space",
            "mekanism:can_function","mekanism:energy_available","mekanism:output_space").contains(condition); }
    @Override public Check condition(Node node, String condition) {
        JsonObject result = recipes.get(node.id());
        if (!fresh(result)) return unknown("Recipe condition evidence is stale or absent");
        JsonObject checks = object(result,"condition_checks");
        if (Boolean.TRUE.equals(bool(checks,condition)) || "verified".equals(text(checks,condition))) return verified("Native recipe condition confirmed: " + condition);
        return Boolean.FALSE.equals(bool(checks,condition)) || "disabled".equals(text(checks,condition)) ? planned("Recipe condition not yet satisfied: " + condition) : unknown("Native recipe condition unknown: " + condition);
    }
    @Override public Check supply(Node source, Resource resource, long amount) {
        JsonObject observation = nodes.get(source.id());
        if (!fresh(observation)) return unknown("Refresh the source inventory/rotation observation");
        if (resource.medium().equals("kinetic") && resource.id().equals("rpm")) {
            JsonObject create = object(object(observation,"native"),"create"); Double speed = decimal(create,"getSpeed");
            return speed != null && Math.abs(speed) >= amount && Boolean.FALSE.equals(bool(create,"isOverStressed"))
                    && Boolean.TRUE.equals(bool(create,"hasNetwork")) ? verified("Observed real rotational supply and unstressed network") : planned("Required native RPM/network/stress supply is not yet observed");
        }
        Map<String,JsonObject> current = sourceObservations();
        Map<String,Recipe> recipeFacts = new LinkedHashMap<>();
        recipes.forEach((id,value) -> { if (fresh(value)) { try { recipeFacts.put(id,ProductionNativeRecipes.decode(value)); } catch (RuntimeException incomplete) { } } });
        return supply.inspect(source,normalized(resource),amount,current,recipeFacts);
    }
    private Map<String,JsonObject> sourceObservations() {
        Map<String,JsonObject> current = new LinkedHashMap<>();
        for (Node node : manifest.nodes()) if (node.kind().equals("source")) {
            JsonObject row = new JsonObject(); var resources = new com.google.gson.JsonArray();
            if (fresh(nodes.get(node.id()))) resources.addAll(array(nodes.get(node.id()),"resources"));
            for (Port port : manifest.ports()) if (port.node().equals(node.id()) && port.direction().equals("output") && fresh(ports.get(port.id()))) resources.addAll(array(ports.get(port.id()),"resources"));
            row.add("resources",resources); current.put(node.id(),row);
        }
        return current;
    }
    private Check configurationResult(Configuration c, JsonObject result, boolean readback) {
        String status = text(result,"status");
        if (!c.operation().equals(text(result,readback ? "configuration_operation" : "operation")) || !text(c.arguments(),"action").equals(text(result,"action"))) return unknown("Configuration result does not match its requested operation/action");
        if (readback && "mismatch".equals(status)) return planned("Current native settings differ; history is retained and no mutation is replayed");
        if (readback && Boolean.FALSE.equals(bool(result,"complete"))) return unknown("Current configuration readback is incomplete");
        if (!(readback ? "matched".equals(status) : "applied".equals(status) || "no_change".equals(status)) || !Boolean.TRUE.equals(bool(result,"verified_configuration"))) return unknown("Native configuration value has not been verified");
        String expected = text(c.arguments(),"resource_id");
        if (expected != null && result.has("filter") && !expected.equals(identityKey(object(result,"filter")))) return unknown("Observed filter identity differs from the requested resource");
        String item = text(c.arguments(),"item_id");
        if (item != null && !item.equals(text(object(result,"filter"),"id"))) return unknown("Observed filter item differs from the semantic request");
        String recipe = text(c.arguments(),"recipe_id");
        if (recipe != null && !recipe.equals(text(result,"requested_recipe_id")) && !recipe.equals(text(result,"recipe_id"))) return unknown("Native pattern result does not identify the requested recipe");
        if (Boolean.TRUE.equals(bool(c.arguments(),"clear")) && !"minecraft:air".equals(text(object(result,"filter"),"id"))) return unknown("Filter clear has not been observed");
        for (String key : List.of("value","enabled","mode","data_type")) if (c.arguments().has(key)) {
            if (!sameScalar(c.arguments().get(key),result.get(key))) return unknown("Observed configuration differs at " + key);
        }
        return verified(readback ? "Current native settings satisfy the request; no writer was replayed" : "Native action result confirms the requested configuration");
    }
    private void putObservation(Map<String,JsonObject> destination, String id, JsonObject result, Point offset) {
        destination.remove(id); bindingsDirty = true; if (anchor == null) throw new IllegalStateException("Bind the production world/anchor before observations");
        JsonObject observation = result;
        if (result != null && result.has("observations")) {
            if (!sameWorld(result) || !"maicraft.machine_snapshot.v1".equals(text(result,"schema"))) return;
            observation = null;
            for (var raw : array(result,"observations")) if (raw.isJsonObject() && absolute(offset).equals(point(raw.getAsJsonObject().get("position")))) {
                observation = raw.getAsJsonObject().deepCopy(); observation.addProperty("dimension",dimension); break;
            }
        }
        if (observation == null) return;
        observation = observation.deepCopy();
        // Individual rows inherit the context already validated by the router and this instance's binding.
        if (!observation.has("dimension")) observation.addProperty("dimension",dimension);
        if (bound(observation,offset) && "server_native".equals(text(observation,"provenance"))) { destination.put(id,observation); advance(number(observation,"tick")); }
    }
    private boolean bound(JsonObject result, Point offset) { return sameWorld(result) && number(result,"tick") != null && number(result,"tick") >= 0 && absolute(offset).equals(point(result.get("position"))); }
    private boolean sameWorld(JsonObject result) { return dimension != null && dimension.equals(text(result,"dimension")); }
    private boolean fresh(JsonObject result) { Long tick = number(result,"tick"); return sameWorld(result) && tick != null && tick >= changedAt && tick <= now && now-tick <= MAX_AGE; }
    private boolean freshReceipt(JsonObject result) { Long tick = number(result,"tick"); return sameWorld(result) && tick != null && tick >= 0 && tick <= now && now-tick <= MAX_AGE; }
    private Point absolute(Point offset) { if (anchor == null) throw new IllegalStateException("Production evidence is not bound"); return new Point(Math.addExact(anchor.x(),offset.x()),Math.addExact(anchor.y(),offset.y()),Math.addExact(anchor.z(),offset.z())); }
    private Link linkById(String id) { return manifest.links().stream().filter(l -> l.id().equals(id)).findFirst().orElseThrow(); }
    private static Check verified(String detail) { return new Check(Status.VERIFIED,"server_native",detail); }
    private static Check planned(String detail) { return new Check(Status.PLANNED,"server_native",detail); }
    private static Check unknown(String detail) { return Check.unknown(detail); }
}
