// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.production;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionEvidence.*;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.*;

/** Compiles production dependencies using adapter evidence; neither geometry nor inventory presence is production proof. */
public final class ProductionDesignCompiler {
    public record Compilation(boolean valid, boolean ready, JsonObject report) {
        public Compilation { report = report.deepCopy(); }
        @Override public JsonObject report() { return report.deepCopy(); }
        public boolean canEnter(String stage) { return ProductionStageReadiness.canEnter(report,stage); }
    }
    private ProductionDesignCompiler() {}

    public static Compilation compile(JsonObject input, ProductionEvidence evidence) {
        Report report = new Report();
        try {
            ProductionManifest manifest = ProductionManifest.parse(input);
            new ProductionGraph(manifest); // Shape/topology errors remain errors even before identity discovery.
            JsonObject resolved = bindResources(input,manifest,evidence,report);
            bindRecipes(resolved,manifest,evidence,report);
            report.json.add("resolved_manifest",resolved);
            if (report.unboundResources) return report.finish();
            manifest = ProductionManifest.parse(resolved);
            ProductionGraph graph = new ProductionGraph(manifest);
            graph.order.forEach(report.order::add);
            Set<String> usedPorts = new LinkedHashSet<>();
            for (Link link : manifest.links()) { usedPorts.add(link.from()); usedPorts.add(link.to()); }
            for (String port : usedPorts) report.require("ports", port, "observe", () -> evidence.port(graph.ports.get(port)));
            for (Node node : manifest.nodes()) {
                report.require("geometry", node.id(), () -> evidence.geometry(node));
                if (node.kind().equals("source")) sources(node, graph, evidence, report);
                else if (node.kind().equals("process")) process(node, graph, evidence, report);
                else if (node.kind().equals("transport")) {
                    if (!ProductionGraph.amounts(graph.incoming.get(node.id())).equals(ProductionGraph.amounts(graph.outgoing.get(node.id()))))
                        report.error("transport_changes_resource_or_quantity", node.id());
                }
                else if (graph.incoming.get(node.id()).isEmpty()) report.error("unconnected_sink", node.id());
            }
            for (Configuration configuration : manifest.configurations()) {
                report.require("configuration_availability", configuration.id(), "before_start", () -> evidence.configurationAvailable(configuration));
                report.require("configuration", configuration.id(), configuration.stage().equals("start") ? "before_observe" : "before_supply", () -> evidence.configuration(configuration));
                JsonObject action = new JsonObject(); action.addProperty("id", configuration.id()); action.addProperty("node", configuration.node());
                action.addProperty("operation", configuration.operation()); action.addProperty("stage", configuration.stage());
                action.add("offset", graph.nodes.get(configuration.node()).offset().json()); action.add("arguments", configuration.arguments());
                (configuration.stage().equals("start") ? report.start : report.configure).add(action);
            }
            for (Link link : manifest.links()) {
                report.require("topology", link.id(), () -> evidence.topology(link, graph.ports.get(link.from()), graph.ports.get(link.to())));
                report.require("operational_connection", link.id(), "before_observe", () -> evidence.operational(link, graph.ports.get(link.from()), graph.ports.get(link.to())));
                report.require("connection", link.id(), "observe", () -> evidence.link(link, graph.ports.get(link.from()), graph.ports.get(link.to())));
                transfer(link, graph, report);
                report.postcondition("flow", link.id(), "Observe the exact resource crossing this link; port compatibility or source depletion alone is insufficient");
            }
            supplyHints(graph,report);
            target(manifest, graph, report);
            report.json.addProperty("node_count", manifest.nodes().size()); report.json.addProperty("link_count", manifest.links().size());
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            report.error("invalid_production_manifest", invalid.getMessage());
        }
        return report.finish();
    }
    private static void supplyHints(ProductionGraph graph, Report report) {
        for (var raw : report.supplies) {
            JsonObject row = raw.getAsJsonObject(); Resource resource = new Resource(row.get("medium").getAsString(),row.get("resource").getAsString());
            long first = 0; Set<String> consumers = new LinkedHashSet<>();
            for (Link link : graph.outgoing.get(row.get("node").getAsString())) if (link.resource().equals(resource)) {
                long batch = ProductionRefill.firstBatch(link,graph,report.processRecipes,r -> r);
                first = resource.medium().equals("kinetic") ? Math.max(first,batch) : Math.addExact(first,batch);
                consumers.addAll(ProductionRefill.consumers(link,graph,r -> r));
            }
            row.addProperty("first_batch",Math.min(row.get("amount").getAsLong(),first)); JsonArray ids = new JsonArray(); consumers.forEach(ids::add); row.add("pacing_consumers",ids);
            if (consumers.isEmpty()) report.error("source_has_no_process_consumer",row.get("node").getAsString()+"/"+resource);
            row.addProperty("preexisting_stock_pacing","unpaced_existing_stock_must_be_reported_separately");
        }
    }

