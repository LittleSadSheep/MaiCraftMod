// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.core.integration.machine.MachineBlueprintDocument;

/** 合法蓝图本来就有方块坐标，检查目标时不能把这些坐标误当成鼠标脚本一概拒绝。 */
public final class BlueprintGoalData {
    private BlueprintGoalData() {}

    /** 复制一份目标，只从检查用的副本中移走已验证蓝图；原目标仍保留完整方块数据。 */
    public static JsonObject instructionView(Goal goal) {
        JsonObject view = goal.toJson();
        stripValidatedBlueprints(goal, view);
        return view;
    }

    private static void stripValidatedBlueprints(Goal goal, JsonObject view) {
        // 只有明确声明支持蓝图的机器能力才适用例外；其他地方塞入同名字段，仍要经过普通检查。
        JsonObject parameters = view.getAsJsonObject("parameters");
        if (BuildingSceneContract.supports(goal)) {
            BuildingSceneContract.validate(goal);
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
            MachineBlueprintDocument.validateWire(blueprint.getAsJsonObject());
            // 先证明是合法蓝图，再从普通脚本字段检查中排除它，不能靠起名 blueprint 绕过验证。
            parameters.remove("blueprint");
        }
        boolean productionDeclared = MachineAbilityAdapter.DESIGN.equals(goal.ability())
                || MachineAbilityAdapter.BUILD.equals(goal.ability())
                || MachineAbilityAdapter.OPERATE.equals(goal.ability()) && operation != null
                    && operation.isJsonPrimitive() && operation.getAsJsonPrimitive().isString()
                    && java.util.Set.of("run_production","watch_production").contains(operation.getAsString());
        if (productionDeclared && parameters.has("production")) {
            // Anchored ports and paths are typed design data. Their strict parser still rejects slot/click scripts.
            MachineProductionIntent.validate(parameters);
            parameters.remove("production");
        }
        for (int i = 0; i < goal.children().size(); i++)
            stripValidatedBlueprints(goal.children().get(i), view.getAsJsonArray("children").get(i).getAsJsonObject());
    }
}
