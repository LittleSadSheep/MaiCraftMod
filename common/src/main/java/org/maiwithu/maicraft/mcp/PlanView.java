package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.intent.Plan;

/** 编译回执只说明要做什么和是否通过审阅；上下文丢失后按 plan_id 找回原设计，无需再提交蓝图。 */
final class PlanView {
    private PlanView() {}

    static JsonObject summary(Plan plan, int limit) {
        JsonObject result = new JsonObject(); result.addProperty("plan_id", plan.id().toString());
        result.addProperty("ability", plan.goal().ability()); result.addProperty("outcome", plan.goal().outcome());
        result.addProperty("step_count", plan.steps().size());
        // 单步目标已由外层说明；只有组合目标才列少量步骤，完整输入始终留在已登记计划里。
        if (plan.steps().size() > 1) {
            JsonArray steps = new JsonArray();
            for (int i = 0; i < Math.min(plan.steps().size(), limit); i++) {
                JsonObject step = new JsonObject(); step.addProperty("index", i);
                step.addProperty("ability", plan.steps().get(i).ability()); step.addProperty("outcome", plan.steps().get(i).outcome());
                steps.add(step);
            }
            result.add("steps", steps);
            if (steps.size() < plan.steps().size()) result.addProperty("steps_detail_path", "/steps");
        }
        JsonArray details = new JsonArray(); details.add("/goal"); details.add("/steps"); result.add("detail_paths", details);
        return result;
    }

    static JsonObject read(Plan plan, JsonObject args) {
        JsonObject result = summary(plan, args.get("limit").getAsInt());
        result.addProperty("status", "stored"); result.addProperty("validation_rechecked", false);
        if (args.has("path") && !args.get("path").isJsonNull())
            result.add("detail", JsonReadback.page(plan.toJson(), args.get("path").getAsString(),
                    args.get("offset").getAsInt(), args.get("limit").getAsInt()));
        return result;
    }
}
