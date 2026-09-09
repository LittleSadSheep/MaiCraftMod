// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.layout;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.maiwithu.maicraft.core.integration.machine.MachineDesignReview;
import org.maiwithu.maicraft.core.integration.machine.MachinePlanningBudget;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutRouting.Bounds;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutRouting.Cell;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutRouting.Pos;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutRouting.Side;

/**
 * 把“有哪些机器、谁连谁”的设计展开成具体格子：排机器和维护空间、铺连接线、列配置与初始物品要求。
 * 这里只根据注册信息生成计划，不访问地形；场地能否施工由后面的勘察和安装流程确认。
 */
public final class SemanticMachineLayout {
    public static final int MAX_RADIUS = MachinePlanningBudget.current().maxRadius();
    public static final int MAX_TARGETS = MachinePlanningBudget.current().maxTargets();
    public interface Registry {
        boolean blockExists(String id);
        boolean itemExists(String id);
        boolean supportsState(String blockId, Map<String, String> properties);
    }
    public record Result(boolean buildable, JsonObject blueprint, JsonObject report) {}
    private record Instance(String name, String blockId, String role, Pos position, MachineLayoutCatalog.Profile profile,
                            MachineLayoutModules.Module module) {}
    private record Endpoint(String blockId, Pos position, List<Side> sides) {}
    private static final List<Side> RESOURCE_SIDES = List.of(Side.EAST, Side.WEST, Side.SOUTH, Side.UP, Side.DOWN);
    private SemanticMachineLayout() {}