    private static JsonObject bindResources(JsonObject input, ProductionManifest manifest, ProductionEvidence evidence, Report report) {
        Map<Resource,Binding> bindings = new LinkedHashMap<>();
        manifest.links().forEach(l -> bindings.put(l.resource(),null)); bindings.put(manifest.target().resource(),null);
        JsonArray rows = new JsonArray();
        bindings.replaceAll((selector, unused) -> {
            Binding binding;
            try { binding = evidence.resolve(selector); } catch (RuntimeException unavailable) { binding = new Binding(Check.unknown("Native identity resolution failed"),selector,null); }
            if (binding == null) binding = new Binding(Check.unknown("Native identity is unavailable"),selector,null);
            Binding value = binding; report.require("resources",selector.toString(),() -> value.check());
            if (binding.check().status() != Status.VERIFIED) report.unboundResources = true;
            JsonObject row = resource(selector); row.addProperty("resource_id",binding.resource().id());
            if (binding.identity() != null) row.add("identity",binding.identity()); rows.add(row); return binding;
        });
        report.json.add("resource_bindings",rows); JsonObject result = input.deepCopy();
        for (var value : result.getAsJsonArray("links")) { JsonObject link = value.getAsJsonObject(); Resource key = new Resource(link.get("medium").getAsString(),link.get("resource").getAsString()); link.addProperty("resource",bindings.get(key).resource().id()); }
        result.getAsJsonObject("target").addProperty("resource",bindings.get(manifest.target().resource()).resource().id()); return result;
    }

    private static void bindRecipes(JsonObject resolved, ProductionManifest manifest, ProductionEvidence evidence, Report report) {
        JsonArray bindings = new JsonArray();
        for (int i = 0; i < manifest.nodes().size(); i++) {
            Node node = manifest.nodes().get(i); if (!node.kind().equals("process")) continue;
            RecipeBinding binding;
            try { binding = evidence.bindRecipe(node); } catch (RuntimeException missing) { binding = new RecipeBinding(Check.unknown("Native recipe binding unavailable"),node.recipeId(),node.recipeId()); }
            RecipeBinding value = binding; report.require("recipe",node.id()+"/identity",() -> value.check());
            String canonical = binding.check().status() == Status.VERIFIED && node.recipeId().equals(binding.requestedRecipeId()) ? binding.recipeId() : node.recipeId();
            if (canonical == null || canonical.isBlank()) { report.error("invalid_recipe_binding",node.id()); canonical = node.recipeId(); }
            resolved.getAsJsonArray("nodes").get(i).getAsJsonObject().addProperty("recipe_id",canonical);
            JsonObject row = new JsonObject(); row.addProperty("node",node.id()); row.addProperty("requested_recipe_id",node.recipeId()); row.addProperty("recipe_id",canonical); bindings.add(row);
        }
        report.json.add("recipe_bindings",bindings);
    }

