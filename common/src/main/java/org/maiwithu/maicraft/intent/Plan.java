package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一份带编号的目标清单。这里的“编译计划”只是把组合目标展开成按顺序执行的步骤，
 * 并不提前算路、算材料或证明一定能做成；这些要等真正执行时再结合世界里的情况判断。
 */
public record Plan(UUID id, Goal goal, List<Goal> steps, long createdGameTime) {
    public Plan {
        // 固定清单的编号、目标和步骤，调用者不能拿着原列表改掉已经保存的计划。
        id = Objects.requireNonNull(id, "id");
        goal = Objects.requireNonNull(goal, "goal");
        steps = List.copyOf(steps);
    }

    public static Plan compile(Goal goal, long gameTime) {
        return new Plan(UUID.randomUUID(), goal, goal.executableSteps(), gameTime);
    }

    public JsonObject toJson() {
        // 对外列出各步的编号、能力和目标描述，不公开还不存在的执行路线。
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
