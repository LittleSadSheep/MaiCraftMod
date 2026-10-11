// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.Locale;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

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
 * 用机器做东西的能力：备料、投料、开机、等产物、收产物，一台机器一次。
 *
 * <p>只保证"这台机器这次做了几件"；一条生产线上有几台机器，LLM 用 sequence 排。
 * 机器能不能转这里不判：读数随结果给出，转不起来 LLM 看得到为什么。
 */
final class MachineRunModule implements AbilityModule {

    private final MachineServices services;
    private final AbilitySpec spec = new AbilitySpec(
            ModIdentity.MOD_ID + ":machine_run",
            "用一台机器做东西：备料、投料、开机、等产物、收产物",
            AbilityDoc.forAbility("machine_run"),
            ParamSpecs.of(
                    ParamSpec.of("item", ParamType.ITEM_OR_TAG)
                            .doc("要做出什么；不给就只开机").build(),
                    ParamSpec.of("count", ParamType.INTEGER).range(1, 64).defaultValue(1)
                            .doc("要做几件").build(),
                    ParamSpec.of("collect", ParamType.BOOLEAN).defaultValue(true)
                            .doc("做完要不要把出口的东西拿进背包；不要就留在出口").build(),
                    ParamSpec.of("max_seconds", ParamType.INTEGER).range(1, 3600).defaultValue(300)
                            .doc("最多等产物多久；到点没出够按部分完成收场").build(),
                    ParamSpec.of("file", ParamType.TEXT)
                            .doc("蓝图桌、蓝图加农炮用：schematics 目录下的 .nbt 蓝图文件名（和 build 的 file 同一个目录）").build(),
                    ParamSpec.of("anchor", ParamType.JSON_OBJECT)
                            .doc("蓝图加农炮用：蓝图原点落在哪一格 {\"x\":..,\"y\":..,\"z\":..}；配 file").build(),
                    ParamSpec.of("rotation", ParamType.INTEGER).range(0, 270).defaultValue(0)
                            .doc("蓝图加农炮用：蓝图绕原点转 0 / 90 / 180 / 270 度；配 anchor").build()),
            Set.of(TargetKind.HERE, TargetKind.SEEN, TargetKind.LANDMARK, TargetKind.POSITION),
            ExecutionMode.CONTROLS_PLAYER,
            Set.of(),
            List.of(),
            Listing.LISTED);

    MachineRunModule(MachineServices services) {
        this.services = services;
    }

    @Override public AbilitySpec spec() {
        return spec;
    }

    // 参数在参数规格里已校过；item 统一小写；蓝图的三个参数互相搭配，写错的当场说清，不进游戏。
    @Override public StepDecision decide(StepContext step) {
        var params = step.goal().params();
        String item = params.has("item") ? params.text("item").toLowerCase(Locale.ROOT) : null;
        int count = (int) (params.has("count") ? params.integer("count") : 1);
        boolean collect = !params.has("collect") || params.bool("collect");
        int maxSeconds = (int) (params.has("max_seconds") ? params.integer("max_seconds") : 300);
        String file = params.has("file") ? params.text("file") : null;
        int rotation = (int) (params.has("rotation") ? params.integer("rotation") : 0);
        if (params.has("anchor") && file == null) return invalid("anchor 要配 file：蓝图原点是给哪份蓝图的");
        if (params.has("rotation") && rotation != 0 && !params.has("anchor")) return invalid("rotation 要配 anchor");
        if (rotation % 90 != 0) return invalid("rotation 只能是 0、90、180、270");
        BlockPos anchor = null;
        if (params.has("anchor")) {
            anchor = position(params.json("anchor"));
            if (anchor == null) return invalid("anchor 写成 {\"x\":整数,\"y\":整数,\"z\":整数}");
        }
        return new StepDecision.Run(new MachineRunInput(step.goal().target(), item, count, collect,
                maxSeconds, step.goal().permissions(), file, anchor, rotation));
    }

    // {"x":..,"y":..,"z":..} 落成一格；缺哪个、不是整数都给 null。
    private static BlockPos position(JsonElement json) {
        if (json == null || !json.isJsonObject()) return null;
        JsonObject object = json.getAsJsonObject();
        int[] xyz = new int[3];
        String[] names = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            JsonElement value = object.get(names[i]);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
            double number = value.getAsDouble();
            if (number != Math.rint(number)) return null;
            xyz[i] = (int) number;
        }
        return new BlockPos(xyz[0], xyz[1], xyz[2]);
    }

    private static StepDecision invalid(String message) {
        return new StepDecision.Finish(TaskResult.failed(message, Problem.of(Problem.Kind.INVALID_PARAMETER, message, null)));
    }

    @Override public void registerTasks(TaskFactories factories) {
        factories.register(MachineRunInput.class, input -> new MachineRunTask(input, services));
    }
}
