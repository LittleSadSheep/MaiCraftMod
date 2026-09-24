// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.maiwithu.maicraft.core.integration.create.CreateBeltAccess;
import org.maiwithu.maicraft.core.integration.machine.MachinePlanningBudget;
import org.maiwithu.maicraft.server.machine.CreateConfigurationContract;

/** 显式机器组合契约只描述组件与原生动作，不发布任何按产物选择的工作站模板。 */
public final class MachineAssemblyResources {
    public static final String URI = "maicraft://knowledge/machine_assembly";
    private MachineAssemblyResources() {}
    public static List<KnowledgeDocument.Entry> entries() {
        return List.of(new KnowledgeDocument.Entry(URI, "machine.assembly", "通用机器组合与原生安装",
                "显式蓝图、加工承载面、原生安装依赖、动力端口与配置参数。", "machine assembly blueprint belt deployer processing pulley shaft kinetic filter configuration create.filter create.speed 机器 加工 机械手 传送带 传动轴 动力 漏斗 配置"));
    }
    public static KnowledgeDocument read(String uri) {
        if (!URI.equals(uri)) return null;
        var metadata = entries().getFirst(); JsonObject result = new JsonObject();
        result.addProperty("protocol", "explicit_machine_assembly_v1"); result.add("blueprint_schema", schema());
        result.addProperty("component_contract", MinecraftKnowledgeSource.BLOCK + "{namespace}/{path}");
        result.addProperty("recipe_contract", RecipeKnowledgeSource.PREFIX + "{namespace}/{path}");
        result.addProperty("production_contract", KnowledgeLibrary.PROCESSES);
        // 机器蓝图已有完整格式和安装原语，直接说明提交外壳，不引导角色先遍历无关的建筑场景教材。
        result.addProperty("submission", "Call plan with goal.ability=maicraft:build_machine, goal.outcome=a non-empty result description, goal.target=construction_site.target, and goal.parameters={snapshot_id:construction_site.snapshot_id, blueprint:the authored blueprint, allow_modify:true}. Put external_inputs and supply_preference inside blueprint. Then execute with plan_id and request_key; request_key is not a plan field.");
        result.addProperty("configuration_contract", "production.configurations uses installed native operations; keep filters, mode and input setup explicit, never copy observed NBT into placement");
        result.add("native_configuration_operations", CreateConfigurationContract.describe());
        // 教程与主流程保持一致：资源 IN 可直接按接收方块、偏移和轴面声明，生成端口编号不是额外的开工门槛。
        result.addProperty("review_workflow", "Read the selected process and component contracts, survey construction_site, author blueprint and external_inputs, then plan and execute. Declare known receiver offsets/faces directly; generated power_ports are optional binding references. Treat Ponder creative supplies as resource IN boundaries, prefer existing world networks and keep the requested product/mod constraints. Revise only concrete diagnostics; design_machine is optional.");
        result.addProperty("existing_power_discovery", "perceive(view=kinetic_sources, query=short name or ID, radius=32) searches loaded chunk indexes in the backend and returns at most 8 source_labels with native interfaces. Only visible outlets near the work floor are candidates. Partial/empty results do not prove no network exists; observed candidates still require authorized use. connect_external_input rechecks interfaces and stress.");
        result.addProperty("design_policy", "The author selects every component, work surface and transport technology. No product-specific workstation or implicit pipe/sorter/depot is inserted.");
        JsonObject belt = new JsonObject(); belt.addProperty("type", "create:belt"); belt.addProperty("available", CreateBeltAccess.available());
        belt.addProperty("item_id", CreateBeltAccess.ITEM.toString()); belt.addProperty("connector_count_per_link", 1);
        belt.addProperty("preparation", "Choose first/second positions; omitted endpoint shafts are generated with an axis perpendicular to belt travel. Explicit endpoint shafts are checked, never silently rotated. Intermediate positions must be empty or declared matching shaft/air targets. Undeclared obstacles are not removed.");
        belt.addProperty("native_action", "Select a plain unmarked connector; use first shaft then second shaft through the actual crosshair; verify the complete chain and one-item consumption. Never place belt blocks one by one.");
        belt.addProperty("processing_surface", "Only horizontal belts support the external-workpiece processing relation; dry prepared spans only.");
        belt.addProperty("transport_direction", "First/second define connector geometry, not guaranteed item flow. Verify actual motion and signed kinetic speed before claiming the intended transport direction.");
        belt.addProperty("power_interfaces", "Endpoint shafts are automatic. Add intermediate pulley positions with pulleys. Review power_ports for final block, offset, axis, connection face and stable port ID; installed/powered remain unknown until observed. Adjacent turning belts exchange items, not necessarily rotation.");
        belt.addProperty("power_binding", "Select a reviewed power_ports ID with external_inputs:[{id,medium:kinetic,port,minimum_rpm?,reason?}]. Compilation resolves the reference to the final belt block. After construction, connect_external_input uses your input id and freshly observed native shaft faces; the temporary preparation shaft is not the final target.");
        belt.addProperty("route", "For a horizontal turning route, give path waypoints in desired item-flow order instead of first/second/flow. Corners belong to the receiving segment; each outgoing end is adjacent to its receiver. Long legs split at the installed native length limit. Read item_handoffs and power_ports separately; each segment still needs actual motion/power verification.");
        try { if (CreateBeltAccess.available()) belt.addProperty("endpoint_distance_exclusive_limit", CreateBeltAccess.maximumLength()); }
        catch (RuntimeException unavailable) { belt.addProperty("available", false); belt.addProperty("unavailable_reason", unavailable.getMessage()); }
        JsonArray installers = new JsonArray(); installers.add(belt); result.add("native_installers", installers);
        result.addProperty("recipe_planning", "For product goals, preserve and resolve the exact expected_output. Reusable workstations may omit it. Read display_recipes[].backing_recipe.definition, or native_fallback.recipes[].native_definition.definition, for actual sequence, loops, transitional items, probabilities and conditions. Do not invent or replace the requested product.");
        result.addProperty("efficiency_planning", "Choose layout against an explicit throughput/material/space goal. Processing time, feed starvation, transport, recirculation and output blocking can all limit throughput. Geometry validation does not prove a maximum rate; use supported production observations and retain unknowns when evidence is missing.");
        return new KnowledgeDocument(URI, metadata.name(), metadata.title(), metadata.description(), result.toString(), "application/json");
    }
    public static JsonObject schema() {
        // 机器继续沿用普通块与 AE 部件格式，仅把新的装配和加工关系作为明确的可校验字段加入。
        JsonObject schema = JsonParser.parseString("""
                {"type":"object","required":["blocks"],"additionalProperties":false,
                 "properties":{
                   "schema_version":{"const":1},"expected_output":{"type":"string","pattern":"^[a-z0-9_.-]+:[a-z0-9/._-]+$"},
                   "blocks":{"type":"array","minItems":0,"items":{"oneOf":[
                     {"type":"object","required":["offset","block_id"],"additionalProperties":false,"properties":{
                       "offset":{"$ref":"#/$defs/position"},"block_id":{"type":"string"},"properties":{"type":"object","additionalProperties":{"type":"string"}},"nbt":{"type":"object"}}},
                     {"type":"object","required":["offset","item_id","part"],"additionalProperties":false,"properties":{
                       "offset":{"$ref":"#/$defs/position"},"item_id":{"type":"string"},"part":{"enum":["center","up","down","north","south","east","west"]}}}]}},
                   "constraints":{"type":"object","additionalProperties":false,"properties":{"forbidden_mods":{"type":"array","maxItems":64,"items":{"type":"string","pattern":"^[a-z0-9_.-]{1,64}$"}}}},
                   "assembly":{"type":"object","additionalProperties":false,"properties":{
                     "installations":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["type"],"oneOf":[
                       {"required":["first","second"],"not":{"required":["path"]}},
                       {"required":["path"],"not":{"anyOf":[{"required":["first"]},{"required":["second"]},{"required":["flow"]}]}}],"properties":{
                       "type":{"const":"create:belt"},"first":{"$ref":"#/$defs/position"},"second":{"$ref":"#/$defs/position"},
                       "path":{"type":"array","minItems":2,"items":{"$ref":"#/$defs/position"}},
                       "pulleys":{"type":"array","items":{"$ref":"#/$defs/position"}},"flow":{"enum":["first_to_second","second_to_first"]}}}},
                     "processing":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["processor","surface"],"properties":{
                       "processor":{"$ref":"#/$defs/position"},"surface":{"$ref":"#/$defs/position"}}}}}},
                   "external_inputs":{"type":"array","maxItems":8,"items":{"type":"object","additionalProperties":false,"required":["id","medium"],"properties":{
                     "id":{"type":"string","pattern":"^[a-zA-Z0-9_.-]+$","maxLength":64},"medium":{"enum":["kinetic","energy","fluids","chemicals","items"]},
                     "port":{"type":"string","maxLength":64},"offset":{"$ref":"#/$defs/position"},"face":{"enum":["up","down","north","south","east","west"]},"block_id":{"type":"string"},
                     "minimum_rpm":{"type":"integer","minimum":1,"maximum":1000000},"resource":{"type":"string"},"reason":{"type":"string","minLength":1,"maxLength":512}},
                     "oneOf":[{"required":["port"],"properties":{"medium":{"const":"kinetic"}},"not":{"anyOf":[{"required":["offset"]},{"required":["face"]},{"required":["block_id"]},{"required":["resource"]}]}},
                       {"required":["offset","face","block_id"],"not":{"required":["port"]}}]}},
                   "supply_preference":{"enum":["external","onsite"]},"onsite_reason":{"type":"string"},
                   "metadata":{"type":"object"},"evidence":{"type":"object"},"entities":{"type":"array"}},
                 "anyOf":[{"properties":{"blocks":{"minItems":1}}},{"required":["assembly"],"properties":{"assembly":{"required":["installations"],"properties":{"installations":{"minItems":1}}}}}],
                 "$defs":{"position":{"type":"array","minItems":3,"maxItems":3,"items":{"type":"integer"}}}}
                """).getAsJsonObject();
        var budget = MachinePlanningBudget.current();
        var coordinate = schema.getAsJsonObject("$defs").getAsJsonObject("position").getAsJsonObject("items");
        coordinate.addProperty("minimum", -budget.maxRadius()); coordinate.addProperty("maximum", budget.maxRadius());
        schema.getAsJsonObject("properties").getAsJsonObject("blocks").addProperty("maxItems", budget.maxTargets());
        var assembly = schema.getAsJsonObject("properties").getAsJsonObject("assembly").getAsJsonObject("properties");
        for (String kind : List.of("installations", "processing")) assembly.getAsJsonObject(kind).addProperty("maxItems", budget.maxConnections());
        return schema;
    }
}
