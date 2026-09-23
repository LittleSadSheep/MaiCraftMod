// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.maiwithu.maicraft.core.integration.create.CreateBeltAccess;
import org.maiwithu.maicraft.core.integration.machine.MachinePlanningBudget;

/** 显式机器组合契约只描述组件与原生动作，不发布任何按产物选择的工作站模板。 */
public final class MachineAssemblyResources {
    public static final String URI = "maicraft://knowledge/machine_assembly";
    private MachineAssemblyResources() {}
    public static List<KnowledgeDocument.Entry> entries() {
        return List.of(new KnowledgeDocument.Entry(URI, "machine.assembly", "通用机器组合与原生安装",
                "显式蓝图、机械手与承载面、传送带原生连接、模组约束、配方工序和运行验收。", "machine assembly blueprint belt deployer processing 机器 加工 机械手 传送带"));
    }
    public static KnowledgeDocument read(String uri) {
        if (!URI.equals(uri)) return null;
        var metadata = entries().getFirst(); JsonObject result = new JsonObject();
        result.addProperty("protocol", "explicit_machine_assembly_v1"); result.add("blueprint_schema", schema());
        result.addProperty("component_contract", MinecraftKnowledgeSource.BLOCK + "{namespace}/{path}");
        result.addProperty("recipe_contract", RecipeKnowledgeSource.PREFIX + "{namespace}/{path}");
        result.addProperty("production_contract", KnowledgeLibrary.PROCESSES);
        result.addProperty("configuration_contract", "production.configurations uses installed native operations; keep filters, mode and input setup explicit, never copy observed NBT into placement");
        result.addProperty("design_policy", "The author selects every component, work surface and transport technology. No product-specific workstation or implicit pipe/sorter/depot is inserted.");
        JsonObject belt = new JsonObject(); belt.addProperty("type", "create:belt"); belt.addProperty("available", CreateBeltAccess.available());
        belt.addProperty("item_id", CreateBeltAccess.ITEM.toString()); belt.addProperty("connector_count_per_link", 1);
        belt.addProperty("preparation", "Declare both endpoint create:shaft blocks with the same explicit axis. Every intermediate position must be empty or an explicitly declared shaft/air target. Undeclared obstacles are not removed.");
        belt.addProperty("native_action", "Select a plain unmarked connector; use first shaft then second shaft through the actual crosshair; verify the complete chain and one-item consumption. Never place belt blocks one by one.");
        belt.addProperty("processing_surface", "Only horizontal belts support the external-workpiece processing relation; dry prepared spans only.");
        belt.addProperty("transport_direction", "First/second define connector geometry, not guaranteed item flow. Verify actual motion and signed kinetic speed before claiming the intended transport direction.");
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
                   "blocks":{"type":"array","minItems":1,"items":{"oneOf":[
                     {"type":"object","required":["offset","block_id"],"additionalProperties":false,"properties":{
                       "offset":{"$ref":"#/$defs/position"},"block_id":{"type":"string"},"properties":{"type":"object","additionalProperties":{"type":"string"}},"nbt":{"type":"object"}}},
                     {"type":"object","required":["offset","item_id","part"],"additionalProperties":false,"properties":{
                       "offset":{"$ref":"#/$defs/position"},"item_id":{"type":"string"},"part":{"enum":["center","up","down","north","south","east","west"]}}}]}},
                   "constraints":{"type":"object","additionalProperties":false,"properties":{"forbidden_mods":{"type":"array","maxItems":64,"items":{"type":"string","pattern":"^[a-z0-9_.-]{1,64}$"}}}},
                   "assembly":{"type":"object","additionalProperties":false,"properties":{
                     "installations":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["type","first","second"],"properties":{
                       "type":{"const":"create:belt"},"first":{"$ref":"#/$defs/position"},"second":{"$ref":"#/$defs/position"}}}},
                     "processing":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["processor","surface"],"properties":{
                       "processor":{"$ref":"#/$defs/position"},"surface":{"$ref":"#/$defs/position"}}}}}},
                   "external_inputs":{"type":"array"},"supply_preference":{"enum":["external","onsite"]},"onsite_reason":{"type":"string"},
                   "metadata":{"type":"object"},"evidence":{"type":"object"},"entities":{"type":"array"}},
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
