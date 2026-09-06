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

/** Defines and validates the complete public MCP tool surface. */
final class PublicToolCatalog {
    static final String PERCEIVE = "maicraft_perceive";
    static final String PLAN = "maicraft_plan";
    static final String EXECUTE = "maicraft_execute";
    static final String TASK = "maicraft_task";

    private static final Pattern RESOURCE_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    );
    private static final Set<String> VIEWS = Set.of(
            "situation", "surroundings", "abilities", "tasks", "attention", "landmarks", "machines", "machine_menu"
    );
    private static final Set<String> TARGET_KINDS = Set.of(
            "current_place", "coordinates", "landmark", "player", "entity", "nearest", "area", "prior_result"
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
                  "parameters":{"type":"object","default":{},"description":"Only keys declared by the selected ability in maicraft_perceive(view=abilities) are accepted."},
                  "preferences":{"type":"object","default":{},"description":"Must be empty unless the selected ability explicitly declares accepted_preferences."},
                  "constraints":{"type":"array","items":{"$ref":"#/$defs/constraint"},"maxItems":32,"default":[],"description":"Only parameter-free hard constraints explicitly declared by the selected ability are accepted."},
                  "children":{"type":"array","items":{"$ref":"#/$defs/goal"},"maxItems":32,"default":[],"description":"Ordered child outcomes; accepted only by maicraft:sequence."}
                },
                "required":["ability","outcome"], "additionalProperties":false
              }
            }
            """).getAsJsonObject();

    private static final List<JsonObject> TOOLS = List.of(
            tool(PERCEIVE,
                    "Read a concise, decision-oriented view of the current game state.",
                    schema("""
                            {
                              "type":"object",
                              "properties": {
                                "view":{"type":"string","enum":["situation","surroundings","abilities","tasks","attention","landmarks","machines","machine_menu"],"default":"situation","description":"landmarks returns durable semantic labels. machines lists cached session observations; inspect_machine gives fresh geometry. machine_menu returns structured current native menu evidence and a receipt for exact entry transfers. Stored absolute coordinates remain private to MaiCraft."},
                                "focus":{"type":["string","null"],"maxLength":256,"description":"Ability filter for abilities; maicraft:navigation or maicraft:transport with situation includes actor, navigation, collision geometry, active transport, Create jetpack and elevator diagnostics; with surroundings, optional literal sign text to find (for example 充气)."},
                                 "task_id":{"type":["string","null"],"pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$","description":"For view=tasks, return one full task when present; otherwise list concise recent task summaries."},
                                 "after_cursor":{"type":"integer","minimum":0,"default":0},
                                 "wait_ms":{"type":"integer","minimum":0,"maximum":60000,"default":0,"description":"For view=attention, wait up to this duration for a newer event without polling raw game state."},
                                 "limit":{"type":"integer","minimum":1,"maximum":20,"default":10},
                                "server_id":{"type":"string","minLength":1,"maxLength":128,"default":"minecraft-server"}
                              },
                              "additionalProperties":false
                            }
                            """), annotations(true, false, true)),
            tool(PLAN,
                    "Compile a semantic goal into a plan without starting it.",
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
                    "Start a goal or a previously compiled plan and return its task identifier immediately.",
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
                    "Inspect or control a runtime task. When state=waiting_for_decision, answer with the exact "
                            + "decision_id and one listed choice. retry may refine details.parameters; recover "
                            + "and replace_goal require one semantic details.goal. Never provide internal tool "
                            + "names, routes, block layouts or click scripts. Machine abilities accept semantic "
                            + "design fields and receipt-bound observed menu entries; MaiCraft compiles exact work.",
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
        JsonArray array = new JsonArray();
        TOOLS.forEach(tool -> array.add(tool.deepCopy()));
        return array;
    }

    static boolean contains(String name) {
        return PERCEIVE.equals(name) || PLAN.equals(name) || EXECUTE.equals(name) || TASK.equals(name);
    }

    static JsonObject validateAndNormalize(String name, JsonElement rawArguments) {
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
        only(value, "view", "focus", "task_id", "after_cursor", "wait_ms", "limit", "server_id");
        defaults(value, "view", "situation", "after_cursor", 0, "wait_ms", 0,
                "limit", 10, "server_id", "minecraft-server");
        String view = string(value, "view", 1, 32, false);
        if (!VIEWS.contains(view)) throw bad("view has an unsupported value");
        if ("surroundings".equals(view)) nullableString(value, "focus", 1, 128);
        else nullableResource(value, "focus");
        nullableUuid(value, "task_id");
        int cursor = integer(value, "after_cursor", 0, Integer.MAX_VALUE);
        int waitMs = integer(value, "wait_ms", 0, 60_000);
        integer(value, "limit", 1, 20);
        string(value, "server_id", 1, 128, false);
        boolean hasTask = present(value, "task_id");
        if (hasTask && !"tasks".equals(view)) {
            throw bad("task_id is only supported by the tasks view");
        }
        if (cursor != 0 && !"attention".equals(view)) {
            throw bad("after_cursor is only supported by the attention view");
        }
        if (waitMs != 0 && !"attention".equals(view)) {
            throw bad("wait_ms is only supported by the attention view");
        }
    }

    private static void validatePlan(JsonObject value) {
        only(value, "goal", "server_id");
        defaults(value, "server_id", "minecraft-server");
        require(value, "goal");
        validateGoal(object(value, "goal"), 0);
        string(value, "server_id", 1, 128, false);
    }

    private static void validateExecute(JsonObject value) {
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
        if (depth > 32) throw bad("goal nesting is too deep");
        only(goal, "ability", "outcome", "target", "parameters", "preferences", "constraints", "children");
        defaults(goal, "parameters", new JsonObject(), "preferences", new JsonObject(),
                "constraints", new JsonArray(), "children", new JsonArray());
        String ability = string(goal, "ability", 1, 256, false);
        resource(ability, "ability");
        string(goal, "outcome", 1, 500, false);
        if (present(goal, "target")) validateTarget(object(goal, "target"));
        object(goal, "parameters");
        object(goal, "preferences");
        JsonArray constraints = array(goal, "constraints", 32);
        constraints.forEach(item -> validateConstraint(asObject(item, "constraint")));
        JsonArray children = array(goal, "children", 32);
        for (JsonElement child : children) validateGoal(asObject(child, "child goal"), depth + 1);
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
        only(target, "kind", "label", "position", "relation");
        String kind = string(target, "kind", 1, 32, false);
        if (!TARGET_KINDS.contains(kind)) throw bad("target kind has an unsupported value");
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
        if ("coordinates".equals(kind) != hasPosition) {
            throw bad("position is required only for a coordinates target");
        }
        boolean hasLabel = present(target, "label");
        if (!Set.of("current_place", "coordinates", "prior_result").contains(kind) && !hasLabel) {
            throw bad("this target kind requires label");
        }
        if ("prior_result".equals(kind) && !present(target, "relation")) {
            throw bad("prior_result requires relation");
        }
    }

    private static void validateConstraint(JsonObject constraint) {
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
        require(object, key);
        JsonElement element = object.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw bad(key + " must be an integer");
        }
        try {
            int value = element.getAsBigDecimal().intValueExact();
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
