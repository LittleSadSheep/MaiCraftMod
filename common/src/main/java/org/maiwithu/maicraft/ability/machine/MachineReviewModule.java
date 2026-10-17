// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.ability.design.api.DesignCompiler;
import org.maiwithu.maicraft.ability.design.api.DesignStore;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 审阅机器蓝图的能力：能不能建、每台机器能不能做声明的工序、要哪些材料、身上缺什么。只读分析。
 *
 * <p>审阅永远不是开工的门；报的问题写清位置与建议，建不建 LLM 自己定。
 * 蓝图与已保存的设计编号二选一。
 */
final class MachineReviewModule implements AbilityModule {

    private final MachineServices services;
    /** 保存过的设计；没接时按"引用不了设计"如实说。 */
    private final Optional<DesignStore> designs;
    private final AbilitySpec spec = new AbilitySpec(
            ModIdentity.MOD_ID + ":machine_review",
            "审阅一份机器蓝图：能建吗、工序做得了吗、要哪些材料、身上缺什么（只读）",
            AbilityDoc.forAbility("machine_review"),
            ParamSpecs.of(
                    ParamSpec.of("blueprint", ParamType.JSON_OBJECT)
                            .doc("机器蓝图的正文（JSON 对象）：cells 逐格、parts 部件、installations 安装段、"
                                    + "settings 装后设置、processes 声明工序；和 design_id 二选一").build(),
                    ParamSpec.of("design_id", ParamType.TEXT)
                            .doc("已保存的设计编号：按它存的方块清单审（没有机器附加条目）").build()),
            Set.of(),
            ExecutionMode.READ_ONLY,
            Set.of(),
            List.of(),
            Listing.LISTED);

    MachineReviewModule(MachineServices services, Optional<DesignStore> designs) {
        this.services = services;
        this.designs = designs;
    }

    @Override public AbilitySpec spec() {
        return spec;
    }

    // 蓝图与 design_id 二选一，正文在计划阶段一次解析完：写错的地方全部报出来，不进游戏。
    @Override public StepDecision decide(StepContext step) {
        var params = step.goal().params();
        int given = (params.has("blueprint") ? 1 : 0) + (params.has("design_id") ? 1 : 0);
        if (given != 1) {
            return finishInvalid("blueprint 和 design_id 二选一，现在给了 " + given + " 个");
        }
        try {
            MachineBlueprint blueprint;
            String what;
            if (params.has("blueprint")) {
                blueprint = MachineBlueprints.parse(params.json("blueprint"));
                what = "逐格机器蓝图";
            } else {
                if (designs.isEmpty()) {
                    return new StepDecision.Finish(TaskResult.builder(TaskResult.Status.FAILED,
                                    "设计库没有接上，引用不了设计编号")
                            .problem(Problem.of(Problem.Kind.UNSUPPORTED, "design_id 现在用不了", "直接给 blueprint 正文")).build());
                }
                var design = designs.get().load(params.text("design_id"));
                List<PlannedCell> cells = DesignCompiler.compile(design.drawing()).cells();
                blueprint = new MachineBlueprint(cells, List.of(), List.of(), List.of(), List.of());
                what = "设计「" + design.name() + "」";
            }
            return new StepDecision.Run(new MachineReviewInput(blueprint, what));
        } catch (IllegalArgumentException invalid) {
            return finishInvalid(invalid.getMessage());
        }
    }

    private static StepDecision finishInvalid(String message) {
        return new StepDecision.Finish(TaskResult.builder(TaskResult.Status.FAILED, "蓝图读不了")
                .problem(Problem.of(Problem.Kind.INVALID_PARAMETER, message, "按错误里的定位改参数再试")).build());
    }

    @Override public void registerTasks(TaskFactories factories) {
        factories.register(MachineReviewInput.class, input -> new MachineReviewTask(input, services));
    }
}