    private static void transfer(Link link, ProductionGraph graph, Report report) {
        Port from = graph.ports.get(link.from()), to = graph.ports.get(link.to());
        Node source = graph.nodes.get(from.node()), target = graph.nodes.get(to.node());
        JsonObject transfer = resource(link.resource()); transfer.addProperty("id", link.id());
        transfer.addProperty("from_node", from.node()); transfer.addProperty("to_node", to.node());
        transfer.addProperty("from_port", from.id()); transfer.addProperty("to_port", to.id());
        transfer.add("source_offset", from.offset().json()); transfer.add("destination_offset", to.offset().json());
        transfer.addProperty("source_face", from.face()); transfer.addProperty("destination_face", to.face());
        transfer.addProperty("total_budget", link.amount());
        transfer.addProperty("source_is_external_supply", source.kind().equals("source"));
        transfer.addProperty("target_process_batches", target.batches());
        transfer.addProperty("suggested_refill", ProductionRefill.firstBatch(link,graph,report.processRecipes,r -> r));
        transfer.addProperty("refill_policy", "Only observed input deficits; cumulative confirmed net injection stays within total_budget; reobserve before another window");
        transfer.addProperty("budget_semantics", link.resource().medium().equals("kinetic") ? "minimum_rpm_not_consumed_quantity" : "maximum_total_transfer_this_window");
        JsonArray path = new JsonArray(); link.path().forEach(p -> path.add(p.json())); transfer.add("path", path);
        JsonArray configs = new JsonArray(); link.configurations().forEach(configs::add); transfer.add("configurations", configs);
        report.transfers.add(transfer);
    }

    private static void sources(Node node, ProductionGraph graph, ProductionEvidence evidence, Report report) {
        if (graph.outgoing.get(node.id()).isEmpty()) { report.error("unconnected_source", node.id()); return; }
        for (var demand : ProductionGraph.amounts(graph.outgoing.get(node.id())).entrySet()) {
            report.require("supply", node.id() + "/" + demand.getKey(), demand.getKey().medium().equals("kinetic") ? "before_observe" : "before_start", () -> evidence.supply(node, demand.getKey(), demand.getValue()));
            JsonObject item = resource(demand.getKey()); item.addProperty("node", node.id()); item.addProperty("amount", demand.getValue());
            item.addProperty("material_policy", node.materialPolicy()); item.add("offset", node.offset().json()); report.supplies.add(item);
        }
    }

    private static void process(Node node, ProductionGraph graph, ProductionEvidence evidence, Report report) {
        Recipe recipe;
        try { recipe = evidence.recipe(node.recipeId()); }
        catch (RuntimeException unavailable) { report.require("recipe", node.id(), () -> Check.unknown(unavailable.getMessage())); return; }
        if (recipe == null || !recipe.complete()) {
            report.require("recipe", node.id(), () -> Check.unknown("Resolve complete installed recipe " + node.recipeId() + "; generic display ingredients are insufficient")); return;
        }
        if (!node.recipeId().equals(recipe.id()) || recipe.provenance() == null || recipe.provenance().isBlank() || recipe.outputs().isEmpty()) {
            report.error("invalid_recipe_evidence", node.id()); return;
        }
        report.require("recipe", node.id(), () -> new Check(Status.VERIFIED, recipe.provenance(), "Complete installed recipe semantics"));
        report.processRecipes.put(node.id(), recipe);
        report.require("process", node.id(), () -> evidence.process(node, recipe));
        for (String condition : recipe.conditions()) report.require("condition", node.id() + "/" + condition, evidence.dynamicCondition(condition) ? "before_observe" : "before_supply", () -> evidence.condition(node, condition));
        Map<Resource, Long> input = ProductionGraph.amounts(graph.incoming.get(node.id()));
        Map<Resource, Long> output = ProductionGraph.amounts(graph.outgoing.get(node.id()));
        Map<Resource, Long> materials = new LinkedHashMap<>(); input.forEach((r, n) -> { if (ProductionGraph.material(r)) materials.put(r, n); });
        if (!ProductionRecipeBalance.covers(recipe.inputs(), materials, node.batches())) report.error("recipe_inputs_underfunded", node.id());
        for (Resource item : materials.keySet()) if (recipe.inputs().stream().noneMatch(i -> i.alternatives().contains(item)))
            report.error("unexpected_process_input", node.id() + "/" + item);
        for (var power : recipe.minimumPower().entrySet()) {
            if (ProductionGraph.material(power.getKey()) || power.getValue() <= 0) { report.error("invalid_power_requirement", node.id()); continue; }
            long required = power.getKey().medium().equals("kinetic") ? power.getValue() : Math.multiplyExact(power.getValue(), node.batches());
            if (input.getOrDefault(power.getKey(), 0L) < required) report.error("missing_or_insufficient_power", node.id() + "/" + power.getKey());
        }
        Map<Resource, Long> capacity = new LinkedHashMap<>(); Set<Resource> probabilistic = new LinkedHashSet<>();
        for (Output product : recipe.outputs()) {
            capacity.merge(product.resource(), Math.multiplyExact(product.amount(), node.batches()), Math::addExact);
            if (product.chance() < 1) probabilistic.add(product.resource());
        }
        for (var sent : output.entrySet()) if (!sent.getKey().medium().equals("kinetic") && sent.getValue() > capacity.getOrDefault(sent.getKey(), 0L))
            report.error("process_output_overallocated", node.id() + "/" + sent.getKey());
        for (var product : capacity.entrySet()) if (output.getOrDefault(product.getKey(), 0L) < product.getValue())
            report.error("missing_output_or_byproduct_capacity", node.id() + "/" + product.getKey());
        for (Resource resource : probabilistic) report.postcondition("stochastic_yield", node.id() + "/" + resource,
                "Output amount is a maximum route capacity, not guaranteed yield; transfer only observed available outputs and measure the actual target result");
        JsonObject stage = new JsonObject(); stage.addProperty("node", node.id()); stage.addProperty("recipe_id", recipe.id());
        stage.addProperty("batches", node.batches()); stage.add("offset", node.offset().json()); stage.addProperty("source", recipe.provenance());
        JsonArray products = new JsonArray();
        for (Output product : recipe.outputs()) { JsonObject row = resource(product.resource()); row.addProperty("amount_per_batch", product.amount()); row.addProperty("chance", product.chance()); products.add(row); }
        stage.add("outputs", products);
        JsonArray ingredients = new JsonArray();
        for (Ingredient ingredient : recipe.inputs()) {
            JsonObject row = new JsonObject(); row.addProperty("id", ingredient.id()); row.addProperty("amount_per_batch", ingredient.amount()); row.addProperty("consumed", ingredient.consumed());
            JsonArray options = new JsonArray(); ingredient.alternatives().forEach(r -> options.add(resource(r))); row.add("alternatives", options); ingredients.add(row);
        }
        stage.add("ingredients", ingredients);
        report.recipes.add(stage);
    }