    public static Result compile(JsonObject design, int radius, Registry registry) {
        MachineLayoutRouting.checkpoint();
        if (radius < 1 || radius > MAX_RADIUS) throw new IllegalArgumentException("layout radius must be 1.." + MAX_RADIUS);
        JsonObject review = MachineDesignReview.review(design, registry::blockExists, registry::itemExists);
        MachineLayoutWork work = new MachineLayoutWork(registry);
        work.report.add("design_review", review);
        if (!review.getAsJsonObject("validation").get("valid").getAsBoolean()) {
            work.fail("invalid_semantic_design", "Correct the semantic graph validation errors.");
            return work.finish();
        }
        work.expectedOutput = design.has("expected_output") ? design.get("expected_output").getAsString() : null;
        JsonObject constraints = design.has("constraints") ? design.getAsJsonObject("constraints") : new JsonObject();
        int width = bounded(constraints, "max_width", 2 * radius + 1, 2 * radius + 1);
        int depth = bounded(constraints, "max_depth", 2 * radius + 1, 2 * radius + 1);
        int height = bounded(constraints, "max_height", 2 * radius + 1, 2 * radius + 1);
        int below = Math.min(radius, Math.min(2, (height - 1) / 2));
        Bounds bounds = new Bounds(-width / 2, (width - 1) / 2, -below,
                Math.min(radius, height - below - 1), -depth / 2, (depth - 1) / 2);
        work.report.addProperty("compiler", "semantic_machine_layout_v1");
        work.report.addProperty("placement_policy", "preserve_existing; verify occupied and clearance cells against live world before mutation");
        work.report.addProperty("style", design.has("style") ? design.get("style").getAsString() : "service_grid");
        long total = 0; int span = 3;
        Map<String, MachineLayoutModules.Module> templates = new LinkedHashMap<>();
        for (JsonElement e : design.getAsJsonArray("components")) {
            MachineLayoutRouting.checkpoint();
            JsonObject c = e.getAsJsonObject(); total = Math.addExact(total, c.get("count").getAsLong());
            try {
                MachineLayoutModules.Module module = MachineLayoutModules.resolve(c, registry);
                if (module != null) { templates.put(c.get("name").getAsString(), module); span = Math.max(span, module.span()); }
            } catch (IllegalArgumentException unsupported) { work.fail("module_adapter_unavailable", unsupported.getMessage()); }
        }
        if (!work.errors.isEmpty()) return work.finish();
        if (total > MAX_TARGETS) { work.fail("target_budget_exceeded", "Logical expansion exceeds the configured physical target budget."); return work.finish(); }
        // 目前所有设备共用最大模块的占地间隔，额外留三格；小设备也按这个最大间隔排，且整体至少要求三格高。
        int spacing = span + 3;
        int columns = Math.min((width - span) / spacing + 1, Math.max(1, (int) Math.ceil(Math.sqrt(total))));
        int rows = columns < 1 ? Integer.MAX_VALUE : Math.toIntExact((total + columns - 1) / columns);
        if (total > MAX_TARGETS || width < span || height < 3 || columns < 1 || (long) (rows - 1) * spacing + span > depth) {
            work.fail("layout_footprint_exceeded", "The requested equipment and maintenance bays do not fit the surveyed dimensions; expand the site or reduce equipment.");
            return work.finish();
        }
        int firstX = -((columns - 1) * spacing) / 2, firstZ = -((rows - 1) * spacing) / 2;
        Map<String, List<Instance>> groups = new LinkedHashMap<>();
        int index = 0;
        for (JsonElement e : design.getAsJsonArray("components")) {
            MachineLayoutRouting.checkpoint();
            JsonObject component = e.getAsJsonObject();
            String name = component.get("name").getAsString(), id = component.get("block_id").getAsString();
            MachineLayoutCatalog.Profile profile = MachineLayoutCatalog.get(id);
            MachineLayoutModules.Module module = templates.get(name);
            if (profile == null && module == null) {
                work.fail("component_adapter_unavailable", name + " (" + id + ") needs a physical interface or multiblock template adapter.");
                continue;
            }
            List<Instance> instances = new ArrayList<>();
            int count = component.get("count").getAsInt();
            for (int i = 0; i < count; i++, index++) {
                MachineLayoutRouting.checkpoint();
                Pos at = new Pos(firstX + index % columns * spacing, 0, firstZ + index / columns * spacing);
                // 多台设备用“名称[编号]”命名；当前未避免用户本就起了同样名字，后续按名字存表时可能相互覆盖。
                String instanceName = count == 1 ? name : name + "[" + i + "]";
                Instance instance = new Instance(instanceName, id, component.get("role").getAsString(), at, profile, module);
                instances.add(instance);
                if (module == null) {
                    work.add(new Cell(at, id, profile.state(), false, "component:" + instanceName));
                    work.clearance.add(at.step(Side.NORTH)); work.clearance.add(at.step(Side.NORTH).step(Side.UP));
                } else installModule(work, instance, bounds);
                JsonObject row = new JsonObject();
                row.addProperty("name", instanceName); row.addProperty("block_id", id); row.addProperty("role", instance.role);
                row.add("offset", position(module == null ? at : at.plus(module.coreOffset())));
                row.addProperty("interface_evidence", module == null ? profile.evidence() : "audited process module " + module.id());
                work.components.add(row);
                if (module == null && id.startsWith("create:mechanical_")) work.pending("process_station", instanceName,
                        "A press/mixer requires its correctly separated basin/depot, recipe and drive coupling; this equipment placement alone does not define a process station.");
                if (id.startsWith("ae2:")) work.pending("ae_device_configuration", instanceName,
                        "Verify network power, channel allocation, storage cells and any interface stocking or processing patterns through native observations.");
            }
            groups.put(name, instances);
        }
        if (!work.errors.isEmpty()) return work.finish();
        // 先把 AE2 设备按声明的网络分组，并为有控制器的网络分配不同出口，再处理各条连接。
        Map<String,MachineLayoutAeNetworks.Leaf> aeLeaves = new LinkedHashMap<>();
        for (List<Instance> group : groups.values()) for (Instance instance : group) {
            Endpoint port = endpoint(instance, "ae_network", false);
            if (port != null) aeLeaves.put(instance.name, new MachineLayoutAeNetworks.Leaf(instance.name, instance.blockId, port.position, port.sides));
        }
        MachineLayoutAeNetworks.prepare(design, work, bounds, aeLeaves);
        if (!work.errors.isEmpty()) return work.finish();
        int edgeIndex = 0;
        for (JsonElement e : design.getAsJsonArray("connections")) {
            MachineLayoutRouting.checkpoint();
            JsonObject edge = e.getAsJsonObject();
            List<Instance> sources = groups.get(edge.get("from").getAsString());
            List<Instance> destinations = groups.get(edge.get("to").getAsString());
            if (sources.size() != destinations.size() && sources.size() != 1 && destinations.size() != 1) {
                work.fail("ambiguous_component_pairing", "Connection " + edgeIndex + " has unequal groups; specify separate named process stages or equal counts.");
                continue;
            }
            // 两组数量相同就逐一配对；一端只有一台时连接另一端全部；两端都多台且数量不同就拒绝猜测。
            int pairs = Math.max(sources.size(), destinations.size());
            for (int i = 0; i < pairs; i++) {
                MachineLayoutRouting.checkpoint();
                connect(work, sources.get(sources.size() == 1 ? 0 : i), destinations.get(destinations.size() == 1 ? 0 : i),
                        edge, "connection:" + edgeIndex + ":" + i, bounds);
            }
            edgeIndex++;
        }
        work.pending("native_placement_preflight", "construction", "Resolve each registered block/part item, support and reachable placement gesture; verify the entire occupied and maintenance volume before mutation.");
        work.pending("recipe_chain_and_throughput", "production", design.has("expected_output")
                ? "Verify installed recipes producing " + design.get("expected_output").getAsString() + ", measured inputs/outputs, catalysts, byproducts and sustained throughput."
                : "Select the intended output and verify recipes, consumed inputs, byproducts and sustained throughput.");
        work.pending("power_and_fault_test", "production", "Verify supplied energy/rotation, capacity, stress, buffers and safe behavior under missing input or blocked output before declaring operation complete.");
        return work.finish();
    }

