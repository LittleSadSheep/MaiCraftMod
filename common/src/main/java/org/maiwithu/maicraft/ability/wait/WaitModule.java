// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.wait;

import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TickContext;

import java.util.List;
import java.util.Set;

import java.util.function.Function;

/**
 * 等待能力：在游戏里等一个条件——等一段时间、等天黑或天亮、等生命回满、等不再饥饿。
 *
 * <p>只观察，不会为了达成条件去做任何事，也没有超时；要停下由 LLM 取消这个目标。
 * 本包对外的唯一入口：规格、参数与任务都从这里进入内核。
 */
public final class WaitModule implements AbilityModule {

    private final AbilitySpec spec = new AbilitySpec(
            "maicraft:wait",
            "在游戏里等一个条件：等一段时间、等天黑天亮、等血回满或不再饥饿",
            AbilityDoc.forAbility("wait"),
            ParamSpecs.of(
                    ParamSpec.of("condition", ParamType.CHOICE)
                            .choices("elapsed", "day", "night", "health_full", "not_hungry")
                            .defaultValue("elapsed")
                            .doc("等什么：elapsed（只等时间，默认）/ day / night / health_full / not_hungry")
                            .build(),
                    ParamSpec.of("after_seconds", ParamType.INTEGER)
                            .range(0, WaitInput.MAX_AFTER_SECONDS)
                            .defaultValue(0)
                            .doc("最短先等多少秒再开始查条件（elapsed 条件下过了这段时间即完成）")
                            .build()),
            Set.of(),
            ExecutionMode.CONTROLS_PLAYER,
            Set.of(),
            List.of(),
            Listing.LISTED);

    /** 世界事实怎么从每刻的上下文取；生产实现逐刻读角色，测试换成固定值。 */
    private final Function<TickContext, WaitWorld> worlds;

    /** 生产用：世界事实逐刻从角色上下文读。 */
    public WaitModule() {
        this(WaitWorld::read);
    }

    /** 测试用：注入固定的世界事实来源。 */
    WaitModule(Function<TickContext, WaitWorld> worlds) {
        this.worlds = worlds;
    }

    @Override public AbilitySpec spec() { return spec; }

    @Override
    public StepDecision decide(StepContext step) {
        var params = step.goal().params();
        WaitFor condition = WaitFor.ofParam(params.has("condition") ? params.text("condition") : "elapsed");
        long afterSeconds = params.has("after_seconds") ? params.integer("after_seconds") : 0;
        return new StepDecision.Run(new WaitInput(condition, afterSeconds));
    }

    @Override
    public void registerTasks(TaskFactories factories) {
        factories.register(WaitInput.class, input -> new WaitTask(input, worlds));
    }
}
