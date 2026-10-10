// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 改机器设置的能力：改一台机器的设置项——侧面配置、过滤规则、样板这一类。
 *
 * <p>设置项有哪些、值怎么写，由登记了的机器类型自报（lookup 里按已装的模组列出）；
 * 这里的参数只是把"要改成什么"带进游戏。认不出的机器以不支持结束，不猜它有什么设置。
 */
final class MachineConfigureModule implements AbilityModule {

    private final MachineServices services;
    private final AbilitySpec spec = new AbilitySpec(
            ModIdentity.MOD_ID + ":machine_configure",
            "改一台机器的设置：侧面、过滤、模式、样板（按已装模组的机器类型）",
            AbilityDoc.forAbility("machine_configure"),
            ParamSpecs.of(
                    ParamSpec.of("settings", ParamType.JSON_OBJECT).required()
                            .doc("要改成什么：键是这台机器认的设置项，值写字符串，"
                                    + "例如 {\"side.north\": \"output:items\"}；能设哪些项先 lookup 查这台机器").build()),
            Set.of(TargetKind.HERE, TargetKind.SEEN, TargetKind.LANDMARK, TargetKind.POSITION),
            ExecutionMode.CONTROLS_PLAYER,
            Set.of(),
            List.of(),
            Listing.LISTED);

    MachineConfigureModule(MachineServices services) {
        this.services = services;
    }

    @Override public AbilitySpec spec() {
        return spec;
    }

    // 设置正文的形状在这里一次核完：必须是对象、每项的值要能当成一个字符串；键认不认得到现场问机器。
    @Override public StepDecision decide(StepContext step) {
        JsonObject raw = step.goal().params().has("settings")
                ? step.goal().params().json("settings").getAsJsonObject()
                : new JsonObject();
        Map<String, String> settings = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : raw.entrySet()) {
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive()) {
                return new StepDecision.Finish(TaskResult.builder(TaskResult.Status.FAILED, "设置写法不对")
                        .problem(Problem.of(Problem.Kind.INVALID_PARAMETER,
                                "设置「" + entry.getKey() + "」的值要写字符串，现在给的是别的形状",
                                "值统一写成字符串；具体的写法在 lookup 的机器说明里")).build());
            }
            settings.put(entry.getKey(), value.getAsString());
        }
        if (settings.isEmpty()) {
            return new StepDecision.Finish(TaskResult.builder(TaskResult.Status.FAILED, "没有要改的设置")
                    .problem(Problem.of(Problem.Kind.INVALID_PARAMETER,
                            "settings 要是至少一项的对象，例如 {\"side.north\": \"output:items\"}")).build());
        }
        return new StepDecision.Run(new MachineConfigureInput(step.goal().target(), settings,
                step.goal().permissions()));
    }

    @Override public void registerTasks(TaskFactories factories) {
        factories.register(MachineConfigureInput.class, input -> new MachineConfigureTask(input, services));
    }
}
