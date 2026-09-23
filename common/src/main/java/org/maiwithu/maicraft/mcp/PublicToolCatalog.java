package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.MachineDesignBindings;

/**
 * MCP 对外的四个入口及 JSON 格式校验。它检查数据形状，不负责理解自然语言。
 * 具体业务能力放在 goal.ability 中，其参数说明和转换逻辑分别由 SemanticAbilityCatalog 和适配器维护。
 */
final class PublicToolCatalog {
    static final String PERCEIVE = "perceive";
    static final String PLAN = "plan";
    static final String EXECUTE = "execute";
    static final String TASK = "task";

    private static final Pattern RESOURCE_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    );
    private static final Set<String> VIEWS = Set.of(
            "situation", "surroundings", "abilities", "tasks", "attention", "landmarks", "machines", "machine_menu", "knowledge"
    );
    private static final Set<String> TASK_ACTIONS = Set.of("get", "list", "pause", "resume", "cancel", "answer");

    private static final JsonObject GOAL_DEFINITIONS = JsonParser.parseString("""
            {
              "worldPosition": {
                "type":"object",
                "description":"Full world coordinates. For travel with unknown height, omit target and use parameters.destination={x,z}; an optional y there is a height hint.",
                "properties": {
                  "x":{"type":"integer"}, "y":{"type":"integer"}, "z":{"type":"integer"},
                  "dimension":{"type":["string","null"],"pattern":"^[a-z0-9_.-]+:[a-z0-9_./-]+$"}
                },
                "required":["x","y","z"], "additionalProperties":false
              },
              "semanticTarget": {
                "type":"object",
                "properties": {
                  "kind":{"type":"string","enum":["current_place","coordinates","landmark","player","entity","nearest","area","prior_result"]},
                  "label":{"type":["string","null"],"minLength":1,"maxLength":160},
                  "position":{"anyOf":[{"$ref":"#/$defs/worldPosition"},{"type":"null"}]},
                  "relation":{"type":["string","null"],"minLength":1,"maxLength":120,"description":"For prior_result, identify the earlier semantic ability or outcome, for example the house built earlier; never copy coordinates."}
                },
                "required":["kind"], "additionalProperties":false
              },
              "constraint": {
                "type":"object",
                "properties": {
                  "kind":{"type":"string","pattern":"^[a-z0-9_.-]+:[a-z0-9_./-]+$"},
                  "description":{"type":"string","minLength":1,"maxLength":300},
                  "hard":{"type":"boolean","default":true},
                  "parameters":{"type":"object","default":{}}
                },
                "required":["kind","description"], "additionalProperties":false
              },
              "goal": {
                "type":"object",
                "properties": {
                  "ability":{"type":"string","pattern":"^[a-z0-9_.-]+:[a-z0-9_./-]+$"},
                  "outcome":{"type":"string","minLength":1,"maxLength":500},
                  "target":{"anyOf":[{"$ref":"#/$defs/semanticTarget"},{"type":"null"}]},
                  "parameters":{"type":"object","default":{},"description":"Only keys declared by the selected ability in perceive(view=abilities) are accepted."},
                  "preferences":{"type":"object","default":{},"description":"Must be empty unless the selected ability explicitly declares accepted_preferences."},
                  "constraints":{"type":"array","items":{"$ref":"#/$defs/constraint"},"maxItems":32,"default":[],"description":"Only parameter-free hard constraints explicitly declared by the selected ability are accepted."},
                  "children":{"type":"array","items":{"$ref":"#/$defs/goal"},"maxItems":32,"default":[],"description":"Ordered child outcomes; accepted only by maicraft:sequence."}
                },
                "required":["ability","outcome"], "additionalProperties":false
              }
            }
            """).getAsJsonObject();

    // 生成公开工具前注入地点规则，模型看到的坐标/标签组合与实际请求入口完全一致。
    static {
        PublicTargetContract.describe(GOAL_DEFINITIONS.getAsJsonObject("semanticTarget"));
        MachineDesignBindings.describe(GOAL_DEFINITIONS.getAsJsonObject("goal"));
    }

    private static final List<JsonObject> TOOLS = List.of(
            // 直接调用者用注意流等待；任务书经同一知识入口按需读，剧情资料不会直接触发角色动作。
            tool(PERCEIVE,
                    "For direct task monitoring, use view=attention with execute/task's next_attention arguments, then each response's next_attention. It waits for task or important body events and includes authoritative task state, pending decisions and terminal results. If the host already tracks tasks and delivers verified snapshots, reuse that monitor and yield through the host when nothing else is actionable; do not duplicate its waits or queries. A wait timeout only means no event arrived: the monitoring host continues waiting without requiring another model decision. Direct callers may continue waiting themselves. On decision/paused/unavailable, act or report. Received game chat is not part of attention; subscribe to maicraft://chatflow instead. Prefer query with knowledge to find product/tutorial candidates, or with abilities to find operation summaries, before requesting large catalogues. query tolerates limited name misspellings and loads metadata only. Select an exact returned URI or ability ID, then use resource_uri to read one document or focus to inspect one ability. For quest books start at maicraft://knowledge/ftbquests/index, read chapter and quest URIs, and re-read progress after relevant actions. FTB progress requires a synchronized world and is separate from MaiCraft execution tasks; bundled guides can be read offline. surroundings includes sampled terrain_overview with complete or partial coverage and synchronized elevator floors; needs_sync means unknown, not empty.",
                    schema("""
                            {
                              "type":"object",
                              "properties": {
                                "view":{"type":"string","enum":["situation","surroundings","abilities","tasks","attention","landmarks","machines","machine_menu","knowledge"],"default":"situation","description":"knowledge searches reference metadata or reads resource_uri; it does not authorize actions or prove runtime capabilities. landmarks returns labels, machines lists observations, machine_menu returns native menu evidence."},
                                "query":{"type":["string","null"],"minLength":1,"maxLength":256,"description":"knowledge or abilities only: concise search keywords, names or IDs. Prefer this to loading a full catalogue. Searches metadata without recipe bodies or complete ability contracts; exact matches rank before bounded spelling corrections. Use knowledge for products/materials/tutorials, abilities for operations. Select an exact returned URI or ability ID next. Mutually exclusive with focus and resource_uri. Literal keywords, not regex or shell commands."},
                                "focus":{"type":["string","null"],"maxLength":256,"description":"With knowledge, legacy literal item ID or search words; use query for spelling-tolerant discovery. With abilities, an exact namespaced ability ID; use query for keywords. Omit focus when passing query or resource_uri. With situation, maicraft:physical_structures observes Sable ships, gaze hits, poses and support surfaces; maicraft:travel or maicraft:elevators adds the elevator floor list; maicraft:navigation or maicraft:transport also includes actor, collision, jetpack and elevator diagnostics. With surroundings, optional literal sign text; view direction and physical structures are also returned."},
                                "resource_uri":{"type":["string","null"],"maxLength":2048,"description":"knowledge only: an exact discovered maicraft://knowledge/... or maicraft://building/... URI. Read maicraft://building/index for the current model contract and content-addressed JSON Schema; resources are read in full without loading unrelated documents."},
                                 "task_id":{"type":["string","null"],"pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$","description":"For attention, filter task events while retaining important body events and include authoritative task state. For tasks, read one full task."},
                                 "stream_id":{"type":["string","null"],"pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$","description":"Attention only: copy from next_attention to detect restart or world change."},
                                 "after_cursor":{"type":"integer","minimum":0,"maximum":9007199254740991,"default":0,"description":"Attention only: copy response cursor, never latest_cursor. Use next_attention for safe pagination."},
                                 "wait_ms":{"type":"integer","minimum":0,"maximum":60000,"default":0,"description":"Attention only: event-driven wait, normally 30000 ms. Returns immediately for completed tasks, pending decisions, pauses, missing tasks or resync; timeout does not cancel the game task."},
                                 "limit":{"type":"integer","minimum":1,"maximum":20,"default":10},
                                "sections":{"type":"array","maxItems":32,"items":{"type":"string","minLength":1,"maxLength":48},"description":"situation or surroundings only: return just these top-level sections instead of the whole snapshot, so a long observation cannot be truncated before the section you need. Section names are the keys of the full response; an unknown name is rejected. surroundings accepts position, dimension, sky_light, biome, nearby_entities, nearby_signs, sign_observation, local_decision_summary, terrain_overview, elevators, view, physical_structures; situation accepts those plus inventory, equipment, health and the focus diagnostics. terrain_overview is the sampled terrain: a request that does not name it skips the sampling wait, so a narrow request answers immediately. A requested section this request produced nothing for is named in sections_unavailable; an absent section means unknown, not empty."},
                                "server_id":{"type":"string","minLength":1,"maxLength":128,"default":"minecraft-server"}
                              },
                              "additionalProperties":false
                            }
                            """), annotations(true, false, true)),
            tool(PLAN,
                    // 建造前先提供模型或蓝图；提前说明对象的孔洞不会擦掉独立格栅，实体相交仍按声明的覆盖策略结算。
                    "Compile a goal without starting it. maicraft:build and maicraft:design_build require an LLM-authored Blender-style scene, saved scene_id or explicit block blueprint; natural-language outcomes alone cannot generate a building. Modelling operations inspect/edit/preview/export named objects; use project_id to resume frozen construction. In v2 scenes, Boolean/hollow/default pattern holes never erase other objects' solid cells; solids can fill holes, while solid overlaps follow overlap_policy. Read the build ability contract first.",
                    goalSchema("""
                            {
                              "type":"object",
                              "properties": {
                                "goal":{"$ref":"#/$defs/goal"},
                                "server_id":{"type":"string","minLength":1,"maxLength":128,"default":"minecraft-server"}
                              },
                              "required":["goal"], "additionalProperties":false
                            }
                            """), annotations(false, false, false)),
            tool(EXECUTE,
                    "Start a goal or compiled plan asynchronously. Pass the returned next_attention to perceive to wait for authoritative task state and results; prefer Attention over polling task/get or wrapping execution as a synchronous call.",
                    goalSchema("""
                            {
                              "type":"object",
                              "properties": {
                                "goal":{"$ref":"#/$defs/goal"},
                                "plan_id":{"type":["string","null"],"pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"},
                                "request_key":{"type":["string","null"],"minLength":1,"maxLength":128},
                                "server_id":{"type":"string","minLength":1,"maxLength":128,"default":"minecraft-server"}
                              },
                              "additionalProperties":false
                            }
                            """), annotations(false, true, false)),
            tool(TASK,
                    "Inspect or control a runtime task. Prefer perceive(view=attention) for waiting and completion; get/list are for explicit inspection and recovery. After a control action, continue with next_attention. When state=waiting_for_decision, answer with the exact "
                            + "decision_id and one listed choice. retry may refine details.parameters; recover "
                            + "and replace_goal require one semantic details.goal. Never provide internal tool "
                              + "names, routes or click scripts. Machine design/build and apply_blueprint accept "
                              + "the declared blueprint JSON or blueprint_uri; menu operations use observed receipts.",
                    goalSchema("""
                            {
                              "type":"object",
                              "properties": {
                                "action":{"type":"string","enum":["get","list","pause","resume","cancel","answer"]},
                                 "task_id":{"type":["string","null"],"pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"},
                                 "request_key":{"type":["string","null"],"minLength":1,"maxLength":128,"description":"With action=list, look up the task accepted for an execute request key after an uncertain transport outcome."},
                                 "answer":{
                                  "anyOf":[
                                    {"type":"null"},
                                    {
                                      "type":"object",
                                      "properties": {
                                        "decision_id":{"type":"string","pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"},
                                        "choice":{"type":"string","minLength":1,"maxLength":120},
                                        "details":{
                                          "type":"object",
                                          "properties":{
                                            "parameters":{"type":"object"},
                                            "goal":{"$ref":"#/$defs/goal"}
                                          },
                                          "default":{}
                                        }
                                      },
                                      "required":["decision_id","choice"], "additionalProperties":false
                                    }
                                  ]
                                },
                                "limit":{"type":"integer","minimum":1,"maximum":50,"default":20},
                                "server_id":{"type":"string","minLength":1,"maxLength":128,"default":"minecraft-server"}
                              },
                              "required":["action"], "additionalProperties":false
                            }
                            """), annotations(false, true, false))
    );

    private PublicToolCatalog() {
    }

    static JsonArray definitions() {
        // 给每个客户端一份接口定义副本，避免调用者修改返回内容时把所有人的工具定义也改掉。
        JsonArray array = new JsonArray();
        TOOLS.forEach(tool -> array.add(tool.deepCopy()));
        return array;
    }

    static boolean contains(String name) {
        return PERCEIVE.equals(name) || PLAN.equals(name) || EXECUTE.equals(name) || TASK.equals(name);
    }

    static JsonObject validateAndNormalize(String name, JsonElement rawArguments) {
        // 复制请求后补默认值、查格式，不直接改调用者传进来的原对象。
        if (rawArguments == null || rawArguments.isJsonNull()) {
            rawArguments = new JsonObject();
        }
        if (!rawArguments.isJsonObject()) {
            throw bad("arguments must be an object");
        }
        JsonObject arguments = rawArguments.getAsJsonObject().deepCopy();
        switch (name) {
            case PERCEIVE -> validatePerceive(arguments);
            case PLAN -> validatePlan(arguments);
            case EXECUTE -> validateExecute(arguments);
            case TASK -> validateTask(arguments);
            default -> throw bad("unknown tool: " + name);
        }
        return arguments;
    }

    private static void validatePerceive(JsonObject value) {
        // 不同查看方式接受不同字段，例如等待时长只属于 Attention，文档地址只属于知识读取。
        only(value, "view", "focus", "query", "resource_uri", "task_id", "stream_id", "after_cursor", "wait_ms", "limit",
                "sections", "server_id");
        defaults(value, "view", "situation", "after_cursor", 0, "wait_ms", 0,
                "limit", 10, "server_id", "minecraft-server");
        String view = string(value, "view", 1, 32, false);
        if (!VIEWS.contains(view)) throw bad("view has an unsupported value");
        // 模糊发现与精确读取明确分开，不能把产品名传给只接受能力标识的 focus。
        nullableString(value, "query", 1, 256);
        if (present(value, "query")) {
            if (!Set.of("knowledge", "abilities").contains(view)) throw bad("query is only supported by knowledge and abilities");
            if (value.get("query").getAsString().isBlank()) throw bad("query must contain search keywords");
            if (present(value, "focus") || present(value, "resource_uri")) throw bad("Use query to discover candidates, then focus or resource_uri to read one; do not combine them");
        }
        if ("surroundings".equals(view)) nullableString(value, "focus", 1, 128);
        else if ("knowledge".equals(view)) nullableString(value, "focus", 1, 256);
        else nullableResource(value, "focus");
        nullableString(value, "resource_uri", 1, 2048);
        if (present(value, "resource_uri") && !"knowledge".equals(view)) throw bad("resource_uri is only supported by knowledge");
        if (present(value, "resource_uri") && present(value, "focus")) throw bad("Use focus to search or resource_uri to read, not both");
        nullableUuid(value, "task_id");
        nullableUuid(value, "stream_id");
        long cursor = longInteger(value, "after_cursor", 0, 9_007_199_254_740_991L);
        int waitMs = integer(value, "wait_ms", 0, 60_000);
        integer(value, "limit", 1, 20);
        string(value, "server_id", 1, 128, false);
        boolean hasTask = present(value, "task_id");
        if (hasTask && !Set.of("tasks", "attention").contains(view)) {
            throw bad("task_id is only supported by tasks and attention");
        }
        if (present(value, "stream_id") && !"attention".equals(view)) {
            throw bad("stream_id is only supported by attention");
        }
        if (cursor != 0 && !"attention".equals(view)) {
            throw bad("after_cursor is only supported by the attention view");
        }
        if (waitMs != 0 && !"attention".equals(view)) {
            throw bad("wait_ms is only supported by the attention view");
        }
        if (present(value, PerceiveSections.SECTIONS)) {
            // 逐段投影只对"段袋"响应成立；段名必须本视图真的会产出，拼错就地拒绝而不是静默返回空。
            if (!PerceiveSections.supports(view)) {
                throw bad("sections is only supported by situation and surroundings");
            }
            JsonArray sections = array(value, PerceiveSections.SECTIONS, 32);
            if (sections.isEmpty()) throw bad("sections must name at least one section");
            Set<String> known = PerceiveSections.known(view);
            for (JsonElement section : sections) {
                if (!section.isJsonPrimitive() || !section.getAsJsonPrimitive().isString()) {
                    throw bad("sections entries must be strings");
                }
                String name = section.getAsString();
                if (name.length() < 1 || name.length() > 48) throw bad("sections entries have an invalid length");
                if (name.equals(PerceiveSections.UNAVAILABLE) || !known.contains(name)) {
                    throw bad("unknown " + view + " section: " + name);
                }
            }
        }
    }

    private static void validatePlan(JsonObject value) {
        // 计划请求必须给完整目标；这里先查结构，能力自己的参数名和值规则还会在 IntentRuntime 检查。
        only(value, "goal", "server_id");
        defaults(value, "server_id", "minecraft-server");
        require(value, "goal");
        validateGoal(object(value, "goal"), 0);
        string(value, "server_id", 1, 128, false);
    }

    private static void validateExecute(JsonObject value) {
        // 目标与旧计划编号必须二选一，不允许同时提交后让执行端猜该用哪份。
        only(value, "goal", "plan_id", "request_key", "server_id");
        defaults(value, "server_id", "minecraft-server");
        boolean hasGoal = present(value, "goal");
        boolean hasPlan = present(value, "plan_id");
        if (hasGoal == hasPlan) throw bad("exactly one of goal and plan_id is required");
        if (hasGoal) validateGoal(object(value, "goal"), 0);
        if (hasPlan) nullableUuid(value, "plan_id");
        nullableString(value, "request_key", 1, 128);
        string(value, "server_id", 1, 128, false);
    }

    private static void validateTask(JsonObject value) {
        // list 无需任务编号；控制单项任务必须有编号；answer 还要说明正在回答哪个问题、选哪一项。
        only(value, "action", "task_id", "request_key", "answer", "limit", "server_id");
        defaults(value, "limit", 20, "server_id", "minecraft-server");
        String action = string(value, "action", 1, 16, false);
        if (!TASK_ACTIONS.contains(action)) throw bad("action has an unsupported value");
        integer(value, "limit", 1, 50);
        string(value, "server_id", 1, 128, false);
        boolean hasTask = present(value, "task_id");
        boolean hasRequestKey = present(value, "request_key");
        boolean hasAnswer = present(value, "answer");
        if ("list".equals(action)) {
            if (hasTask || hasAnswer) throw bad("list does not accept task_id or answer");
            nullableString(value, "request_key", 1, 128);
            return;
        }
        if (hasRequestKey) throw bad("request_key is only supported by the list action");
        if (!hasTask) throw bad("task_id is required for this action");
        nullableUuid(value, "task_id");
        if ("answer".equals(action)) {
            if (!hasAnswer) throw bad("answer is required for the answer action");
            JsonObject answer = object(value, "answer");
            only(answer, "decision_id", "choice", "details");
            defaults(answer, "details", new JsonObject());
            uuid(string(answer, "decision_id", 36, 36, false), "decision_id");
            String choice = string(answer, "choice", 1, 120, false);
            JsonObject details = object(answer, "details");
            only(details, "parameters", "goal");
            if (present(details, "parameters")) object(details, "parameters");
            if (present(details, "goal")) validateGoal(object(details, "goal"), 0);
            boolean changesSemanticGoal = "recover".equals(choice) || "replace_goal".equals(choice);
            // 改目标用 details.goal，普通重试调整参数用 details.parameters，不能把两种含义混着传。
            if (changesSemanticGoal && !present(details, "goal")) {
                throw bad(choice + " requires one semantic details.goal");
            }
            if (changesSemanticGoal && present(details, "parameters")) {
                throw bad(choice + " accepts details.goal, not a separate parameters object");
            }
            if (!changesSemanticGoal && present(details, "goal")) {
                throw bad("details.goal is accepted only by recover or replace_goal");
            }
        } else if (hasAnswer) {
            throw bad("answer is only supported by the answer action");
        }
    }

    private static void validateGoal(JsonObject goal, int depth) {
        // 限制嵌套深度和每一层子目标数量；当前没有在这里累计展开后的总步骤数。
        if (depth > 32) throw bad("goal nesting is too deep");
        only(goal, "ability", "outcome", "target", "parameters", "preferences", "constraints", "children");
        defaults(goal, "parameters", new JsonObject(), "preferences", new JsonObject(),
                "constraints", new JsonArray(), "children", new JsonArray());
        String ability = string(goal, "ability", 1, 256, false);
        resource(ability, "ability");
        string(goal, "outcome", 1, 500, false);
        if (present(goal, "target")) validateTarget(object(goal, "target"));
        object(goal, "parameters");
        // 这里仅确认 parameters 是对象，不检查里面 count 等各能力参数的整数类型或数值范围。
        object(goal, "preferences");
        JsonArray constraints = array(goal, "constraints", 32);
        constraints.forEach(item -> validateConstraint(asObject(item, "constraint")));
        JsonArray children = array(goal, "children", 32);
        for (JsonElement child : children) validateGoal(asObject(child, "child goal"), depth + 1);
        if (ability.equals("maicraft:design_machine")) MachineDesignBindings.validate(Goal.fromJson(goal));
        boolean sequence = "maicraft:sequence".equals(ability);
        if (sequence && children.isEmpty()) throw bad("maicraft:sequence requires at least one child");
        if (!sequence && !children.isEmpty()) throw bad("only maicraft:sequence may contain children");
        if (sequence) {
            JsonObject parameters = goal.getAsJsonObject("parameters");
            only(parameters, "protected_labels");
            if (present(parameters, "protected_labels")) {
                JsonArray labels = array(parameters, "protected_labels", 64);
                for (JsonElement label : labels) {
                    if (!label.isJsonPrimitive() || !label.getAsJsonPrimitive().isString()
                            || label.getAsString().isBlank() || label.getAsString().length() > 160) {
                        throw bad("protected_labels must contain non-empty semantic labels");
                    }
                }
            }
        }
    }

    private static void validateTarget(JsonObject target) {
        // 坐标目标必须有位置，其他目标不能夹带位置；地标、人物等要有名字，引用前一步要说明引用关系。
        only(target, "kind", "label", "position", "relation");
        String kind = string(target, "kind", 1, 32, false);
        nullableString(target, "label", 1, 160);
        nullableString(target, "relation", 1, 120);
        boolean hasPosition = present(target, "position");
        if (hasPosition) {
            JsonObject position = object(target, "position");
            only(position, "x", "y", "z", "dimension");
            integer(position, "x", Integer.MIN_VALUE, Integer.MAX_VALUE);
            integer(position, "y", Integer.MIN_VALUE, Integer.MAX_VALUE);
            integer(position, "z", Integer.MIN_VALUE, Integer.MAX_VALUE);
            nullableResource(position, "dimension");
        }
        PublicTargetContract.validate(target, kind);
    }

    private static void validateConstraint(JsonObject constraint) {
        // 这里只看条件描述的格式；条件种类是否真的支持，在语义契约里继续检查。
        only(constraint, "kind", "description", "hard", "parameters");
        defaults(constraint, "hard", true, "parameters", new JsonObject());
        resource(string(constraint, "kind", 1, 256, false), "constraint kind");
        string(constraint, "description", 1, 300, false);
        if (!constraint.get("hard").isJsonPrimitive() || !constraint.getAsJsonPrimitive("hard").isBoolean()) {
            throw bad("hard must be a boolean");
        }
        object(constraint, "parameters");
    }

    private static JsonObject tool(String name, String description, JsonObject inputSchema, JsonObject annotations) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        tool.add("inputSchema", inputSchema);
        tool.add("annotations", annotations);
        return tool;
    }

    private static JsonObject annotations(boolean readOnly, boolean destructive, boolean idempotent) {
        JsonObject value = new JsonObject();
        value.addProperty("readOnlyHint", readOnly);
        value.addProperty("destructiveHint", destructive);
        value.addProperty("idempotentHint", idempotent);
        value.addProperty("openWorldHint", true);
        return value;
    }

    private static JsonObject schema(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonObject goalSchema(String json) {
        JsonObject result = schema(json);
        result.add("$defs", GOAL_DEFINITIONS.deepCopy());
        return result;
    }

    private static void defaults(JsonObject object, Object... pairs) {
        // 只给缺失字段补值；明确写了 null 的字段不会在这里被替换成默认值。
        for (int i = 0; i < pairs.length; i += 2) {
            String key = (String) pairs[i];
            if (!object.has(key)) {
                Object value = pairs[i + 1];
                if (value instanceof String text) object.addProperty(key, text);
                else if (value instanceof Number number) object.addProperty(key, number);
                else if (value instanceof Boolean bool) object.addProperty(key, bool);
                else object.add(key, ((JsonElement) value).deepCopy());
            }
        }
    }

    private static void only(JsonObject object, String... allowed) {
        Set<String> names = Set.of(allowed);
        object.keySet().forEach(key -> {
            if (!names.contains(key)) throw bad("unexpected field: " + key);
        });
    }

    private static void require(JsonObject object, String key) {
        if (!present(object, key)) throw bad(key + " is required");
    }

    private static boolean present(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull();
    }

    private static JsonObject object(JsonObject parent, String key) {
        require(parent, key);
        return asObject(parent.get(key), key);
    }

    private static JsonObject asObject(JsonElement value, String label) {
        if (!value.isJsonObject()) throw bad(label + " must be an object");
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonObject parent, String key, int max) {
        require(parent, key);
        if (!parent.get(key).isJsonArray()) throw bad(key + " must be an array");
        JsonArray value = parent.getAsJsonArray(key);
        if (value.size() > max) throw bad(key + " has too many entries");
        return value;
    }

    private static String string(JsonObject object, String key, int min, int max, boolean nullable) {
        if (!object.has(key)) throw bad(key + " is required");
        JsonElement element = object.get(key);
        if (element.isJsonNull() && nullable) return null;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw bad(key + " must be a string");
        }
        String value = element.getAsString();
        if (value.length() < min || value.length() > max) throw bad(key + " has an invalid length");
        return value;
    }

    private static void nullableString(JsonObject object, String key, int min, int max) {
        if (object.has(key) && !object.get(key).isJsonNull()) string(object, key, min, max, false);
    }

    private static int integer(JsonObject object, String key, int min, int max) {
        return (int) longInteger(object, key, min, max);
    }

    private static long longInteger(JsonObject object, String key, long min, long max) {
        // 协议层整数使用精确转换，小数和超出 long 的数字直接拒绝，再检查业务允许范围。
        require(object, key);
        JsonElement element = object.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw bad(key + " must be an integer");
        }
        try {
            long value = element.getAsBigDecimal().longValueExact();
            if (value < min || value > max) throw bad(key + " is outside the accepted range");
            return value;
        } catch (ArithmeticException exception) {
            throw bad(key + " must be an integer");
        }
    }

    private static void nullableResource(JsonObject object, String key) {
        if (object.has(key) && !object.get(key).isJsonNull()) {
            resource(string(object, key, 1, 256, false), key);
        }
    }

    private static void nullableUuid(JsonObject object, String key) {
        if (object.has(key) && !object.get(key).isJsonNull()) {
            uuid(string(object, key, 36, 36, false), key);
        }
    }

    private static void resource(String value, String label) {
        if (!RESOURCE_ID.matcher(value).matches()) throw bad(label + " must be a namespaced resource identifier");
    }

    private static void uuid(String value, String label) {
        if (!CANONICAL_UUID.matcher(value).matches()) throw bad(label + " must be a canonical lowercase UUID");
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw bad(label + " must be a valid UUID");
        }
    }

    private static IllegalArgumentException bad(String message) {
        return new IllegalArgumentException(message);
    }
}
