package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.Plan;

/** 组合计划无需在接单前复印全部输入；遗忘设计时只读原计划，不能把旧审阅当成当前施工许可。 */
public final class PlanViewTest {
    public static void main(String[] args) {
        List<Goal> children = new ArrayList<>();
        for (int i = 0; i < 32; i++) children.add(new Goal("maicraft:travel", "前往仓库 " + i,
                new Goal.SemanticTarget("landmark", "仓库 " + i, null, null), "{}", "{}", List.of(), List.of()));
        Goal goal = new Goal("maicraft:sequence", "依次巡视仓库", null, "{}", "{}", List.of(), children);
        Plan plan = Plan.compile(goal, 20); JsonObject summary = PlanView.summary(plan, 3);
        // 摘要列出每一步的身份（index/ability/outcome），但不复印目标参数等编排输入。
        check(!summary.has("goal") && summary.getAsJsonArray("steps").size() == 32
                && summary.get("step_count").getAsInt() == 32, "plan does not echo authored inputs");
        JsonObject request = new JsonObject(); request.addProperty("plan_id", plan.id().toString());
        request.addProperty("path", "/goal/children"); request.addProperty("limit", 3);
        JsonObject read = PlanView.read(plan, PublicToolCatalog.validateAndNormalize("plan", request));
        check(!read.get("validation_rechecked").getAsBoolean() && !read.has("ready_to_execute")
                && read.getAsJsonObject("detail").getAsJsonArray("value").size() == 32,
                "read only restores frozen design at offset zero");
        request.addProperty("offset", 3);
        read = PlanView.read(plan, PublicToolCatalog.validateAndNormalize("plan", request));
        check(read.getAsJsonObject("detail").get("next_offset").getAsInt() == 6,
                "paged reads continue from the requested offset");
        check(plan.goal().toJson().equals(goal.toJson()), "reading does not change the compiled goal");
        request.add("goal", goal.toJson());
        try { PublicToolCatalog.validateAndNormalize("plan", request); throw new AssertionError("ambiguous compile/read accepted"); }
        catch (IllegalArgumentException expected) { /* 只读恢复不能夹带一个新目标，避免意外登记另一份计划。 */ }
        System.out.println("PlanViewTest: full=" + plan.toJson().toString().length() + "; summary=" + summary.toString().length() + "; passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
