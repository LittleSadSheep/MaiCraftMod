// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Reviews an LLM-authored logical machine graph without reading or changing the world.
 * A graph accepted here is an explicit hypothesis, never a functioning machine or a build plan.
 * Minecraft registry access is injected so structural validation remains independently testable.
 */
public final class MachineDesignReview {
    // Planning budgets bound JSON traversal and physical expansion, independently of survey radius.
    public static final int MAX_COMPONENTS = MachinePlanningBudget.current().maxComponents();
    public static final int MAX_CONNECTIONS = MachinePlanningBudget.current().maxConnections();
    public static final int MAX_COMPONENT_COUNT = MachinePlanningBudget.current().maxTargets();
    public static final int MAX_TOTAL_BLOCKS = MachinePlanningBudget.current().maxTargets();
    private static final int MAX_ERRORS = 64;
    private static final Set<String> DESIGN_FIELDS = Set.of("components", "connections", "expected_output", "style", "constraints");
    private static final Set<String> CONSTRAINT_FIELDS = Set.of(
            "max_width", "max_depth", "max_height", "terrain_fit",
            "maintenance_access", "preserve_existing", "throughput");
    private static final Set<String> COMPONENT_FIELDS = Set.of("name", "block_id", "count", "role", "module", "module_tier", "module_options");
    private static final Set<String> CONNECTION_FIELDS = Set.of("from", "to", "medium", "purpose", "item_id", "resource");
    private static final Set<String> MEDIA = Set.of("kinetic", "items", "fluids", "energy", "chemicals", "ae_network", "redstone", "heat");
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    private MachineDesignReview() {}

    private record Component(String name, String blockId, int count, String role, JsonObject module) {}
    private record Connection(String from, String to, String medium, String purpose, JsonObject resource) {}
    private record EdgeKey(String from, String to, String medium) {}