    private static void target(ProductionManifest manifest, ProductionGraph graph, Report report) {
        long arrival = graph.incoming.get(manifest.target().node()).stream().filter(l -> l.resource().equals(manifest.target().resource()))
                .mapToLong(Link::amount).reduce(0, Math::addExact);
        if (arrival < manifest.observation().minimumOutput()) report.error("target_underfunded", manifest.target().node());
        if (manifest.nodes().stream().noneMatch(n -> n.kind().equals("process")))
            report.error("no_processing_stage", "A source-to-sink transfer alone cannot establish production");
        // Trace this exact output identity. An unrelated process cannot legitimize delivery of pre-existing finished stock.
        Map<String, Boolean> origins = new LinkedHashMap<>();
        Set<String> producers = new LinkedHashSet<>();
        for (Link link : graph.incoming.get(manifest.target().node())) if (link.resource().equals(manifest.target().resource())
                && !processedOrigin(graph.ports.get(link.from()).node(), link.resource(), graph, origins, producers))
            report.error("target_has_unprocessed_supply", link.id());
        long events = producers.stream().mapToLong(id -> graph.nodes.get(id).batches()).reduce(0, Math::addExact);
        if (producers.stream().allMatch(report.processRecipes::containsKey) && events < manifest.observation().minimumEvents())
            report.error("insufficient_planned_production_events", "Target-producing batches cannot meet minimum_events");
        JsonObject observation = resource(manifest.target().resource()); observation.addProperty("node", manifest.target().node());
        observation.add("offset", graph.nodes.get(manifest.target().node()).offset().json());
        observation.addProperty("window_ticks", manifest.observation().windowTicks()); observation.addProperty("minimum_output", manifest.observation().minimumOutput());
        observation.addProperty("minimum_events", manifest.observation().minimumEvents()); observation.addProperty("max_idle_ticks", manifest.observation().maxIdleTicks());
        observation.addProperty("require_native_production_events", true); observation.addProperty("require_delivery_to_sink", true);
        observation.addProperty("external_changes", "must_be_excluded_or_reported_ambiguous");
        observation.addProperty("deduplicate_storage_aliases", true); report.json.add("observation", observation);
        JsonArray eligible = new JsonArray(); producers.forEach(eligible::add); observation.add("producer_nodes", eligible);
        observation.addProperty("maximum_planned_events", events);
        report.postcondition("production", manifest.target().node(), "Require distinct native processing events and matching sink delivery across the full observation window; a snapshot or inventory increase alone is insufficient");
    }

