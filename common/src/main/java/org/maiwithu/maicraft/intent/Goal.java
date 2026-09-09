package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** 模型提出的目标：要做什么、做到什么程度、对象是谁；还没有具体路线、鼠标点击或物品槽操作。 */
public record Goal(String ability,
                   String outcome,
                   SemanticTarget target,
                   String parametersJson,
                   String preferencesJson,
                   List<Constraint> constraints,
                   List<Goal> children,
                   List<String> inheritedProtectionLabels) {

    public Goal(String ability, String outcome, SemanticTarget target, String parametersJson,
                String preferencesJson, List<Constraint> constraints, List<Goal> children) {
        this(ability, outcome, target, parametersJson, preferencesJson, constraints, children, List.of());
    }

    public Goal {
        // 参数存成 JSON 文本，步骤列表复制后禁止直接修改，避免别人拿着原对象改掉已接受的目标。
        ability = Objects.requireNonNull(ability, "ability");
        outcome = Objects.requireNonNull(outcome, "outcome");
        parametersJson = canonicalObject(parametersJson);
        preferencesJson = canonicalObject(preferencesJson);
        constraints = List.copyOf(constraints == null ? List.of() : constraints);
        children = List.copyOf(children == null ? List.of() : children);
        inheritedProtectionLabels = List.copyOf(inheritedProtectionLabels == null
                ? List.of() : new LinkedHashSet<>(inheritedProtectionLabels));
        if (inheritedProtectionLabels.stream().anyMatch(String::isBlank))
            throw new IllegalArgumentException("inherited protection labels must not be blank");
    }

    public static Goal fromJson(JsonObject source) {
        // 按约定字段读入目标，遇到组合目标就继续读取子目标；公开请求的格式校验在调用本方法前完成。
        String ability = source.get("ability").getAsString();
        String outcome = source.get("outcome").getAsString();
        SemanticTarget target = source.has("target") && !source.get("target").isJsonNull()
                ? SemanticTarget.fromJson(source.getAsJsonObject("target"))
                : null;
        List<Constraint> constraints = new ArrayList<>();
        JsonArray constraintArray = source.getAsJsonArray("constraints");
        if (constraintArray != null) {
            for (JsonElement element : constraintArray) {
                constraints.add(Constraint.fromJson(element.getAsJsonObject()));
            }
        }
        List<Goal> children = new ArrayList<>();
        JsonArray childArray = source.getAsJsonArray("children");
        if (childArray != null) {
            for (JsonElement element : childArray) {
                children.add(fromJson(element.getAsJsonObject()));
            }
        }
        return new Goal(
                ability,
                outcome,
                target,
                objectString(source, "parameters"),
                objectString(source, "preferences"),
                constraints,
                children);
    }

    public JsonObject parameters() {
        // 每次返回一份新对象；改它不会改原目标，要用 withParameters 创建修改后的目标。
        return JsonParser.parseString(parametersJson).getAsJsonObject();
    }

    public JsonObject preferences() {
        return JsonParser.parseString(preferencesJson).getAsJsonObject();
    }

    public Goal withParameters(JsonObject parameters) {
        return new Goal(ability, outcome, target, parameters.toString(), preferencesJson, constraints, children,
                inheritedProtectionLabels);
    }

    public Goal withTarget(SemanticTarget nextTarget) {
        // 例如把“前一步找到的地方”换成 Mod 确认的位置，其他要求保持原样。
        return new Goal(ability, outcome, nextTarget, parametersJson, preferencesJson, constraints, children,
                inheritedProtectionLabels);
    }

    /** 分组范围作为执行元数据携带，不塞进子能力未声明的参数，也不改写原始请求。 */
    public Goal withInheritedProtection(List<String> labels) {
        var inherited = new LinkedHashSet<>(labels);
        inherited.addAll(inheritedProtectionLabels);
        if (inherited.equals(new LinkedHashSet<>(inheritedProtectionLabels))) return this;
        return new Goal(ability, outcome, target, parametersJson, preferencesJson, constraints, children,
                List.copyOf(inherited));
    }

    public List<String> protectionLabels() {
        var labels = new LinkedHashSet<>(inheritedProtectionLabels);
        JsonElement declared = parameters().get("protected_labels");
        if (declared != null && declared.isJsonArray()) {
            for (JsonElement label : declared.getAsJsonArray()) {
                if (label.isJsonPrimitive() && label.getAsJsonPrimitive().isString()
                        && !label.getAsString().isBlank()) labels.add(label.getAsString());
            }
        }
        return List.copyOf(labels);
    }

    public List<Goal> executableSteps() {
        // 展开每层 sequence 时带上该层保护范围；子分组的新增保护不会回流到兄弟步骤。
        if ("maicraft:sequence".equals(ability)) {
            List<Goal> flattened = new ArrayList<>();
            for (Goal child : children) {
                flattened.addAll(child.withInheritedProtection(protectionLabels()).executableSteps());
            }
            return List.copyOf(flattened);
        }
        return List.of(this);
    }

    public JsonObject toJson() {
        // 保存或公开目标时，把参数和子步骤重新组成 JSON；这里描述的是请求，不证明已经执行。
        JsonObject result = new JsonObject();
        result.addProperty("ability", ability);
        result.addProperty("outcome", outcome);
        if (target != null) result.add("target", target.toJson());
        result.add("parameters", parameters());
        result.add("preferences", preferences());
        JsonArray constraintArray = new JsonArray();
        constraints.forEach(value -> constraintArray.add(value.toJson()));
        result.add("constraints", constraintArray);
        JsonArray childArray = new JsonArray();
        children.forEach(value -> childArray.add(value.toJson()));
        result.add("children", childArray);
        return result;
    }

    private static String objectString(JsonObject source, String key) {
        // 遇到缺失或非对象字段时会当成空对象；严格拒绝错误格式依赖外层校验。
        JsonObject value = source.has(key) && source.get(key).isJsonObject()
                ? source.getAsJsonObject(key)
                : new JsonObject();
        return value.toString();
    }

    private static String canonicalObject(String json) {
        // 空值统一为空对象，并重新解析再输出，去掉无意义空白；这不会把对象键按字母排序。
        if (json == null || json.isBlank()) return "{}";
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("goal metadata must be an object");
        return parsed.toString();
    }

    /** 某个维度的一格位置；不带 dimension 时，需要使用这个位置的功能结合当前世界判断。 */
    public record WorldPosition(int x, int y, int z, String dimension) {
        static WorldPosition fromJson(JsonObject value) {
            return new WorldPosition(
                    value.get("x").getAsInt(),
                    value.get("y").getAsInt(),
                    value.get("z").getAsInt(),
                    value.has("dimension") && !value.get("dimension").isJsonNull()
                            ? value.get("dimension").getAsString()
                            : null);
        }

        JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("x", x);
            result.addProperty("y", y);
            result.addProperty("z", z);
            if (dimension != null) result.addProperty("dimension", dimension);
            return result;
        }
    }

    /** 目标的描述方式：当前地点、坐标、地标名、人物名或“前一步的结果”等。 */
    public record SemanticTarget(String kind, String label, WorldPosition position, String relation) {
        static SemanticTarget fromJson(JsonObject value) {
            return new SemanticTarget(
                    value.get("kind").getAsString(),
                    nullableString(value, "label"),
                    value.has("position") && value.get("position").isJsonObject()
                            ? WorldPosition.fromJson(value.getAsJsonObject("position"))
                            : null,
                    nullableString(value, "relation"));
        }

        JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("kind", kind);
            if (label != null) result.addProperty("label", label);
            if (position != null) result.add("position", position.toJson());
            if (relation != null) result.addProperty("relation", relation);
            return result;
        }
    }

    /** 调用者提出的限制；这里只负责保存，是否真的支持由 SemanticGoalContract 检查。 */
    public record Constraint(String kind, String description, boolean hard, String parametersJson) {
        public Constraint {
            parametersJson = canonicalObject(parametersJson);
        }

        static Constraint fromJson(JsonObject value) {
            return new Constraint(
                    value.get("kind").getAsString(),
                    value.get("description").getAsString(),
                    !value.has("hard") || value.get("hard").getAsBoolean(),
                    objectString(value, "parameters"));
        }

        JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("kind", kind);
            result.addProperty("description", description);
            result.addProperty("hard", hard);
            result.add("parameters", JsonParser.parseString(parametersJson));
            return result;
        }
    }

    private static String nullableString(JsonObject value, String key) {
        return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : null;
    }
}
