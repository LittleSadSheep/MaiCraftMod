// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.maiwithu.maicraft.core.build.BuildingBudgets;

/** 固定本次设计使用的格式、编译语义和设计预算；世界地形、材料库存与施工可达性仍须现场核实。 */
public final class BuildingModelContract {
    // 几何采样、变换、状态归一化或对象叠加语义改变时必须递增，不能仅依赖字段 Schema 的哈希。
    public static final String COMPILER_SEMANTICS = "maicraft-building-compiler/1";
    public static final String INDEX_URI = "maicraft://building/index";
    public static final String SCHEMA_PREFIX = "maicraft://building/schema/";
    public static final String EXPECTED_CAPABILITY = "expected_capability_revision";
    public static final String EXPECTED_SCHEMA = "expected_design_schema_revision";
    public static final Set<String> OPERATIONS = Set.of("create_scene","update_scene","get_scene_info","get_object_info",
            "get_component_info","export_scene","preview","revise_project","build");
    private static BuildingBudgets cachedBudget;
    private static Snapshot cached;
    private BuildingModelContract() {}

    public record Snapshot(String revision,String designSchemaRevision,String schemaText,List<String> capabilities,String budgetText) {
        public Snapshot { capabilities = List.copyOf(capabilities); }
        public String schemaUri() { return SCHEMA_PREFIX + designSchemaRevision.substring("sha256:".length()); }
        public JsonObject schema() { return JsonParser.parseString(schemaText).getAsJsonObject(); }
        public JsonObject budgets() { return JsonParser.parseString(budgetText).getAsJsonObject(); }
        public JsonObject index() {
            var result = new JsonObject(); result.addProperty("protocol_version",1);
            result.addProperty("revision",revision); result.addProperty("design_schema_uri",schemaUri());
            result.addProperty("design_schema_revision",designSchemaRevision);
            var list = new JsonArray(); capabilities.forEach(list::add); result.add("capabilities",list);
            result.add("resources",new JsonArray()); result.addProperty("edit_schema_ref","#/$defs/scene_edits");
            result.addProperty("compiler_semantics",COMPILER_SEMANTICS); result.add("planning_budget",budgets());
            result.addProperty("revision_scope","Compiler semantics, design Schema, declared model operations/features and design budgets. Does not freeze the world, loaded chunks, block registries, supplies, access or successful construction; native block states are revalidated before use.");
            result.addProperty("schema_revision_scope","SHA-256 of the full canonical JSON Schema text served by design_schema_uri; all references are local $defs. Unknown historical hashes are rejected, never redirected to current content.");
            return result;
        }
    }

    public static synchronized Snapshot current() {
        // 正常客户端使用不可变的启动预算；测试或重启换预算时重新计算内容指纹，不复用不相符的目录。
        var budget = BuildingBudgets.current();
        if (cached == null || cachedBudget != budget) { cached = describe(budget); cachedBudget = budget; }
        return cached;
    }

    static Snapshot describe(BuildingBudgets budget) {
        String schema = canonical(BuildingModelJsonSchema.describe(budget)).toString();
        String schemaRevision = digest(schema);
        var capabilities = new ArrayList<String>(List.of("scene.v1","scene.v2","named_components","component_arrays",
                "quarter_turn_rotation","mirror","boolean.difference","panel.binary_pattern","immutable_scene_revisions",
                "named_object_edits","expected_revision_guards","exact_block_states"));
        BuildingModelSchema.PRIMITIVES.forEach(primitive -> capabilities.add("primitive."+primitive));
        OPERATIONS.forEach(operation -> capabilities.add("operation."+operation)); capabilities.sort(String::compareTo);
        var budgets = new JsonObject(); budgets.addProperty("max_targets",budget.maxTargets()); budgets.addProperty("max_objects",budget.maxObjects());
        budgets.addProperty("max_connections",budget.maxConnections()); budgets.addProperty("max_radius",budget.maxRadius());
        budgets.addProperty("max_voxel_work",budget.maxVoxelWork()); budgets.addProperty("max_scene_bytes",budget.maxSceneBytes());
        var descriptor = new JsonObject(); descriptor.addProperty("compiler_semantics",COMPILER_SEMANTICS);
        descriptor.addProperty("design_schema_revision",schemaRevision); descriptor.add("planning_budget",budgets);
        var features = new JsonArray(); capabilities.forEach(features::add); descriptor.add("capabilities",features);
        return new Snapshot(digest(canonical(descriptor).toString()),schemaRevision,schema,capabilities,canonical(budgets).toString());
    }

    public static boolean guarded(JsonObject parameters) { return parameters.has(EXPECTED_CAPABILITY) || parameters.has(EXPECTED_SCHEMA); }
    public static void checkExpected(JsonObject parameters) {
        if (!guarded(parameters)) return;
        var current = current();
        checkField(parameters,EXPECTED_CAPABILITY,current.revision()); checkField(parameters,EXPECTED_SCHEMA,current.designSchemaRevision());
    }
    private static void checkField(JsonObject parameters,String key,String actual) {
        if (!parameters.has(key)) return;
        String expected = BuildingSceneGeometry.string(parameters.get(key),256,key);
        if (!expected.equals(actual)) throw new IllegalArgumentException("building_contract_changed: " + key
                + "=" + expected + "; current=" + actual + ". Refresh " + INDEX_URI + " and revalidate the full design.");
    }
    public static void checkScene(BuildingSceneStore.Entry entry,JsonObject parameters) {
        if (!guarded(parameters)) return;
        checkExpected(parameters);
        // 有版本要求时，旧记录不能冒充已按当前契约校验；调用者仍可读完整模型后另建一个新场景。
        var current = current();
        if (!current.revision().equals(entry.capabilityRevision()) || !current.designSchemaRevision().equals(entry.designSchemaRevision()))
            throw new IllegalArgumentException("building_scene_revalidation_required: scene_id=" + entry.sceneId()
                    + " has missing or outdated validation revisions. Read its full scene and use create_scene to validate a new immutable revision; the old record is retained.");
    }
    static String digest(String text) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            var result = new JsonObject(); value.getAsJsonObject().keySet().stream().sorted()
                    .forEach(key -> result.add(key,canonical(value.getAsJsonObject().get(key)))); return result;
        }
        if (value.isJsonArray()) { var result = new JsonArray(); value.getAsJsonArray().forEach(item -> result.add(canonical(item))); return result; }
        return value.deepCopy();
    }
}
