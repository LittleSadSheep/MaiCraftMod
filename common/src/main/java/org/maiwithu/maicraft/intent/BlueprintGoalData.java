// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;
import org.maiwithu.maicraft.core.integration.machine.MachineBlueprintDocument;

/** 合法蓝图本来就有方块坐标，检查目标时不能把这些坐标误当成鼠标脚本一概拒绝。 */
public final class BlueprintGoalData {
    private BlueprintGoalData() {}

    /** 复制一份目标，只从检查用的副本中移走已验证蓝图；原目标仍保留完整方块数据。 */
    public static JsonObject instructionView(Goal goal) {
        return instructionView(goal, true);
    }

    /**
     * enforceCurrentRules=false 供恢复旧检查点使用：只按保存时的同形规则复制视图，
     * 不再拿收紧后的预算与政策裁决历史数据——运行时门禁只约束新提交。
     */
    public static JsonObject instructionView(Goal goal, boolean enforceCurrentRules) {
        JsonObject view = goal.toJson();
        stripValidatedBlueprints(goal, view, enforceCurrentRules);
        return view;
    }

    private static void stripValidatedBlueprints(Goal goal, JsonObject view, boolean enforceCurrentRules) {
        // 只有明确声明支持蓝图的机器能力才适用例外；其他地方塞入同名字段，仍要经过普通检查。
        JsonObject parameters = view.getAsJsonObject("parameters");
        if (BuildingSceneContract.supports(goal)) {
            if (enforceCurrentRules) BuildingSceneContract.validate(goal);
            parameters.remove("scene");
            parameters.remove("edits");
            parameters.remove("blueprint");
        }
        JsonElement operation = parameters.get("operation");
        boolean declared = MachineAbilityAdapter.DESIGN.equals(goal.ability())
                || MachineAbilityAdapter.BUILD.equals(goal.ability())
                || MachineAbilityAdapter.MODIFY.equals(goal.ability()) && operation != null
                    && operation.isJsonPrimitive() && operation.getAsJsonPrimitive().isString()
                    && "apply_blueprint".equals(operation.getAsString());
        if (declared && parameters.has("blueprint")) {
            JsonElement blueprint = parameters.get("blueprint");
            if (!blueprint.isJsonObject()) throw new IllegalArgumentException("blueprint must be an object");
            if (enforceCurrentRules) MachineBlueprintDocument.validateWire(blueprint.getAsJsonObject());
            // 先证明是合法蓝图，再从普通脚本字段检查中排除它，不能靠起名 blueprint 绕过验证。
            parameters.remove("blueprint");
        }
        boolean productionDeclared = MachineAbilityAdapter.DESIGN.equals(goal.ability())
                || MachineAbilityAdapter.BUILD.equals(goal.ability())
                || MachineAbilityAdapter.OPERATE.equals(goal.ability()) && operation != null
                    && operation.isJsonPrimitive() && operation.getAsJsonPrimitive().isString()
                    && Set.of("run_production","watch_production").contains(operation.getAsString());
        if (productionDeclared && parameters.has("production")) {
            // 锚定接口和路线属于明确的机器设计数据，严格解析仍会拒绝槽位点击脚本。
            if (enforceCurrentRules) MachineProductionIntent.validate(parameters);
            parameters.remove("production");
        }
        for (int i = 0; i < goal.children().size(); i++)
            stripValidatedBlueprints(goal.children().get(i),
                    view.getAsJsonArray("children").get(i).getAsJsonObject(), enforceCurrentRules);
    }
}