    /**
     * Graph fields are strict and intentionally semantic. Exact cells, block states and placement
     * instructions belong to a Mod-side compiler and are never accepted from the model.
     */
    public static JsonObject review(JsonObject design, Predicate<String> blockExists, Predicate<String> itemExists) {
        Objects.requireNonNull(blockExists, "blockExists");
        Objects.requireNonNull(itemExists, "itemExists");
        JsonArray errors = new JsonArray();
        if (design == null) {
            error(errors, "$", "object_required", "Design must be an object.");
            return invalid(errors);
        }
        checkFields(design, DESIGN_FIELDS, "$", errors);
        JsonArray componentsJson = array(design, "components", 1, MAX_COMPONENTS, errors);
        JsonArray connectionsJson = array(design, "connections", 0, MAX_CONNECTIONS, errors);
        String expectedOutput = null;
        if (design.has("expected_output")) {
            expectedOutput = identifier(design, "expected_output", "$", errors);
            if (expectedOutput != null) checkRegistry(itemExists, expectedOutput, "$.expected_output", "item", errors);
        }
        String style = design.has("style") ? string(design, "style", "$", 256, errors) : null;
        JsonObject constraints = design.has("constraints")
                ? constraints(design.get("constraints"), errors) : null;
        // Do not traverse oversized lists. No partial material estimate or graph is returned on failure.
        if (componentsJson == null || connectionsJson == null) return invalid(errors);
        List<Component> components = new ArrayList<>();
        Map<String, Component> byName = new LinkedHashMap<>();
        long total = 0;
        for (int i = 0; i < componentsJson.size(); i++) {
            String path = "$.components[" + i + "]";
            JsonObject component = object(componentsJson.get(i), path, errors);
            if (component == null) continue;
            checkFields(component, COMPONENT_FIELDS, path, errors);
            String name = string(component, "name", path, 64, errors);
            String blockId = identifier(component, "block_id", path, errors);
            String role = string(component, "role", path, 160, errors);
            int count = count(component, path, errors);
            JsonObject module = module(component, path, errors);
            if (blockId != null) {
                checkRegistry(blockExists, blockId, path + ".block_id", "block", errors);
                if (Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air").contains(blockId)) {
                    error(errors, path + ".block_id", "air_component", "Air is not a machine component.");
                }
            }
            if (count > 0) total = Math.addExact(total, count);
            if (name == null || blockId == null || role == null || count < 1) continue;
            Component entry = new Component(name, blockId, count, role, module);
            if (byName.putIfAbsent(name, entry) != null) {
                error(errors, path + ".name", "duplicate_component", "Component names must be unique.");
            } else {
                components.add(entry);
            }
        }
        if (total > MAX_TOTAL_BLOCKS) error(errors, "$.components", "total_count_exceeded", "Total component count must be at most " + MAX_TOTAL_BLOCKS + ".");
        List<Connection> connections = new ArrayList<>();
        Set<EdgeKey> edges = new HashSet<>();
        for (int i = 0; i < connectionsJson.size(); i++) {
            String path = "$.connections[" + i + "]";
            JsonObject connection = object(connectionsJson.get(i), path, errors);
            if (connection == null) continue;
            checkFields(connection, CONNECTION_FIELDS, path, errors);
            String from = string(connection, "from", path, 64, errors);
            String to = string(connection, "to", path, 64, errors);
            String medium = string(connection, "medium", path, 256, errors);
            String purpose = string(connection, "purpose", path, 256, errors);
            JsonObject resource = new JsonObject();
            for (String key : List.of("item_id", "resource")) if (connection.has(key)) {
                String id = identifier(connection, key, path, errors);
                if (id != null) { resource.addProperty(key,id); if ("items".equals(medium)) checkRegistry(itemExists,id,path+'.'+key,"item",errors); }
            }
            if (connection.has("item_id") && !"items".equals(medium)) error(errors,path+".item_id","invalid_resource_medium","item_id applies to item connections only.");
            if (resource.has("item_id") && resource.has("resource") && !resource.get("item_id").equals(resource.get("resource"))) error(errors,path,"conflicting_resource_identity","item_id and resource must name the same intended item.");
            if (medium != null && !MEDIA.contains(medium) && !ID.matcher(medium).matches()) {
                error(errors, path + ".medium", "unsupported_medium", "Use kinetic, items, fluids, energy, chemicals, ae_network, redstone, heat or a namespaced custom resource such as addon:mana.");
            }
            if (from != null && !byName.containsKey(from)) error(errors, path + ".from", "unknown_component", "Source must reference a declared component name.");
            if (to != null && !byName.containsKey(to)) error(errors, path + ".to", "unknown_component", "Destination must reference a declared component name.");
            if (from != null && from.equals(to)) error(errors, path, "self_connection", "Declare separate components for the two connection endpoints.");
            if (from == null || to == null || medium == null || purpose == null) continue;
            if (!edges.add(new EdgeKey(from, to, medium))) {
                error(errors, path, "duplicate_connection", "Each directed endpoint pair may declare a medium only once.");
            }
            connections.add(new Connection(from, to, medium, purpose, resource));
        }
        if (!errors.isEmpty()) return invalid(errors);
        return report(components, connections, expectedOutput, style, constraints, (int) total);
    }

