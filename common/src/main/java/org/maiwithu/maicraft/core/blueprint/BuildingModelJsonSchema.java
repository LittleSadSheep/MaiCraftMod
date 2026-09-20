// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.Set;
import org.maiwithu.maicraft.core.build.BuildingBudgets;
import java.util.List;

/** 导出作者模型的完整字段形状；几何相交、原生状态与预算总量仍由 Mod 在保存前实际校验。 */
final class BuildingModelJsonSchema {
    private final BuildingBudgets budget;
    private final JsonObject definitions = new JsonObject();
    private final JsonObject nodeProperties;
    private final long span;

    static JsonObject describe(BuildingBudgets budget) { return new BuildingModelJsonSchema(budget).document(); }

    private BuildingModelJsonSchema(BuildingBudgets budget) {
        this.budget = budget; span = 2L * budget.maxRadius() + 1;
        define("name", text(64,"^(?!\\s*$)[^/\\[\\]]+$"));
        define("material", object(new String[]{"block_id"},
                "block_id",text(256,"^[a-z0-9_.-]+:[a-z0-9/._-]+$"),
                "properties",dictionary(text(128,null),text(64,null),0,32)));
        define("modifier", object(new String[]{"type","operation","object"},
                "type",constant("BOOLEAN"),"operation",constant("DIFFERENCE"),"object",ref("name")));
        define("array", object(new String[]{"count","step"},
                "count",vector(number("integer",1,budget.maxObjects())),"step",vector(number("number",-1e9,1e9)),
                "skip",unique(array(vector(number("integer",0,budget.maxObjects()-1L)),0,budget.maxObjects()))));
        define("pattern", object(new String[]{"axes","rows"},
                "axes",unique(array(choices(Set.of("x","y","z")),2,2)),
                "rows",array(text(span,"^[01]+$"),1,span),
                "materials",object(new String[]{},"0",text(64,null),"1",text(64,null))));
        nodeProperties = properties(
                "name",ref("name"),"type",choices(Set.of("MESH","INSTANCE","cube","panel")),
                "location",vector(number("number",-1e9,1e9)),"dimensions",vector(number("integer",1,span)),
                "rotation_euler",vector(number("number",-1e9,1e9)),
                "mirror",unique(array(choices(Set.of("x","y","z")),0,3)),"array",ref("array"),
                "modifiers",array(ref("modifier"),0,budget.maxConnections()),
                "block_state_axes",choices(Set.of("local","minecraft_world")),
                "primitive",choices(BuildingModelSchema.PRIMITIVES),"role",choices(Set.of("solid","cutter")),
                "material",text(64,null),"fill",choices(Set.of("solid","hollow")),
                "wall_thickness",number("number",.5,span),"edge_width",number("number",.5,span),
                "face_materials",bindings(),"edge_material",text(64,null),"edge_materials",bindings(),
                "open_faces",unique(array(text(64,null),0,64)),"segments",number("integer",3,32),
                "vertices",array(vector(number("number",0,1)),4,64),
                "faces",array(array(number("integer",0,63),3,64),4,64),"pattern",ref("pattern"),
                "component",ref("name"),"material_map",bindings());
        // 设计 Agent 直接读取字段语义，不能把网格中心误当成角点，或把弧度当角度后生成另一座建筑。
        explain(nodeProperties,"location","Coordinates in the authored scene system. MESH uses its geometry centre; INSTANCE uses the component origin. One unit is one Minecraft block. Mesh bounds must land on integer block faces after transforms; centres may be half-integers.");
        explain(nodeProperties,"dimensions","Positive integer lengths along the authored scene axes. A panel is one block thick on at least one axis. INSTANCE has no dimensions; edit its component meshes instead.");
        explain(nodeProperties,"rotation_euler","Euler XYZ radians, not degrees. v2 accepts quarter turns about each axis; v1 only about the scene up axis. Unsupported angles are rejected, never rounded.");
        explain(nodeProperties,"material","Name in scene.materials. Every solid mesh needs one; a referenced Boolean cutter need not have a material and is not built independently.");
        explain(nodeProperties,"material_map","Named material substitutions for this component instance, applied from inner instances outward. They preserve exact authored block types and states.");
        explain(nodeProperties,"modifiers","Only BOOLEAN/DIFFERENCE. object names a solid primitive cutter in the same object list; a cutter cannot itself have modifiers or a pattern. A component's cutter references are isolated per instance.");
        explain(nodeProperties,"block_state_axes","local transforms block facing/axis/half with the component; minecraft_world keeps Minecraft-world directions. Impossible native states, such as vertical half slabs, are rejected.");
        definitions.getAsJsonObject("array").addProperty("description","Repeat along node-local axes before rotation/mirror. count and step are XYZ triples; skip contains zero-based instance indices. Repetitions must stay inside aggregate model budgets.");
        definitions.getAsJsonObject("pattern").addProperty("description","Panel-only binary tile; either digit may start. axes gives column then row in normalized Minecraft-local Y-up axes, also for Blender scenes; the remaining dimension is 1. Equal-width rows repeat from the local minimum corner and follow all transforms. Unmapped 1 keeps painted material; unmapped 0 is a hole. Named 0/1 materials can specify top/bottom slab states.");
        definitions.getAsJsonObject("material").addProperty("description","Exact installed block ID and optional final-state requirements. The Mod checks the real registry before saving/using a scene. Omitted properties are not additional final-state constraints.");
        // 公共字段连同语义说明只发布一次，所有对象通过本地引用复用，避免完整格式因重复正文挤满设计上下文。
        for (String field : List.of("location","dimensions","rotation_euler","material","material_map","modifiers","block_state_axes")) {
            define("field_"+field,nodeProperties.getAsJsonObject(field)); nodeProperties.add(field,ref("field_"+field));
        }
    }

