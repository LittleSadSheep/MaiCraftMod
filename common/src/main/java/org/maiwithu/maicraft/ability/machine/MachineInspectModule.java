// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;
import java.util.Set;

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
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 机器查看能力：看一台机器或一片机器区——由哪些机器组成、各自的状态、挂在哪些网络上。只读分析。
 *
 * <p>不打开界面看机器内部（那归 use 与 deposit/obtain），不预测机器能不能转；
 * 转不转以每台的运行状态为准，一起给在结果里。范围没给就是脚下这一片。
 */
final class MachineInspectModule implements AbilityModule {

    private final MachineServices services;
    private final AbilitySpec spec = new AbilitySpec(
            ModIdentity.MOD_ID + ":machine_inspect",
            "看一台机器或一片机器区：组成、运行状态、进出口、挂在哪些网络上（只读）",
            AbilityDoc.forAbility("machine_inspect"),
            ParamSpecs.of(
                    ParamSpec.of("radius", ParamType.INTEGER).range(1, 32).defaultValue(16)
                            .doc("看多大范围，单位格；给了目标就是以它为中心，没给以角色为中心").build()),
            Set.of(TargetKind.HERE, TargetKind.SEEN, TargetKind.LANDMARK, TargetKind.POSITION),
            ExecutionMode.READ_ONLY,
            Set.of(),
            List.of(),
            Listing.LISTED);

    MachineInspectModule(MachineServices services) {
        this.services = services;
    }

    @Override public AbilitySpec spec() {
        return spec;
    }

    // 参数只有 radius，范围在参数规格里已经校过；直接交给任务分刻去看。
    @Override public StepDecision decide(StepContext step) {
        int radius = (int) (step.goal().params().has("radius") ? step.goal().params().integer("radius") : 16);
        return new StepDecision.Run(new MachineInspectInput(step.goal().target(), radius));
    }

    @Override public void registerTasks(TaskFactories factories) {
        factories.register(MachineInspectInput.class, input -> new MachineInspectTask(input, services));
    }
}
