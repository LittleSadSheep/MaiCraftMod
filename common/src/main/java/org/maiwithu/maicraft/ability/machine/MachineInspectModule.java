// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 机器查看能力：看一台机器或一片机器区——由哪些机器组成、各自的状态、挂在哪些网络上。只读分析。
 *
 * <p>不打开界面看机器内部（那归 use 与 deposit/obtain），不预测机器能不能转；
 * 转不转以每台的运行状态为准，一起给在结果里。范围没给就是脚下这一片。
 */
final class MachineInspectModule implements AbilityModule {

    private final MachineServices services;
    private final MachineArchives archives;
    private final MachineVehicles vehicles;
    private final AbilitySpec spec = new AbilitySpec(
            ModIdentity.MOD_ID + ":machine_inspect",
            "看一台机器或一片机器区：组成、运行状态、进出口、挂在哪些网络上；指档案名时再和档案整机比对（只读）",
            AbilityDoc.forAbility("machine_inspect"),
            ParamSpecs.of(
                    ParamSpec.of("radius", ParamType.INTEGER).range(1, 32).defaultValue(16)
                            .doc("看多大范围，单位格；给了目标就是以它为中心，没给以角色为中心").build(),
                    ParamSpec.of("item", ParamType.ITEM_OR_TAG)
                            .doc("看身上物品自己的设置（过滤器的规则）时写物品 ID：每一件分别列出在哪一格、现在是什么规则").build()),
            Set.of(TargetKind.HERE, TargetKind.SEEN, TargetKind.LANDMARK, TargetKind.POSITION),
            ExecutionMode.READ_ONLY,
            Set.of(),
            List.of(),
            Listing.LISTED);

    MachineInspectModule(MachineServices services, MachineArchives archives, MachineVehicles vehicles) {
        this.services = services;
        this.archives = archives;
        this.vehicles = vehicles;
    }

    @Override public AbilitySpec spec() {
        return spec;
    }

    // 参数只有 radius，范围在参数规格里已经校过；目标是档案名就带上档案，看它的范围并做整机比对。
    @Override public StepDecision decide(StepContext step) {
        // 给了 item：看身上那种物品每一件自己的设置，不看机器。
        if (step.goal().params().has("item")) {
            return new StepDecision.Run(new ItemSettingsTask.Input(step.goal().params().text("item").toLowerCase(Locale.ROOT),
                    null, Map.of(), step.goal().permissions()));
        }
        // 指结构的观察编号 s#：载具也是机器，看它的 vehicle 一节（座位、回路、姿态），不扫世界里的方块。
        if (step.goal().target() instanceof Target.Seen seen && seen.id().startsWith("s")) {
            Optional<String> structure = vehicles.structureOf().apply(seen.id());
            if (structure.isEmpty()) {
                return new StepDecision.Finish(TaskResult.failed("指的结构不在了", Problem.of(Problem.Kind.TARGET_GONE,
                        "观察编号 " + seen.id() + " 指的结构已经不在了", "重新 observe 再指")));
            }
            return new StepDecision.Run(new VehicleInspectTask.Input(structure.get(), seen.id()));
        }
        int radius = (int) (step.goal().params().has("radius") ? step.goal().params().integer("radius") : 16);
        MachineArchive archive = step.goal().target() instanceof Target.Landmark landmark
                ? archives.find(landmark.name()).filter(found -> !found.removed()).orElse(null)
                : null;
        return new StepDecision.Run(new MachineInspectInput(step.goal().target(), radius, archive));
    }

    @Override public void registerTasks(TaskFactories factories) {
        factories.register(MachineInspectInput.class, input -> new MachineInspectTask(input, services));
        factories.register(VehicleInspectTask.Input.class, input -> new VehicleInspectTask(input, vehicles.readers()));
    }
}
