// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.layout;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutRouting.Cell;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutRouting.Pos;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutRouting.Side;
import org.maiwithu.maicraft.core.integration.machine.MachinePlanningBudget;
import static org.maiwithu.maicraft.core.integration.machine.layout.SemanticMachineLayout.position;

/**
 * 保存一次布局计算的中间结果：设备格、电缆部件、要留空的位置、材料和待验收要求。任何错误都会让最终可执行蓝图为空。
 */
final class MachineLayoutWork {
    final SemanticMachineLayout.Registry registry;
    final Map<Pos, Cell> cells = new LinkedHashMap<>();
    final List<Cell> attachments = new ArrayList<>();
    final Set<Pos> clearance = new LinkedHashSet<>();
    final Map<String,String> aeNetworks = new LinkedHashMap<>();
    final Set<String> aeTopologyNetworks = new LinkedHashSet<>();
    final JsonObject report = new JsonObject();
    final JsonArray errors = new JsonArray(), obligations = new JsonArray(), components = new JsonArray(), routes = new JsonArray(), configurations = new JsonArray(), modules = new JsonArray();
    final JsonArray initialContents = new JsonArray();
    final JsonArray seals = new JsonArray();
    final JsonArray filters = new JsonArray();
    String expectedOutput;
    final Set<String> failures = new LinkedHashSet<>();
    MachineLayoutWork(SemanticMachineLayout.Registry registry) {
        this.registry = registry; var budget = MachinePlanningBudget.current(); JsonObject limits = new JsonObject();
        limits.addProperty("max_targets", budget.maxTargets()); limits.addProperty("max_radius", budget.maxRadius()); limits.addProperty("search_visited_budget", budget.searchVisitedBudget());
        JsonArray diagnostics = new JsonArray(); budget.diagnostics().forEach(d -> { JsonObject row = new JsonObject(); row.addProperty("code",d.code()); row.addProperty("message",d.message()); diagnostics.add(row); });
        limits.add("diagnostics",diagnostics); report.add("planning_budget",limits);
    }
    void fail(String code, String detail) {
        if (errors.size() >= 64 || !failures.add(code + ":" + detail)) return;
        JsonObject row = new JsonObject(); row.addProperty("code", code); row.addProperty("detail", detail); errors.add(row);
    }
    void configure(Pos at, Side side, String medium, String mode) {
        JsonObject row = new JsonObject(); row.add("offset", position(at)); row.addProperty("face", side.label());
        row.addProperty("medium", medium); row.addProperty("mode", mode); configurations.add(row);
    }
    void pending(String code, String scope, String detail) {
        JsonObject row = new JsonObject(); row.addProperty("code", code); row.addProperty("scope", scope);
        row.addProperty("status", "pending"); row.addProperty("detail", detail); obligations.add(row);
    }
    // 检查预算、安装物品和重叠；AE2 中央电缆与不同面的部件可以共格，同一槽重复占用则报错。
    void add(Cell cell) {
        if (cells.size() + attachments.size() >= SemanticMachineLayout.MAX_TARGETS) {
            fail("target_budget_exceeded", "Physical layout exceeds the configured " + SemanticMachineLayout.MAX_TARGETS + "-target construction budget."); return;
        }
        if (cell.isPart() ? !registry.itemExists(cell.id()) : !registry.blockExists(cell.id()) || !registry.supportsState(cell.id(), cell.properties())) {
            fail("installed_adapter_unavailable", "Installed registry does not support " + cell.id() + " " + cell.properties()); return;
        }
        Cell prior = cells.get(cell.position());
        if (prior != null) {
            if (!prior.isPart() || !cell.isPart() || prior.part().equals(cell.part()) || attachments.stream().anyMatch(a -> a.position().equals(cell.position()) && a.part().equals(cell.part()))) {
                fail("overlapping_layout_cells", "Compiler produced an overlapping target at " + cell.position()); return;
            }
            attachments.add(cell);
        } else cells.put(cell.position(), cell);
    }
    // 最后核对维护空间未被占用；即使布局失败仍返回错误和材料线索，但不输出半份可施工格子。
    SemanticMachineLayout.Result finish() {
        for (Pos space : clearance) if (cells.containsKey(space)) fail("occupied_maintenance_space", "A generated component blocks reserved working space at " + space);
        boolean buildable = errors.isEmpty() && !cells.isEmpty();
        JsonObject blueprint = new JsonObject(); JsonArray blocks = new JsonArray(); Map<String, Integer> materials = new LinkedHashMap<>();
        List<Cell> all = new ArrayList<>(cells.values()); all.addAll(attachments);
        for (Cell cell : all) {
            MachineLayoutRouting.checkpoint();
            JsonObject row = new JsonObject(); row.add("offset", position(cell.position()));
            row.addProperty(cell.isPart() ? "item_id" : "block_id", cell.id());
            if (cell.isPart()) row.addProperty("part", cell.part());
            else { JsonObject properties = new JsonObject(); cell.properties().forEach(properties::addProperty); row.add("properties", properties); }
            if (buildable) blocks.add(row);
            materials.merge(cell.id(), 1, Math::addExact);
        }
        blueprint.add("blocks", blocks);
        initialContents.forEach(e -> { JsonObject content = e.getAsJsonObject(); materials.merge(content.get("item_id").getAsString(), content.get("count").getAsInt(), Math::addExact); });
        JsonArray clear = new JsonArray(); clearance.forEach(at -> clear.add(position(at)));
        JsonObject materialCounts = new JsonObject(); materials.forEach(materialCounts::addProperty);
        report.addProperty("buildable", buildable); report.addProperty("physical_layout_compiled", buildable);
        report.addProperty("construction_complete", false); report.addProperty("configuration_complete", false); report.addProperty("production_verified", false);
        report.addProperty("physical_target_count", all.size()); report.add("logical_material_counts", materialCounts);
        report.addProperty("material_count_scope", "Audited full blocks, cable parts and planned drive contents; runtime item mapping and actual inventory remain authoritative.");
        report.add("components", components); report.add("connections", routes); report.add("clearance_cells", clear);
        report.add("unsupported", errors); report.add("obligations", obligations); report.add("configurations", configurations); report.add("modules", modules);
        report.add("initial_contents", initialContents);
        report.add("seal_after_cleanup", seals);
        report.add("filters", filters);
        JsonArray commissioning = new JsonArray();
        modules.forEach(e -> { if (e.getAsJsonObject().has("kind")) commissioning.add(e.deepCopy()); });
        report.add("commissioning_requirements", commissioning);
        JsonArray stages = new JsonArray();
        for (String stage : List.of("survey_and_clearance_preflight", "acquire_materials", "place_equipment", "install_transport_routes", "remove_temporary_supports", "seal_enclosures", "install_initial_contents", "configure_output_filters", "configure_native_interfaces", "verify_networks", "measure_production")) stages.add(stage);
        report.add("construction_stages", stages);
        return new SemanticMachineLayout.Result(buildable, blueprint, report);
    }
}