    // 先确认两端都支持这种介质，再选具体管线；Create 加工输出若指定了成品，还要插入可配置过滤的分拣机。
    private static void connect(MachineLayoutWork work, Instance source, Instance destination, JsonObject edge, String owner, Bounds bounds) {
        String medium = edge.get("medium").getAsString(), transport = MachineLayoutCatalog.transport(medium);
        Endpoint from = endpoint(source, medium, true), to = endpoint(destination, medium, false);
        if (transport == null || from == null || to == null) {
            work.fail("connection_adapter_unavailable", owner + ": " + source.name + " -> " + destination.name + " via " + medium
                    + " lacks verified endpoint capabilities; arbitrary medium labels do not establish compatibility.");
            return;
        }
        if (medium.equals("ae_network") && work.aeTopologyNetworks.contains(work.aeNetworks.get(source.name))) {
            recordAeConnection(work, source, destination, from, to, edge, owner);
            return;
        }
        String routeOwner = medium.equals("ae_network") ? "ae_network:" + work.aeNetworks.get(source.name) : owner;
        boolean processOutput = source.module != null && source.module.id().startsWith("create:") && Set.of("items", "fluids").contains(medium);
        String outputItem = edge.has("item_id") ? edge.get("item_id").getAsString() : edge.has("resource") ? edge.get("resource").getAsString() : work.expectedOutput;
        MachineLayoutItemOutputs.Output filtered = processOutput && medium.equals("items") && outputItem != null
                ? MachineLayoutItemOutputs.route(work,from.position,from.sides,to.position,to.sides,owner,bounds,outputItem) : null;
        MachineLayoutRouting.Route route = filtered != null ? filtered.route() : processOutput && medium.equals("items") && outputItem != null ? null
                : MachineLayoutRouting.route(from.position,to.position,from.sides,to.sides,medium,transport,routeOwner,work.cells,work.clearance,bounds);
        if (route == null) {
            work.fail("transport_route_unresolved", owner + " has no isolated, compatible route within the site/search budget; expand the site or supply a dedicated bus/layout adapter.");
            return;
        }
        if (filtered != null) work.add(filtered.sorter());
        for (Cell cell : route.cells()) {
            Cell existing = work.cells.get(cell.position());
            if (medium.equals("ae_network") && existing != null && existing.id().equals(cell.id()) && existing.owner().equals(routeOwner)) continue;
            work.add(cell);
        }
        JsonObject row = new JsonObject();
        row.addProperty("id", owner); row.addProperty("from", source.name); row.addProperty("to", destination.name);
        row.add("source_offset", position(from.position)); row.add("destination_offset", position(to.position));
        row.addProperty("medium", medium); row.addProperty("purpose", edge.get("purpose").getAsString());
        row.addProperty("transport_id", transport); row.addProperty("source_side", route.sourceSide().label());
        row.addProperty("destination_side", route.destinationSide().label()); row.addProperty("physical_path_compiled", true);
        row.addProperty("resource_transfer_verified", false);
        JsonArray path = new JsonArray(); if (filtered != null) { path.add(position(filtered.sorter().position())); row.add("filter_offset",position(filtered.sorter().position())); row.addProperty("item_id",outputItem); }
        route.cells().forEach(cell -> path.add(position(cell.position()))); row.add("route", path);
        work.routes.add(row);
        String exactPorts = source.name + "." + route.sourceSide().label() + " -> " + destination.name + "." + route.destinationSide().label();
        if (medium.equals("kinetic")) {
            work.pending("kinetic_commissioning", owner, exactPorts + ": confirm nonzero destination RPM, required direction/speed and sufficient stress capacity; shaft relays are not generators.");
        } else if (medium.equals("ae_network")) {
            work.pending("ae_network_commissioning", owner, exactPorts + ": install center cable parts, observe a powered network and channels; cable connectivity alone proves no crafting pattern.");
        } else {
            configureEndpoint(work, from, route.sourceSide(), medium, "output", owner);
            configureEndpoint(work, to, route.destinationSide(), medium, "input", owner);
            Side towardSource = switch (route.sourceSide()) {
                case EAST -> Side.WEST; case WEST -> Side.EAST; case UP -> Side.DOWN;
                case DOWN -> Side.UP; case SOUTH -> Side.NORTH; case NORTH -> Side.SOUTH;
            };
            // 普通来源使用管道拉取；已有分拣机则由它推出；加工设备未明确成品时先关闭抽取，避免把原料也抽走。
            work.configure(route.cells().get(0).position(), towardSource, medium, filtered != null ? "normal" : processOutput ? "none" : "pull");
            if (processOutput && filtered == null) work.pending("process_output_filter_required",owner,"The process receiver also exposes raw inputs. Its output connection stays disabled until an exact finished resource and supported native filter are verified.");
            work.pending("endpoint_side_configuration", owner, exactPorts + ": use native observed controls to set source output/ejection or transporter pull, destination input, exact resource filters and tank/slot selection for " + medium + ".");
            work.pending("transport_commissioning", owner, "Observe a bounded transfer of the intended resource across " + exactPorts + "; defaults and cross-mod capability availability must be checked in the running installation.");
        }
    }