    private static JsonObject report(
            List<Component> components,
            List<Connection> connections,
            String expectedOutput,
            String style,
            JsonObject constraints,
            int total) {
        JsonObject result = base(true, new JsonArray());
        JsonObject graph = new JsonObject();
        JsonArray componentRows = new JsonArray();
        JsonArray connectionRows = new JsonArray();
        Map<String, Integer> required = new LinkedHashMap<>();
        Map<String, Component> byName = new LinkedHashMap<>();
        Set<String> namespaces = new LinkedHashSet<>();
        Set<String> media = new LinkedHashSet<>();
        Set<String> connected = new HashSet<>();
        for (Component component : components) {
            JsonObject row = new JsonObject();
            row.addProperty("name", component.name());
            row.addProperty("block_id", component.blockId());
            row.addProperty("count", component.count());
            row.addProperty("role", component.role());
            component.module().entrySet().forEach(e -> row.add(e.getKey(), e.getValue().deepCopy()));
            componentRows.add(row);
            required.merge(component.blockId(), component.count(), Math::addExact);
            byName.put(component.name(), component);
            namespaces.add(namespace(component.blockId()));
        }
        JsonArray obligations = new JsonArray();
        JsonArray hints = new JsonArray();
        Map<String, JsonArray> crossModConnections = new LinkedHashMap<>();
        JsonArray warnings = new JsonArray();
        obligation(obligations, "physical_layout", "design", "Resolve actual placement, facing, clearance, loaded chunks and multiblock formation from world evidence.");
        obligation(obligations, "material_acquisition", "design", "Resolve block-to-item mappings, multipart items, recipes, ingredient quantities and available inventory; block counts alone are not a crafting bill.");
        obligation(obligations, "operational_test", "design", "Observe a bounded test with measured inputs, outputs and fault conditions before claiming that this machine works.");
        if (!connections.isEmpty()) {
            obligation(obligations, "interface_compatibility", "each_connection", "Verify both endpoint capabilities, exact transported resource, intermediary transport and any necessary conversion for each connection's declared medium.");
            obligation(obligations, "direction_and_control", "each_connection", "Verify actual endpoint sides, input/output modes, filters, rates and control conditions for every connection; from/to only expresses the proposed direction.");
        }
        for (int i = 0; i < connections.size(); i++) {
            Connection connection = connections.get(i);
            JsonObject row = new JsonObject();
            row.addProperty("from", connection.from());
            row.addProperty("to", connection.to());
            row.addProperty("medium", connection.medium());
            row.addProperty("purpose", connection.purpose());
            connection.resource().entrySet().forEach(e -> row.add(e.getKey(),e.getValue().deepCopy()));
            row.addProperty("evidence", "design_claim_only");
            connectionRows.add(row);
            media.add(connection.medium());
            connected.add(connection.from());
            connected.add(connection.to());
            Component from = byName.get(connection.from());
            Component to = byName.get(connection.to());
            if (!namespace(from.blockId()).equals(namespace(to.blockId()))) {
                crossModConnections.computeIfAbsent(connection.medium(), ignored -> new JsonArray()).add(i);
            }
        }
        crossModConnections.forEach((medium, indices) -> {
            JsonObject hint = new JsonObject();
            hint.add("connection_indices", indices);
            hint.addProperty("medium", medium);
            hint.addProperty("message", conversionHint(medium));
            hints.add(hint);
        });
        for (Component component : components) {
            if (!connected.contains(component.name())) warnings.add("Component '" + component.name() + "' has no declared connection; justify independent operation or add its missing interfaces.");
            if (component.count() > 1) warnings.add("Component '" + component.name() + "' groups " + component.count() + " blocks; group connections do not specify each instance's topology.");
        }
        if (media.contains("kinetic") || namespaces.contains("create")) {
            obligation(obligations, "kinetic_stress_and_speed", "design", "Verify rotational connectivity, axes, direction, speed, source capacity and total stress under the installed Create configuration.");
        }
        if (media.contains("energy") || media.contains("ae_network") || namespaces.contains("ae2") || usesMekanism(namespaces)) {
            obligation(obligations, "power_budget", "design", "Verify energy types, any conversion, supply/transfer limits, storage and sustained demand for every powered device.");
        }
        if (media.contains("ae_network") || namespaces.contains("ae2")) {
            obligation(obligations, "ae_network_and_channels", "design", "Verify network formation, powered state, channels under the installed configuration, storage access, security and device connectivity.");
            obligation(obligations, "ae_parts_and_patterns", "design", "Resolve cable multipart contents, interface/bus configuration and processing patterns against observed machine inputs and outputs.");
        }
        if (media.contains("fluids") || media.contains("chemicals") || usesMekanism(namespaces)) {
            obligation(obligations, "resource_types_and_side_configuration", "design", "Verify exact fluid/chemical types, container compatibility, side configuration, transport and buffer capacity using runtime evidence.");
        }
        if (media.contains("heat")) {
            obligation(obligations, "heat_transfer_and_temperature", "design", "Verify heat sources and sinks, temperature requirements, thermal capacity, transfer limits and losses against actual endpoint behavior.");
        }
        if (media.stream().anyMatch(medium -> !MEDIA.contains(medium))) {
            obligation(obligations, "custom_medium_semantics", "each_custom_medium", "Verify every namespaced custom medium's installed resource identity, units, production/consumption rules and endpoint capabilities; accepting its identifier does not verify that it exists or can be transported.");
        }
        obligation(obligations, "recipes_and_throughput", "design", expectedOutput == null
                ? "Choose an intended output and verify installed recipes, inputs, catalysts, byproducts, operating conditions and throughput."
                : "Verify an installed recipe chain that produces " + expectedOutput + ", including inputs, catalysts, byproducts, operating conditions and throughput.");
        if (media.contains("redstone")) obligation(obligations, "control_and_failure_modes", "design", "Verify signal polarity, thresholds, start/stop behavior and handling of full output buffers or missing input.");
        JsonArray materials = new JsonArray();
        required.forEach((blockId, count) -> {
            JsonObject material = new JsonObject();
            material.addProperty("block_id", blockId);
            material.addProperty("count", count);
            materials.add(material);
        });
        graph.add("components", componentRows);
        graph.add("connections", connectionRows);
        if (expectedOutput != null) graph.addProperty("expected_output", expectedOutput);
        if (style != null) graph.addProperty("style", style);
        if (constraints != null) graph.add("constraints", constraints.deepCopy());
        result.add("graph", graph);
        result.addProperty("component_count", components.size());
        result.addProperty("total_block_count", total);
        result.add("material_requirements", materials);
        result.addProperty("material_requirements_source", "logical_components; a Mod-side layout compiler must derive exact physical requirements separately");
        result.addProperty("material_scope", "Declared block quantities only; excludes crafting ingredients, multipart contents, upgrades and additional transport or infrastructure.");
        result.add("obligations", obligations);
        JsonArray unsupported = new JsonArray();
        for (JsonElement entry : obligations) unsupported.add(entry.getAsJsonObject().get("code").getAsString());
        result.add("unsupported_obligations", unsupported);
        result.add("integration_hints", hints);
        result.add("warnings", warnings);
        return result;
    }

