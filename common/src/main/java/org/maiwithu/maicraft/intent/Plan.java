package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable compiled semantic plan. It contains goals, never body-control micro-steps. */
public record Plan(UUID id, Goal goal, List<Goal> steps, long createdGameTime) {
    public Plan {
        id = Objects.requireNonNull(id, "id");
        goal = Objects.requireNonNull(goal, "goal");
        steps = List.copyOf(steps);
    }

    public static Plan compile(Goal goal, long gameTime) {
        return new Plan(UUID.randomUUID(), goal, goal.executableSteps(), gameTime);
    }

    public JsonObject toJson() {
        JsonObject result = new JsonObject();
        result.addProperty("plan_id", id.toString());
        result.add("goal", goal.toJson());
        JsonArray summaries = new JsonArray();
        for (int i = 0; i < steps.size(); i++) {
            Goal step = steps.get(i);
            JsonObject summary = new JsonObject();
            summary.addProperty("index", i);
            summary.addProperty("ability", step.ability());
            summary.addProperty("outcome", step.outcome());
            summaries.add(summary);
        }
        result.add("steps", summaries);
        result.addProperty("step_count", steps.size());
        return result;
    }
}