    // 只对当前明确支持的 Mekanism 接口生成自动配置；电解分离器的化学输出要先确认该用哪一个储槽。
    private static void configureEndpoint(MachineLayoutWork work, Endpoint instance, Side side, String medium, String mode, String owner) {
        if (!instance.blockId.startsWith("mekanism:") || instance.blockId.endsWith("_fluid_tank") || instance.blockId.equals("mekanism:induction_port")) return;
        if (instance.blockId.equals("mekanism:electrolytic_separator") && medium.equals("chemicals") && mode.equals("output")) {
            work.pending("chemical_output_tank_selection", owner,
                    "Resolve the recipe's output chemical against the separator's two live tanks before selecting output_1 or output_2 at " + side.label() + "; a generic purpose string cannot prove chemical identity.");
            return;
        }
        work.configure(instance.position, side, medium, mode);
    }

    private static void recordAeConnection(MachineLayoutWork work, Instance source, Instance target, Endpoint from, Endpoint to, JsonObject edge, String owner) {
        List<Pos> path = MachineLayoutAeNetworks.connectionPath(work, from.position, to.position, work.aeNetworks.get(source.name));
        if (path == null) { work.fail("ae_network_path_missing", "Compiled controller branches do not physically connect " + source.name + " and " + target.name); return; }
        JsonObject row = new JsonObject(); row.addProperty("id", owner); row.addProperty("from", source.name); row.addProperty("to", target.name);
        row.addProperty("medium", "ae_network"); row.addProperty("purpose", edge.get("purpose").getAsString());
        row.add("source_offset", position(from.position)); row.add("destination_offset", position(to.position));
        row.addProperty("physical_path_compiled", true); row.addProperty("resource_transfer_verified", false); row.addProperty("transport_id", MachineLayoutAeNetworks.DENSE);
        row.addProperty("topology", "capacity_partitioned_controller_faces");
        JsonArray cells = new JsonArray(); path.forEach(p -> cells.add(position(p))); row.add("route", cells); work.routes.add(row);
    }