    private static String conversionHint(String medium) {
        return switch (medium) {
            case "kinetic" -> "Cross-mod rotation requires compatible kinetic interfaces or an installed conversion device; a shared energy label is not evidence of compatibility.";
            case "energy" -> "Check the installed endpoints' energy capabilities and units; do not assume rotational power, electrical energy and AE network power are interchangeable.";
            case "chemicals" -> "Verify the exact chemical capability and resource type, and an installed bridge if required; chemical storage is not automatically fluid storage.";
            case "ae_network" -> "A foreign machine is not automatically an AE network device; verify a supported bus/interface/adapter and its separate resource-side connection.";
            case "items", "fluids" -> "Verify the installed shared transfer capability and resource identity, then specify compatible transports, buffers, endpoint sides and extraction/insertion controls.";
            case "redstone" -> "Verify signal emission, receiving face, polarity and machine control mode at both ends.";
            case "heat" -> "Verify compatible thermal interfaces, temperature ranges, heat capacity and transfer losses; stored electrical energy or rotational power is not automatically a thermal connection.";
            default -> "A namespaced custom medium is a proposed resource model only. Verify its installed definition, endpoint capabilities, units and any actual transport or conversion adapter before claiming compatibility.";
        };
    }

    private static boolean usesMekanism(Set<String> namespaces) {
        return namespaces.contains("mekanism") || namespaces.contains("mekanismgenerators");
    }

    private static String namespace(String id) { return id.substring(0, id.indexOf(':')); }

    private static void obligation(JsonArray obligations, String code, String scope, String description) {
        JsonObject obligation = new JsonObject();
        obligation.addProperty("code", code);
        obligation.addProperty("scope", scope);
        obligation.addProperty("status", "unverified");
        obligation.addProperty("verification_supported_by_this_reviewer", false);
        obligation.addProperty("requirement", description);
        obligations.add(obligation);
    }