    private JsonObject document() {
        // 显式列出 MESH、实例和旧版简写；不通过 additionalProperties 放行隐藏字段或操作脚本。
        var meshFields = union(BuildingModelSchema.COMMON,BuildingModelSchema.MESH);
        var mesh = node(meshFields,"MESH","name","type","primitive","location","dimensions");
        meshConditions(mesh);
        var instance = node(union(BuildingModelSchema.COMMON,BuildingModelSchema.INSTANCE),"INSTANCE","name","type","component");
        var shorthand = node(meshFields,null,"name","type","location","dimensions");
        shorthand.getAsJsonObject("properties").add("type",choices(Set.of("cube","panel")));
        shorthand.getAsJsonObject("properties").addProperty("primitive",false);
        for (String field : Set.of("segments","vertices","faces")) shorthand.getAsJsonObject("properties").addProperty(field,false);
        panelConstraint(shorthand,"type");
        addCondition(shorthand,has("pattern"),object(new String[]{},"type",constant("panel")));
        define("node", alternatives(mesh,instance,shorthand));
        define("component",object(new String[]{"objects"},"objects",array(ref("node"),1,budget.maxObjects())));

        var oldMesh = node(BuildingSceneCompiler.OBJECT_FIELDS,"MESH","name","type","primitive","location","dimensions");
        oldMesh.getAsJsonObject("properties").add("name",text(64,null));
        oldMesh.getAsJsonObject("properties").add("primitive",choices(Set.of("cube","panel")));
        oldMesh.getAsJsonObject("properties").add("modifiers",array(object(new String[]{"type","operation","object"},
                "type",constant("BOOLEAN"),"operation",constant("DIFFERENCE"),"object",text(64,null)),0,budget.maxConnections()));
        panelConstraint(oldMesh,"primitive");
        var oldShorthand = oldMesh.deepCopy(); oldShorthand.add("required",strings("name","type","location","dimensions"));
        oldShorthand.remove("allOf"); oldShorthand.getAsJsonObject("properties").add("type",choices(Set.of("cube","panel")));
        oldShorthand.getAsJsonObject("properties").addProperty("primitive",false); panelConstraint(oldShorthand,"type");
        define("node_v1",alternatives(oldMesh,oldShorthand));

        var sceneProperties = properties("schema_version",constant(2),"name",text(128,null),
                "coordinate_system",choices(Set.of("blender_z_up","minecraft_y_up")),
                "materials",dictionary(ref("material"),text(64,null),1,budget.maxObjects()),
                "objects",array(ref("node"),1,budget.maxObjects()),
                "components",dictionary(ref("component"),ref("name"),0,budget.maxObjects()),
                "block_state_axes",choices(Set.of("local","minecraft_world")),"overlap_policy",choices(Set.of("last_wins","error")));
        // 明确默认坐标系与叠加规则，调用者省略字段时也能预知墙面、孔洞和独立格栅的关系。
        sceneProperties.getAsJsonObject("coordinate_system").addProperty("default","blender_z_up");
        explain(sceneProperties,"coordinate_system","Default blender_z_up maps points [x,y,z] to Minecraft [x,z,-y]; minecraft_y_up uses Minecraft axes directly. The external goal target provides the fixed world anchor.");
        sceneProperties.getAsJsonObject("block_state_axes").addProperty("default","local");
        sceneProperties.getAsJsonObject("overlap_policy").addProperty("default","last_wins");
        explain(sceneProperties,"overlap_policy","last_wins lets later solids replace earlier solids; error rejects differing material/state overlaps. Boolean/hollow/default-pattern voids do not erase other objects' solids. World block replacement still needs separate permission.");
        define("scene_v2",selected(sceneProperties,BuildingModelSchema.FIELDS,"schema_version","materials","objects"));
        var old = selected(sceneProperties,BuildingSceneCompiler.FIELDS,"materials","objects");
        old.getAsJsonObject("properties").add("schema_version",constant(1));
        old.getAsJsonObject("properties").add("objects",array(ref("node_v1"),1,budget.maxObjects())); define("scene_v1",old);

        // 编辑只提交被点名的字段；合并后的完整模型仍须通过同一套图元和引用校验。
        var patch = selected(nodeProperties,union(meshFields,BuildingModelSchema.INSTANCE),"name");
        patch.getAsJsonObject("properties").add("name",text(64,null)); define("node_patch",patch);
        var edits = object(new String[]{},"schema_version",constant(2),"objects",array(ref("node_patch"),0,budget.maxObjects()),
                "materials",dictionary(ref("material"),text(64,null),0,budget.maxObjects()),
                "remove_objects",unique(array(text(64,null),0,budget.maxObjects())),
                "components",dictionary(ref("component"),text(64,null),0,budget.maxObjects()),
                "remove_components",unique(array(text(64,null),0,budget.maxObjects())),
                "block_state_axes",choices(Set.of("local","minecraft_world")),"overlap_policy",choices(Set.of("last_wins","error")));
        edits.addProperty("minProperties",1); define("scene_edits",edits);
        var document = alternatives(ref("scene_v2"),ref("scene_v1"));
        document.addProperty("$schema","https://json-schema.org/draft/2020-12/schema");
        document.addProperty("title","MaiCraft authored building scene");
        document.addProperty("description","Use schema_version 2 for current modelling. Format validation only: the Mod additionally checks object references, quarter-turn transforms, aligned bounds, convex geometry, native block states, pattern rows/axes, aggregate expansion and voxel budgets. Saving does not prove site access, supplies or completed construction. scene_edits is available in $defs for named updates.");
        document.add("$defs",definitions); return document;
    }

