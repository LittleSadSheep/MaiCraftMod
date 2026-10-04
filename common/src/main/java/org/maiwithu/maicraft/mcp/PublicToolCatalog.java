package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.MachineDesignBindings;

import org.maiwithu.maicraft.mcp.knowledge.web.WebKnowledgeService;

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
            "situation", "surroundings", "construction_site", "kinetic_sources", "abilities", "tasks", "attention", "landmarks", "exploration", "machines", "machine_menu", "knowledge", "web_knowledge"
    );
    private static final Set<String> TASK_ACTIONS = Set.of("get", "list", "pause", "resume", "cancel", "answer");

    // 规划、执行和恢复共用目标说明；保留未知高度时的行走入口及能力限制，避免重复解释挤占工具预算。
    private static final JsonObject GOAL_DEFINITIONS = JsonParser.parseString("""
            {
              "worldPosition": {
                "type":"object",
                "description":"For travel without a known y, omit target and set parameters.destination={x,z}.",
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
                  "relation":{"type":["string","null"],"minLength":1,"maxLength":120,"description":"Name the earlier ability or outcome for prior_result. Do not copy coordinates."}
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
                  "outcome":{"type":"string","minLength":1,"maxLength":500,"description":"Desired outcome."},
                  "target":{"anyOf":[{"$ref":"#/$defs/semanticTarget"},{"type":"null"}]},
                  "parameters":{"type":"object","default":{},"description":"Use fields from perceive(view=abilities,focus=ability)."},
                  "preferences":{"type":"object","default":{},"description":"Leave empty unless this ability declares accepted_preferences."},
                  "constraints":{"type":"array","items":{"$ref":"#/$defs/constraint"},"maxItems":32,"default":[],"description":"Use only this ability's parameter-free hard constraints."},
                  "children":{"type":"array","items":{"$ref":"#/$defs/goal"},"maxItems":32,"default":[],"description":"Ordered steps for maicraft:sequence only."},
                  "on_failure":{"type":"string","enum":["stop","continue"]}
                },
                "required":["ability","outcome"], "additionalProperties":false
              }
            }
            """).getAsJsonObject();

    // 生成公开工具前注入地点规则，模型看到的坐标/标签组合与实际请求入口完全一致。
    static {
        PublicTargetContract.describe(GOAL_DEFINITIONS.getAsJsonObject("semanticTarget"));
        // 特定机器审阅条件按能力读取，入口校验仍执行同一规则，不在三个目标 Schema 中重复展开。
    }

    private static final List<JsonObject> TOOLS = List.of(
            // 建造先勘测并沿用返回的锚点，避免为找工具参数先遍历任务历史或整套教材；执行后再等注意流。
            // 入口说明观察与等待；能力先一次列全名称，再让模型直接 focus 所选动作，概要只按需读取。
            tool(PERCEIVE,
                    "Observe the world, find an ability, or read reference material. To wait for a task, reuse its next_attention arguments.",
                    perceiveSchema("""
                            {
                              "type":"object",
                              "properties": {
                                "view":{"type":"string","enum":["situation","surroundings","construction_site","kinetic_sources","abilities","tasks","attention","landmarks","exploration","machines","machine_menu","knowledge","web_knowledge"],"default":"situation","description":"Read body and inventory with situation, nearby facts with surroundings, build geometry with construction_site, visible power with kinetic_sources, or saved observations with machines. abilities lists all action IDs. web_knowledge needs exactly one of query or url; version compatibility is unverified."},
                                "url":{"type":["string","null"],"maxLength":2048,"description":"Read a Minecraft Wiki /w/ or www.mcmod.cn item/class/post article over HTTPS, without URL query parameters. Selects web_knowledge; omit query, source and language."},
                                "source":{"type":["string","null"],"enum":["minecraft_wiki","mcmod",null],"description":"Choose a web_knowledge search source; default minecraft_wiki. MC百科 robots forbid search; use an article url instead."},
                                "language":{"type":["string","null"],"enum":["zh","en",null],"description":"Choose the web_knowledge Wiki search language; default zh. A url sets its own language."},
                                "subject_id":{"type":["string","null"],"pattern":"^[a-z0-9_.-]+:[a-z0-9_./-]+$","description":"For web_knowledge, give a registry ID to include its installed mod version. This does not verify article compatibility."},
                                "label":{"type":["string","null"],"minLength":1,"maxLength":160,"description":"Name the construction_site, or omit to generate a label for its anchor."},
                                "radius":{"type":"integer","minimum":1,"maximum":64,"description":"Use 1..8 for construction_site (default 8), or 8..64 for kinetic_sources (default 32). Power scans also cap height and exclude hidden or protected outlets."},
                                "query":{"type":["string","null"],"minLength":1,"maxLength":256,"description":"Search knowledge, abilities, exploration, web_knowledge or kinetic_sources by keyword. Only exploration allows a catalog focus with query. Omit resource_uri and url."},
                                "focus":{"type":["string","null"],"maxLength":256,"description":"Read an ability's full contract by ID, or maicraft:server_assistance; omit for all ability names. For situation, prefix travel, elevators, physical_structures, navigation or transport with maicraft:. Use sign text for surroundings, a block ID for kinetic_sources, or search words for knowledge. Exploration accepts discoveries (default), biomes, biome_tags, structures, pending, run:<id> or returned details_focus."},
                                "detail":{"type":["string","null"],"enum":["names","summary",null],"description":"Abilities index only: names (default) returns all IDs; summary adds descriptions for all. Omit query/focus. Use focus for parameters."},
                                "resource_uri":{"type":["string","null"],"maxLength":2048,"description":"Read a returned resource_uri, details_uri or next_uri. Selects knowledge. Receipt pages are frozen and temporary; unread contents remain unknown."},
                                 "task_id":{"type":["string","null"],"pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$","description":"Follow this task plus important body events in attention. tasks gives its summary; use task with get and path for evidence."},
                                 "stream_id":{"type":["string","null"],"pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$","description":"Attention only: copy from next_attention to detect restart or world change."},
                                 "after_cursor":{"type":"integer","minimum":0,"maximum":9007199254740991,"default":0,"description":"For attention, copy cursor from the response, never latest_cursor. Reuse next_attention to page safely."},
                                 "wait_ms":{"type":"integer","minimum":0,"maximum":60000,"default":0,"description":"For attention, usually use 30000. Returns early on completion, decisions, pauses, missing tasks or resync. Timeout leaves the task running."},
                                 "limit":{"type":"integer","minimum":1,"maximum":20,"description":"Ignored by abilities (always complete). Defaults to 5; web_knowledge defaults to 3 and accepts 1..5."},
                                 "offset":{"type":"integer","minimum":0,"default":0,"description":"Copy next_offset for exploration/tasks pages. Abilities has no pagination."},
                                "server_id":{"type":"string","minLength":1,"maxLength":128,"default":"minecraft-server"}
                              },
                              "additionalProperties":false
                            }
                            """), annotations(true, false, true)),
            tool(PLAN,
                    // 先设计、再提交施工：用场地回执绑定自己的蓝图，待计划就绪后执行；读取旧计划的方式由 plan_id 说明。
                    "Plan a goal without starting it. For build_machine, include your blueprint and the target and snapshot_id from construction_site. Call execute with plan_id when ready_to_execute.",
                    goalSchema("""
                            {
                              "type":"object",
                              "properties": {
                                "goal":{"$ref":"#/$defs/goal"},
                                "plan_id":{"type":"string","description":"Read a saved plan instead of planning a new goal."},
                                "path":{"type":"string","maxLength":1024,"description":"With plan_id, read saved input at this JSON Pointer. Use an empty string to list fields."},
                                "offset":{"type":"integer","minimum":0,"default":0},
                                "limit":{"type":"integer","minimum":1,"maximum":20,"default":5},
                                "server_id":{"type":"string","minLength":1,"maxLength":128,"default":"minecraft-server"}
                              },
                              "additionalProperties":false
                            }
                            """), annotations(false, false, false)),
            tool(EXECUTE,
                    // 登记任务后仍要等待原生动作的实际结果；相同请求复用幂等键，避免网络重试让角色重复施工。
                    "Start a goal or saved plan in the background. accepted means registered. Reuse request_key for an identical retry; follow next_attention until completion, a decision or a pause.",
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
                    // 查看状态、读取旧证据和处理待答问题分清用途；恢复参数留在这里说明，防止把查历史误当成重新执行。
                    "Check or control a task. To answer a decision, copy decision_id and a listed choice. retry can change details.parameters; recover or replace_goal needs details.goal. Then follow next_attention.",
                    goalSchema("""
                            {
                              "type":"object",
                              "properties": {
                                "action":{"type":"string","enum":["get","list","pause","resume","cancel","answer"]},
                                 "task_id":{"type":["string","null"],"pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"},
                                 "request_key":{"type":["string","null"],"minLength":1,"maxLength":128,"description":"If execute's reply was lost, use action=list with the same request_key to find the accepted task."},
                                 "path":{"type":"string","maxLength":1024,"description":"With get, omit for current status. To read saved evidence, copy detail_path (a JSON Pointer); an empty string lists fields. Reading history never repeats actions."},
                                 "offset":{"type":"integer","minimum":0,"default":0,"description":"Copy next_offset to continue a task list or evidence page."},
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
                                "limit":{"type":"integer","minimum":1,"maximum":50,"default":5},
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
        only(value, "view", "focus", "query", "resource_uri", "task_id", "stream_id", "after_cursor", "wait_ms", "limit", "label", "radius",
                "sections", "offset", "server_id", "url", "source", "language", "subject_id", "detail");
        // 已选中文档 URI 就直接读知识；调用者明确指定的其他视图仍会校验，避免先做一次无关身体观察。
        defaults(value, "view", present(value, "url") ? WebKnowledgeService.VIEW : present(value, "resource_uri") ? "knowledge" : "situation");
        String view = string(value, "view", 1, 32, false);
        defaults(value, "after_cursor", 0, "wait_ms", 0, "limit", WebKnowledgeService.VIEW.equals(view) ? 3 : 5,
                "offset", 0, "server_id", "minecraft-server");
        if (!VIEWS.contains(view)) throw bad("view has an unsupported value");
        // 选动作先读完整名称表；概要目录显式选择，两层都不能因数量上限藏掉后面的施工或交互能力。
        boolean abilityIndex = "abilities".equals(view) && !present(value, "query") && !present(value, "focus");
        if (present(value, "detail")) {
            if (!abilityIndex) throw bad("detail is only supported by the abilities index without query or focus");
            if (!Set.of("names", "summary").contains(string(value, "detail", 1, 16, false)))
                throw bad("detail must be names or summary");
        } else if (abilityIndex) value.addProperty("detail", "names");
        // 查外部机制沿资料线程读取，专用参数不能混入身体观察或原生配方查询。
        if (WebKnowledgeService.VIEW.equals(view)) {
            if (present(value, "focus")) throw bad("web_knowledge uses query or url, not focus");
            WebKnowledgeService.validate(value);
        } else if (present(value, "url") || present(value, "source") || present(value, "language") || present(value, "subject_id"))
            throw bad("url/source/language/subject_id are only supported by web_knowledge");
        // 场地参数只服务这一次有界观察，不悄悄改变其他视图的范围或过滤语义。
        if ("construction_site".equals(view)) {
            defaults(value, "radius", 8);
            integer(value, "radius", 1, 8);
            nullableString(value, "label", 1, 160);
            if (present(value, "label") && value.get("label").getAsString().isBlank()) throw bad("label must not be blank");
            if (present(value, "focus")) throw bad("construction_site uses label and radius, not focus");
        } else if ("kinetic_sources".equals(view)) {
            // 动力检索与建造几何分开，水平扩大不会同时向地底扩大；观察只能命名真实可见出口。
            defaults(value, "radius", 32); integer(value, "radius", 8, 64);
            if (present(value, "label")) throw bad("kinetic_sources returns observed source labels; it does not accept an invented label");
        } else if (present(value, "label") || present(value, "radius")) throw bad("radius is only supported by construction_site and kinetic_sources; label is construction_site only");
        // 模糊发现与精确读取明确分开，不能把产品名传给只接受能力标识的 focus。
        nullableString(value, "query", 1, 256);
        if (present(value, "query")) {
            if (!Set.of("knowledge", "web_knowledge", "abilities", "kinetic_sources", "exploration").contains(view)) throw bad("query is only supported by knowledge, web_knowledge, abilities, exploration and kinetic_sources");
            if (value.get("query").getAsString().isBlank()) throw bad("query must contain search keywords");
            if (present(value, "focus") && !"exploration".equals(view) || present(value, "resource_uri")) throw bad("Use query to discover candidates, then focus or resource_uri to read one; do not combine them");
        }
        if ("surroundings".equals(view) || "exploration".equals(view)) nullableString(value, "focus", 1, 128);
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
        int offset = integer(value, "offset", 0, Integer.MAX_VALUE);
        // 能力目录和搜索每次完整交付；非零页码只属于跑图目录或任务列表。
        if (offset != 0 && !"exploration".equals(view) && (!"tasks".equals(view)
                || present(value, "focus") || present(value, "query") || present(value, "task_id")))
            throw bad("offset is only supported by exploration or tasks indexes; abilities are always complete");
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
                    throw bad(PerceiveSections.invalidSection(view, name));
                }
            }
        }
    }

    private static void validatePlan(JsonObject value) {
        // 编译新目标与找回旧设计二选一；读取旧计划只接受定位和分页，不触发再次登记。
        only(value, "goal", "plan_id", "path", "offset", "limit", "server_id");
        defaults(value, "server_id", "minecraft-server", "offset", 0, "limit", 5);
        boolean goal = present(value, "goal"), plan = present(value, "plan_id");
        if (goal == plan) throw bad("exactly one of goal and plan_id is required");
        if (goal) validateGoal(object(value, "goal"), 0);
        else nullableUuid(value, "plan_id");
        int offset = integer(value, "offset", 0, Integer.MAX_VALUE);
        integer(value, "limit", 1, 20);
        if (present(value, "path")) {
            String path = string(value, "path", 0, 1024, false);
            if (!plan || !path.isEmpty() && !path.startsWith("/")) throw bad("path requires plan_id and a JSON Pointer");
        } else if (offset != 0) throw bad("offset requires a plan detail path");
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
        only(value, "action", "task_id", "request_key", "answer", "limit", "offset", "path", "server_id");
        defaults(value, "limit", 5, "offset", 0, "server_id", "minecraft-server");
        String action = string(value, "action", 1, 16, false);
        if (!TASK_ACTIONS.contains(action)) throw bad("action has an unsupported value");
        integer(value, "limit", 1, 50);
        string(value, "server_id", 1, 128, false);
        boolean hasTask = present(value, "task_id");
        boolean hasRequestKey = present(value, "request_key");
        boolean hasAnswer = present(value, "answer");
        // 读取历史只允许 get 指定路径；暂停、重试等控制请求不能夹带会被忽略的分页参数。
        integer(value, "offset", 0, Integer.MAX_VALUE);
        boolean hasPath = present(value, "path");
        if (hasPath) {
            String path = string(value, "path", 0, 1024, false);
            if (!action.equals("get") || !path.isEmpty() && !path.startsWith("/")) throw bad("path requires get and a JSON Pointer");
        }
        if (value.get("offset").getAsInt() != 0 && !action.equals("list") && !(action.equals("get") && hasPath))
            throw bad("offset requires list or get with path");
        if ("list".equals(action)) {
            if (hasTask || hasAnswer) throw bad("list does not accept task_id or answer");
            if (hasRequestKey && value.get("offset").getAsInt() != 0) throw bad("request_key lookup has no offset");
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
        only(goal, "ability", "outcome", "target", "parameters", "preferences", "constraints", "children", "on_failure");
        defaults(goal, "parameters", new JsonObject(), "preferences", new JsonObject(),
                "constraints", new JsonArray(), "children", new JsonArray(), "on_failure", "stop");
        String ability = string(goal, "ability", 1, 256, false);
        resource(ability, "ability");
        // 目标描述填错时直接指出字段位置和类型，避免模型为修正请求外壳重新查询整本能力目录。
        try { string(goal, "outcome", 1, 500, false); }
        catch (IllegalArgumentException invalid) {
            throw bad("goal.outcome must be a non-empty string (1..500 characters) describing the requested result; "
                    + "keep it inside goal, not beside goal: " + invalid.getMessage());
        }
        if (present(goal, "target")) validateTarget(object(goal, "target"));
        object(goal, "parameters");
        // 这里仅确认 parameters 是对象，不检查里面 count 等各能力参数的整数类型或数值范围。
        object(goal, "preferences");
        // 取值在入口就给明确拒绝；挂载位置（仅 sequence 直接子级）由语义契约检查。
        if (present(goal, "on_failure")) {
            String onFailure = string(goal, "on_failure", 1, 16, false);
            if (!onFailure.equals("stop") && !onFailure.equals("continue"))
                throw bad("goal.on_failure accepts \"stop\" (default) or \"continue\"");
        }
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
            // 运行时级死亡自恢复授权键不是工序参数，sequence 同样接受；语义契约层已放行同一份名单，
            // 这里不放行会让契约文字"any ability"与入口校验自相矛盾。生效范围是整个序列执行期。
            Set<String> allowedParameters = new HashSet<>(List.of("protected_labels"));
            allowedParameters.addAll(IntentRuntime.runtimeAuthorizationKeys());
            only(parameters, allowedParameters.toArray(new String[0]));
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

    private static JsonObject perceiveSchema(String json) {
        // 字段清单由感知校验器提供，公开给模型的说明与运行时受理条件一起更新。
        JsonObject result = schema(json);
        result.getAsJsonObject("properties").add(PerceiveSections.SECTIONS, PerceiveSections.schema());
        // Claude Code 会整项过滤带顶层组合条件的工具；公开普通对象，角色勘测的分视图范围由 validatePerceive 校验。
        // radius 的字段说明保留各自界限，避免施工入口因描述搜索半径而从模型的工具列表里消失。
        return result;
    }

    private static JsonObject goalSchema(String json) {
        // 宿主发现规划、执行和恢复入口时直接看到对象字段，不必先解析引用才能提交游戏目标。
        return inlineGoalSchema(schema(json), Set.of()).getAsJsonObject();
    }

    private static JsonElement inlineGoalSchema(JsonElement value, Set<String> ancestors) {
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            value.getAsJsonArray().forEach(item -> result.add(inlineGoalSchema(item, ancestors)));
            return result;
        }
        if (!value.isJsonObject()) return value.deepCopy();
        JsonObject source = value.getAsJsonObject();
        if (source.has("$ref")) {
            String reference = source.get("$ref").getAsString();
            JsonObject definition = GOAL_DEFINITIONS.getAsJsonObject(reference.substring("#/$defs/".length()));
            if (ancestors.contains(reference)) {
                // 顺序任务仍可递归提交；发现阶段只提示子目标形状，避免无限展开，逐层规则由 validateGoal 执行。
                JsonObject child = new JsonObject();
                child.add("type", definition.get("type").deepCopy());
                child.add("required", definition.get("required").deepCopy());
                // 必填字段同时列入 properties，宿主可直接填写能力和结果，其余字段沿用父目标的规则。
                JsonObject fields = new JsonObject();
                definition.getAsJsonArray("required").forEach(key -> fields.add(key.getAsString(),
                        definition.getAsJsonObject("properties").get(key.getAsString()).deepCopy()));
                child.add("properties", fields);
                child.addProperty("additionalProperties", true);
                // 顺序施工的子目标沿用父目标字段，不再逐个重列；仍明示逐层校验和递归深度，避免漏掉执行限制。
                child.addProperty("description", "Use the same fields as the parent goal. Every level is validated; nest at most 32 levels.");
                return child;
            }
            Set<String> path = new HashSet<>(ancestors); path.add(reference);
            return inlineGoalSchema(definition, path);
        }
        JsonObject result = new JsonObject();
        source.entrySet().forEach(entry -> result.add(entry.getKey(), inlineGoalSchema(entry.getValue(), ancestors)));
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
