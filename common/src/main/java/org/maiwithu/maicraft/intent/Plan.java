package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 不可变的业务步骤清单。compile 只整理 Goal 中的可执行步骤并生成计划 ID，
 * 不在这里调用模型、搜索路径或预先决定按键；执行时才结合世界状态生成具体操作。
 */
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