    private static boolean processedOrigin(String id, Resource resource, ProductionGraph graph, Map<String, Boolean> memo, Set<String> producers) {
        if (memo.containsKey(id)) return memo.get(id);
        Node node = graph.nodes.get(id);
        if (node.kind().equals("process")) { producers.add(id); return true; }
        if (!node.kind().equals("transport")) return false;
        // Mark before traversal so unknown energy feedback cannot recurse indefinitely.
        memo.put(id, false);
        List<Link> inputs = graph.incoming.get(id).stream().filter(l -> l.resource().equals(resource)).toList();
        boolean result = !inputs.isEmpty() && inputs.stream().allMatch(l -> processedOrigin(graph.ports.get(l.from()).node(), resource, graph, memo, producers));
        memo.put(id, result); return result;
    }

    static JsonObject resource(Resource resource) { JsonObject row = new JsonObject(); row.addProperty("medium", resource.medium()); row.addProperty("resource", resource.id()); return row; }
    private static final class Report {
        final JsonObject json = new JsonObject();
        final JsonArray errors = new JsonArray(), requirements = new JsonArray(), order = new JsonArray(), supplies = new JsonArray();
        final JsonArray configure = new JsonArray(), start = new JsonArray(), recipes = new JsonArray();
        final JsonArray transfers = new JsonArray();
        final Map<String, Recipe> processRecipes = new LinkedHashMap<>();
        boolean pending, unboundResources;
        void error(String code, String detail) {
            JsonObject row = new JsonObject(); row.addProperty("code", code); row.addProperty("detail", detail); errors.add(row);
        }
        void require(String category, String subject, Supplier<Check> call) {
            require(category,subject,"before_supply",call);
        }
        void require(String category, String subject, String gate, Supplier<Check> call) {
            Check check;
            try { check = call.get(); if (check == null) check = Check.unknown("Native adapter returned no evidence"); }
            catch (RuntimeException failure) { check = Check.unknown("Native evidence unavailable: " + failure.getClass().getSimpleName()); }
            JsonObject row = requirement(category, subject, "prepare", check.status().name().toLowerCase(), check.detail());
            row.addProperty("gate",gate);
            row.addProperty("provenance", check.provenance()); requirements.add(row);
            if (check.status() == Status.UNSUPPORTED) error("unsupported_" + category, subject + ": " + check.detail());
            else if (check.status() != Status.VERIFIED) pending = true;
        }
        void postcondition(String category, String subject, String detail) { requirements.add(requirement(category, subject, "observe", "not_verified", detail)); }
        private static JsonObject requirement(String category, String subject, String phase, String status, String detail) {
            JsonObject row = new JsonObject(); row.addProperty("category", category); row.addProperty("subject", subject);
            row.addProperty("phase", phase); row.addProperty("status", status); row.addProperty("detail", detail); return row;
        }
        Compilation finish() {
            boolean valid = errors.isEmpty(), ready = valid && !pending;
            json.addProperty("schema", "maicraft.production_plan.v1"); json.addProperty("valid", valid); json.addProperty("ready", ready);
            json.addProperty("status", !valid ? "blocked" : ready ? "ready_for_operation" : "needs_evidence_or_preparation");
            json.addProperty("machine_production_verified", false); json.addProperty("resource_transfer_verified", false);
            json.addProperty("observation_reading_requires_ready",false);
            json.add("errors", errors); json.add("requirements", requirements); json.add("dependency_order", order);
            json.add("supplies", supplies); json.add("configure", configure); json.add("start", start); json.add("processes", recipes);
            json.add("transfers", transfers);
            JsonObject gates = new JsonObject(); for (String stage : List.of("supply","start","observe")) gates.addProperty(stage,ProductionStageReadiness.canEnter(json,stage)); json.add("stage_readiness",gates);
            return new Compilation(valid, ready, json);
        }
    }
}