    private void meshConditions(JsonObject mesh) {
        panelConstraint(mesh,"primitive");
        addCondition(mesh,has("pattern"),object(new String[]{},"primitive",constant("panel")));
        addCondition(mesh,has("segments"),object(new String[]{},"primitive",choices(Set.of("prism","cylinder","cone"))));
        // Schema 数组顺序会参与内容指纹，不能让不同 JVM 的 Set 遍历顺序导致重启后凭据无故过期。
        for (String field : List.of("vertices","faces")) addCondition(mesh,has(field),object(new String[]{},"primitive",constant("convex_polyhedron")));
        addCondition(mesh,object(new String[]{"primitive"},"primitive",constant("convex_polyhedron")),has("vertices","faces"));
    }
    private static void panelConstraint(JsonObject value, String field) {
        var dimensions = new JsonObject(); dimensions.add("contains",constant(1));
        addCondition(value,object(new String[]{field},field,constant("panel")),openProperties("dimensions",dimensions));
    }
    private JsonObject node(Set<String> fields,String type,String... required) {
        var node = selected(nodeProperties,fields,required);
        if (type != null) node.getAsJsonObject("properties").add("type",constant(type)); return node;
    }
    private static void addCondition(JsonObject schema,JsonObject condition,JsonObject then) {
        // 条件只约束点名字段，不能把对象的其他合法字段误当成附加字段拒绝。
        condition.remove("additionalProperties"); then.remove("additionalProperties");
        if (!schema.has("allOf")) schema.add("allOf",new JsonArray());
        var rule = new JsonObject(); rule.add("if",condition); rule.add("then",then); schema.getAsJsonArray("allOf").add(rule);
    }
    private void define(String name,JsonObject value) { definitions.add(name,value); }
    private static JsonObject ref(String name) { var value = new JsonObject(); value.addProperty("$ref","#/$defs/"+name); return value; }
    private static JsonObject selected(JsonObject properties,Set<String> fields,String... required) {
        var value = object(required); var selected = value.getAsJsonObject("properties");
        fields.stream().sorted().forEach(field -> {
            if (!properties.has(field)) throw new IllegalStateException("Building Schema is missing runtime field " + field);
            selected.add(field,properties.get(field).deepCopy());
        }); return value;
    }
    private static JsonObject object(String[] required,Object... fields) {
        var value = new JsonObject(); value.addProperty("type","object"); value.addProperty("additionalProperties",false);
        value.add("properties",properties(fields)); if (required.length > 0) value.add("required",strings(required)); return value;
    }
    private static JsonObject openProperties(Object... fields) { var value = new JsonObject(); value.add("properties",properties(fields)); return value; }
    private static void explain(JsonObject fields,String key,String description) { fields.getAsJsonObject(key).addProperty("description",description); }
    private static JsonObject properties(Object... fields) {
        var value = new JsonObject(); for (int i=0;i<fields.length;i+=2) value.add((String)fields[i],(JsonElement)fields[i+1]); return value;
    }
    private static JsonObject dictionary(JsonObject items,JsonObject names,int min,long max) {
        var value = new JsonObject(); value.addProperty("type","object"); value.add("additionalProperties",items);
        value.add("propertyNames",names); value.addProperty("minProperties",min); value.addProperty("maxProperties",max); return value;
    }
    private static JsonObject bindings() { return dictionary(text(64,null),text(128,null),0,192); }
    private static JsonObject text(long max,String pattern) {
        var value = new JsonObject(); value.addProperty("type","string"); value.addProperty("minLength",1); value.addProperty("maxLength",max);
        value.addProperty("pattern",pattern == null ? "\\S" : pattern); return value;
    }
    private static JsonObject number(String type,double min,double max) {
        var value = new JsonObject(); value.addProperty("type",type); value.addProperty("minimum",min); value.addProperty("maximum",max); return value;
    }
    private static JsonObject vector(JsonObject items) { return array(items,3,3); }
    private static JsonObject array(JsonObject items,long min,long max) {
        var value = new JsonObject(); value.addProperty("type","array"); value.add("items",items); value.addProperty("minItems",min); value.addProperty("maxItems",max); return value;
    }
    private static JsonObject unique(JsonObject value) { value.addProperty("uniqueItems",true); return value; }
    private static JsonObject choices(Set<String> values) { var value = new JsonObject(); value.add("enum",strings(values.stream().sorted().toArray(String[]::new))); return value; }
    private static JsonObject constant(String value) { var result = new JsonObject(); result.addProperty("const",value); return result; }
    private static JsonObject constant(int value) { var result = new JsonObject(); result.addProperty("const",value); return result; }
    private static JsonArray strings(String... values) { var result = new JsonArray(); for (String value : values) result.add(value); return result; }
    private static JsonObject has(String... fields) { var result = new JsonObject(); result.add("required",strings(fields)); return result; }
    private static JsonObject alternatives(JsonObject... values) { var result = new JsonObject(); var choices = new JsonArray(); for (var value : values) choices.add(value); result.add("oneOf",choices); return result; }
    private static Set<String> union(Set<String> first,Set<String> second) { var result = new LinkedHashSet<>(first); result.addAll(second); return result; }
}