    private static JsonObject invalid(JsonArray errors) {
        JsonObject result = base(false, errors);
        result.add("obligations", new JsonArray());
        result.add("unsupported_obligations", new JsonArray());
        result.add("integration_hints", new JsonArray());
        result.add("warnings", new JsonArray());
        return result;
    }

    private static JsonObject base(boolean valid, JsonArray errors) {
        JsonObject result = new JsonObject();
        result.addProperty("schema_version", 2);
        JsonObject validation = new JsonObject();
        validation.addProperty("valid", valid);
        validation.addProperty("scope", "Strict logical graph schema and installed block/item identifiers only. Does not validate machine function, recipes, topology or transport compatibility.");
        validation.add("errors", errors);
        result.add("validation", validation);
        JsonObject readiness = new JsonObject();
        readiness.addProperty("design_validated", valid);
        readiness.addProperty("physical_layout_submitted", false);
        readiness.addProperty("physical_layout_verified", false);
        readiness.addProperty("interfaces_verified", false);
        readiness.addProperty("recipes_verified", false);
        readiness.addProperty("functioning_machine_verified", false);
        readiness.addProperty("executable", false);
        readiness.addProperty("next_step", valid ? "Resolve verification obligations, then use a matching Mod-side semantic layout compiler or dedicated high-level ability. Never supply per-block instructions." : "Correct the validation errors and submit the semantic design again.");
        result.add("readiness", readiness);
        result.addProperty("notice", "Design validation is not proof of a functioning machine and never authorizes or executes construction, configuration, dismantling or item transfer.");
        return result;
    }

    private static JsonObject module(JsonObject component, String path, JsonArray errors) {
        JsonObject result = new JsonObject();
        if (component.has("module")) {
            String id = identifier(component, "module", path, errors);
            if (id != null) result.addProperty("module", id);
        } else if (component.has("module_tier") || component.has("module_options")) {
            error(errors, path, "module_required", "module_tier and module_options require a named module.");
        }
        if (component.has("module_tier")) {
            String tier = string(component, "module_tier", path, 64, errors);
            if (tier != null) result.addProperty("module_tier", tier);
        }
        if (component.has("module_options")) {
            JsonObject options = object(component.get("module_options"), path + ".module_options", errors);
            if (options != null) {
                checkFields(options, Set.of("width", "height", "depth", "cell_count", "provider_count", "storage_tier", "storage_cells"), path + ".module_options", errors);
                for (var entry : options.entrySet()) {
                    if (entry.getKey().equals("storage_tier")) {
                        String tier = string(options, "storage_tier", path + ".module_options", 16, errors);
                        if (tier != null && !Set.of("1k", "4k", "16k", "64k", "256k").contains(tier)) error(errors, path + ".module_options.storage_tier", "invalid_storage_tier", "Use an installed AE item-cell tier: 1k, 4k, 16k, 64k or 256k.");
                        continue;
                    }
                    try {
                        JsonElement v = entry.getValue();
                        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
                        int n = v.getAsBigDecimal().intValueExact();
                        if (n < 1 || n > MAX_TOTAL_BLOCKS) throw new ArithmeticException();
                    } catch (ArithmeticException | NumberFormatException invalid) {
                        error(errors, path + ".module_options." + entry.getKey(), "invalid_module_option", "Module sizes and counts must be positive integers within the physical planning budget.");
                    }
                }
                result.add("module_options", options.deepCopy());
            }
        }
        return result;
    }

