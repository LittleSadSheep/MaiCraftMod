// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.integration.machine.MachineBlueprintDocument;
import org.maiwithu.maicraft.core.integration.machine.MachineConstructionPlan;
import org.maiwithu.maicraft.core.integration.machine.MachineDesignRejection;
import org.maiwithu.maicraft.core.integration.ponder.PonderBlueprintStore;

/** plan 直接检查显式机器蓝图；不领取材料、不占用角色，也不把尚未供电当作设计失败。 */
public final class MachinePlanPreflight {
    private MachinePlanPreflight() {}

    public static JsonObject review(Goal goal) {
        return review(goal, null, null);
    }

    /** 在线规划同时核对场地身份但不消费回执，随后 execute 仍独立检查实际开工条件。 */
    public static JsonObject review(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        // 先沿用目标契约限制字段、组合深度和蓝图大小，再做原生编译，错误请求不能绕过原有入口检查。
        SemanticGoalContract.validate(goal, IntentRuntime.KNOWN_ABILITIES);
        JsonArray checks = new JsonArray();
        boolean valid = true;
        List<Goal> steps = goal.executableSteps();
        for (int index = 0; index < steps.size(); index++) {
            Goal step = steps.get(index);
            JsonObject parameters = step.parameters();
            if (!step.ability().equals(MachineAbilityAdapter.BUILD)
                    || !parameters.has("blueprint") && !parameters.has("blueprint_uri")) continue;
            JsonObject check = new JsonObject();
            check.addProperty("step_index", index);
            check.addProperty("ability", step.ability());
            String issuePath = "goal.parameters.blueprint";
            try {
                JsonObject blueprint = parameters.has("blueprint") ? parameters.getAsJsonObject("blueprint")
                        : PonderBlueprintStore.resolve(parameters.get("blueprint_uri").getAsString());
                var layout = MachineConstructionPlan.reviewExplicit(
                        MachineBlueprintDocument.compile(blueprint, MachineConstructionPlan.registry()));
                valid &= layout.buildable();
                check.addProperty("valid", layout.buildable());
                // 返回诊断、材料清单与动力接口即可决定下一步；逐格放置依赖留给 Mod，避免再抄整张图。
                for (String field : List.of("validation", "native_material_counts", "power_ports", "item_handoffs",
                        "physical_layout_compiled", "native_installation_validated", "physical_target_count",
                        "site_and_material_preflight_pending", "external_inputs"))
                    if (layout.report().has(field)) check.add(field, layout.report().get(field).deepCopy());
                if (layout.buildable() && player != null) {
                    issuePath = "goal.parameters.snapshot_id";
                    MachineAbilityAdapter.boundSnapshot(step, player, runtime, true);
                    check.addProperty("site_anchor_verified", true);
                }
            } catch (IllegalArgumentException invalid) {
                // 错误蓝图就地返回可修订的诊断，尚未登记可执行计划，更不会开始角色动作。
                valid = false;
                check.addProperty("valid", false);
                JsonArray issues = new JsonArray();
                issues.add(MachineDesignRejection.issue(invalid.getMessage(), issuePath));
                check.add("issues", issues);
            }
            checks.add(check);
        }
        JsonObject result = new JsonObject();
        result.addProperty("valid", valid);
        result.add("checks", checks);
        result.addProperty("scope", "Declared machine blueprints and native installation only; site, material supply and actual production are checked during execution. Other goal types retain their own execution checks.");
        return result;
    }
}
