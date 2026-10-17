// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

import java.util.List;
import java.util.Set;

/**
 * 跟随能力：像陪着走的玩家那样跟着一位玩家或一个实体，保持几格距离；它走就跟，它停就停。
 *
 * <p>跟随是常驻的，不会自己完成；跟不过去先停下等、跟丢了先去找，长时间没跟上或找不回来才结束并说明原因。
 * 本包对外的唯一入口：规格、参数与任务都从这里进入内核。
 */
public final class FollowModule implements AbilityModule {

    private final WalkTo walks;
    private final FollowView view;
    private final FollowMoves moves;
    private final TaskEventSink events;

    /** 生产用：目标视图、身体动作、走到与事件出口由启动一侧创建并登记。 */
    public FollowModule(WalkTo walks, LiveFollowView view, LiveFollowMoves moves, TaskEventSink events) {
        this.walks = walks;
        this.view = view;
        this.moves = moves;
        this.events = events;
    }

    private final AbilitySpec spec = new AbilitySpec(
            "maicraft:follow",
            "跟着一位玩家或一个实体，保持几格距离：它走就跟、它停就停，跟着上车、过门，它在打怪时上去帮忙",
            AbilityDoc.forAbility("follow"),
            ParamSpecs.of(
                    ParamSpec.of("distance", ParamType.INTEGER)
                            .range(2, 16)
                            .defaultValue(3)
                            .doc("保持的距离，单位格")
                            .build()),
            Set.of(TargetKind.PLAYER, TargetKind.SEEN),
            ExecutionMode.CONTROLS_PLAYER,
            Set.of(),
            List.of(),
            Listing.LISTED);

    @Override public AbilitySpec spec() { return spec; }

    @Override
    public StepDecision decide(StepContext step) {
        var target = step.goal().target();
        if (target == null) {
            return new StepDecision.Finish(TaskResult.builder(
                            TaskResult.Status.FAILED, "跟随缺少目标")
                    .problem(Problem.of(
                            Problem.Kind.NOT_FOUND,
                            "没说要跟着谁：给一位玩家的名字，或先 observe 再给一个观察编号 e#",
                            "先 observe 找到它，再用观察编号跟随"))
                    .build());
        }
        var params = step.goal().params();
        int distance = (int) (params.has("distance") ? params.integer("distance") : 3);
        return new StepDecision.Run(new FollowInput(target, distance, step.goal().permissions()));
    }

    @Override
    public void registerTasks(TaskFactories factories) {
        factories.register(FollowInput.class, input -> new FollowTask(input, view, moves, walks, events));
    }
}