    private static JsonObject constraints(JsonElement value, JsonArray errors) {
        JsonObject input = object(value, "$.constraints", errors);
        if (input == null) return null;
        checkFields(input, CONSTRAINT_FIELDS, "$.constraints", errors);
        JsonObject normalized = new JsonObject();
        for (String key : List.of("max_width", "max_depth", "max_height")) {
            if (!input.has(key)) continue;
            JsonElement raw = input.get(key);
            try {
                if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
                int limit = raw.getAsBigDecimal().intValueExact();
                if (limit < 1 || limit > 2 * MachinePlanningBudget.current().maxRadius() + 1) throw new ArithmeticException();
                normalized.addProperty(key, limit);
            } catch (ArithmeticException | NumberFormatException invalid) {
                error(errors, "$.constraints." + key, "invalid_dimension", "Expected an integer from 1 to " + (2 * MachinePlanningBudget.current().maxRadius() + 1) + '.');
            }
        }
        for (String key : List.of("terrain_fit", "throughput")) {
            if (!input.has(key)) continue;
            String text = string(input, key, "$.constraints", 160, errors);
            if (text != null) normalized.addProperty(key, text);
        }
        for (String key : List.of("maintenance_access", "preserve_existing")) {
            if (!input.has(key)) continue;
            JsonElement raw = input.get(key);
            if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isBoolean()) {
                error(errors, "$.constraints." + key, "boolean_required", "Expected true or false.");
            } else {
                normalized.addProperty(key, raw.getAsBoolean());
            }
        }
        return normalized;
    }

    private static JsonArray array(JsonObject object, String key, int min, int max, JsonArray errors) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonArray()) {
            error(errors, "$." + key, "array_required", "Expected an array.");
            return null;
        }
        JsonArray array = value.getAsJsonArray();
        if (array.size() < min || array.size() > max) {
            error(errors, "$." + key, "array_size", "Array must contain " + min + " to " + max + " entries.");
            return null;
        }
        return array;
    }

    private static JsonObject object(JsonElement value, String path, JsonArray errors) {
        if (value == null || !value.isJsonObject()) {
            error(errors, path, "object_required", "Expected an object.");
            return null;
        }
        return value.getAsJsonObject();
    }

    private static void checkFields(JsonObject object, Set<String> allowed, String path, JsonArray errors) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) error(errors, path, "unknown_field", "Unsupported field: " + key.substring(0, Math.min(key.length(), 96)));
            if (errors.size() >= MAX_ERRORS) return;
        }
    }

    private static String string(JsonObject object, String key, String path, int maxLength, JsonArray errors) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            error(errors, path + "." + key, "string_required", "Expected a nonempty string.");
            return null;
        }
        String string = value.getAsString();
        if (string.isBlank() || string.length() > maxLength || !string.equals(string.strip()) || string.codePoints().anyMatch(Character::isISOControl)) {
            error(errors, path + "." + key, "invalid_string", "Use 1 to " + maxLength + " characters without leading/trailing whitespace or control characters.");
            return null;
        }
        return string;
    }

    private static String identifier(JsonObject object, String key, String path, JsonArray errors) {
        String value = string(object, key, path, 256, errors);
        if (value != null && !ID.matcher(value).matches()) {
            error(errors, path + "." + key, "invalid_identifier", "Expected a lowercase namespaced registry identifier such as minecraft:stone.");
            return null;
        }
        return value;
    }

    private static int count(JsonObject object, String path, JsonArray errors) {
        JsonElement value = object.get("count");
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            try {
                // Accept equivalent JSON number spellings, but never truncate fractions or overflow.
                int count = value.getAsBigDecimal().intValueExact();
                if (count >= 1 && count <= MAX_COMPONENT_COUNT) return count;
            } catch (ArithmeticException | NumberFormatException invalid) { /* Return a validation issue below. */ }
        }
        error(errors, path + ".count", "invalid_count", "Expected a JSON integer from 1 to " + MAX_COMPONENT_COUNT + ".");
        return -1;
    }

    private static void checkRegistry(Predicate<String> exists, String id, String path, String kind, JsonArray errors) {
        try {
            if (!exists.test(id)) error(errors, path, "unknown_" + kind, "No installed " + kind + " has this identifier.");
        } catch (RuntimeException exception) {
            // Registry unavailability is an explicit failure, never evidence that an identifier exists.
            error(errors, path, "registry_unavailable", "Could not verify the installed " + kind + " registry.");
        }
    }

    private static void error(JsonArray errors, String path, String code, String message) {
        if (errors.size() >= MAX_ERRORS) return;
        JsonObject error = new JsonObject();
        error.addProperty("path", path);
        error.addProperty("code", code);
        error.addProperty("message", message);
        errors.add(error);
    }
}