    private static Endpoint endpoint(Instance instance, String medium, boolean output) {
        if (instance.module != null) {
            MachineLayoutModules.Port p = instance.module.port(medium, output);
            return p == null ? null : new Endpoint(p.blockId(), instance.position.plus(p.offset()), p.sides());
        }
        if (!instance.profile.accepts(medium, output)) return null;
        return new Endpoint(instance.blockId, instance.position, medium.equals("kinetic") ? instance.profile.shafts() : RESOURCE_SIDES);
    }

    // 把模块内部相对位置统一平移到场地，方块、维护空间、配置、初始物品和最后封口的位置一起移动。
    private static void installModule(MachineLayoutWork work, Instance instance, Bounds bounds) {
        var module = instance.module;
        for (Cell c : module.cells()) {
            MachineLayoutRouting.checkpoint();
            Pos at = instance.position.plus(c.position());
            if (!bounds.contains(at)) work.fail("module_outside_site", instance.name + " extends outside the surveyed dimensions");
            work.add(new Cell(at, c.id(), c.properties(), c.part(), "component:" + instance.name));
        }
        for (Pos clear : module.clearance()) {
            Pos at = instance.position.plus(clear);
            if (!bounds.contains(at)) work.fail("module_clearance_outside_site", instance.name + " working clearance exceeds the site");
            work.clearance.add(at);
        }
        for (JsonElement e : module.configurations()) {
            JsonObject c = e.getAsJsonObject().deepCopy();
            c.add("offset", position(instance.position.plus(MachineLayoutModules.from(c.getAsJsonArray("offset"))))); work.configurations.add(c);
        }
        for (JsonElement e : module.initialContents()) {
            JsonObject contents = e.getAsJsonObject().deepCopy();
            contents.add("offset", position(instance.position.plus(MachineLayoutModules.from(contents.getAsJsonArray("offset"))))); work.initialContents.add(contents);
        }
        for (JsonElement e : module.seals()) {
            JsonObject seal = e.getAsJsonObject().deepCopy(); JsonArray openings = new JsonArray();
            seal.getAsJsonArray("offsets").forEach(p -> openings.add(position(instance.position.plus(MachineLayoutModules.from(p.getAsJsonArray())))));
            seal.add("offsets", openings); seal.add("outside_offset", position(instance.position.plus(MachineLayoutModules.from(seal.getAsJsonArray("outside_offset"))))); work.seals.add(seal);
        }
        JsonObject report = module.commissioning().deepCopy();
        for (String key : List.of("min_offset", "max_offset")) if (report.has(key)) report.add(key, position(instance.position.plus(MachineLayoutModules.from(report.getAsJsonArray(key)))));
        report.addProperty("module", module.id()); report.addProperty("component", instance.name); work.modules.add(report);
        work.pending("module_commissioning", instance.name, report.get("requirements").getAsString());
    }

    private static int bounded(JsonObject constraints, String key, int fallback, int maximum) {
        return constraints.has(key) ? Math.min(maximum, constraints.get(key).getAsInt()) : fallback;
    }
    static JsonArray position(Pos at) {
        JsonArray array = new JsonArray(); array.add(at.x()); array.add(at.y()); array.add(at.z()); return array;
    }

}
