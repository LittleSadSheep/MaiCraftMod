package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable semantic intent. Execution details are compiled later inside the game runtime. */
public record Goal(String ability,
                   String outcome,
                   SemanticTarget target,
                   String parametersJson,
                   String preferencesJson,
                   List<Constraint> constraints,
                   List<Goal> children) {

    public Goal {
        ability = Objects.requireNonNull(ability, "ability");
        outcome = Objects.requireNonNull(outcome, "outcome");
        parametersJson = canonicalObject(parametersJson);
        preferencesJson = canonicalObject(preferencesJson);
        constraints = List.copyOf(constraints == null ? List.of() : constraints);
        children = List.copyOf(children == null ? List.of() : children);
    }

    public static Goal fromJson(JsonObject source) {
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
        return JsonParser.parseString(parametersJson).getAsJsonObject();
    }

    public JsonObject preferences() {
        return JsonParser.parseString(preferencesJson).getAsJsonObject();
    }

    public Goal withParameters(JsonObject parameters) {
        return new Goal(ability, outcome, target, parameters.toString(), preferencesJson, constraints, children);
    }

    public Goal withTarget(SemanticTarget nextTarget) {
        return new Goal(ability, outcome, nextTarget, parametersJson, preferencesJson, constraints, children);
    }

    public List<Goal> executableSteps() {
        if ("maicraft:sequence".equals(ability)) {
            List<Goal> flattened = new ArrayList<>();
            for (Goal child : children) {
                flattened.addAll(child.executableSteps());
            }
            return List.copyOf(flattened);
        }
        return List.of(this);
    }

    public JsonObject toJson() {
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
        JsonObject value = source.has(key) && source.get(key).isJsonObject()
                ? source.getAsJsonObject(key)
                : new JsonObject();
        return value.toString();
    }

    private static String canonicalObject(String json) {
        if (json == null || json.isBlank()) return "{}";
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("goal metadata must be an object");
        return parsed.toString();
    }

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
