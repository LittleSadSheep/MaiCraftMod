// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.travel;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.travel.DestinationResolver;
import org.maiwithu.maicraft.behavior.travel.ReadsPlacedBlocks;
import org.maiwithu.maicraft.behavior.travel.TravelProgressListener;
import org.maiwithu.maicraft.behavior.travel.TravelTask;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;

import org.maiwithu.maicraft.kernel.goal.Goal;

import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.param.Param;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

import java.util.List;
import java.util.Set;

/**
 * 出行能力：走到一个地方。能力只做薄皮——做决定时用玩家行为层的目的地解析器把目标对象落成目的地
 * （说不清就向 LLM 提问，已经站在那里就直接结束），再把目的地、时限和许可交给出行任务；
 * 走到、到达确认与结算都是出行任务自己的事。
 *
 * <p>里程碑一只有步行：目的地在别的维度、乘船、飞行、电梯都由目的地解析或任务以
 * "暂不支持"如实结束，本能力不做变通。需要的走到、目的地解析、垫方块记录与进展去处
 * 都从构造函数传入；本能力不认任何具体的服务实现。
 */
public final class TravelAbility implements AbilityModule {

    /** 最多走多久没有给时的缺省：不设时限，交给进度跟踪按"有没有在前进"判断。 */
    private static final int NO_TIME_LIMIT = 0;

    private final WalkTo walks;
    private final DestinationResolver resolver;
    private final ReadsPlacedBlocks placedBlocks;
    private final TravelProgressListener progressListener;

    public TravelAbility(WalkTo walks, DestinationResolver resolver,
            ReadsPlacedBlocks placedBlocks, TravelProgressListener progressListener) {
        this.walks = walks;
        this.resolver = resolver;
        this.placedBlocks = placedBlocks;
        this.progressListener = progressListener;
    }

    @Override
    public AbilitySpec spec() {
        return new AbilitySpec(
                "maicraft:travel",
                "走到一个地方：给坐标、地点名、看得见的东西或往某方向走多远",
                AbilityDoc.forAbility("travel"),
                ParamSpec.of(
                        Param.of("radius", ParamType.NUMBER)
                                .defaultValue(2.0).range(0, 2048)
                                .doc("到达容差，单位格，默认 2；给 0 表示必须站进那一格").build(),
                        Param.of("max_seconds", ParamType.INTEGER)
                                .doc("最多走多久（秒）；超时以卡住结束，写明停在哪、离目标多远").build()),
                Set.of(TargetKind.HERE, TargetKind.SEEN, TargetKind.LANDMARK,
                        TargetKind.POSITION, TargetKind.DIRECTION),
                ExecutionMode.CONTROLS_PLAYER,
                Set.of(),
                List.of(),
                Listing.LISTED);
    }

    @Override
    public StepDecision decide(StepContext step) {
        Goal goal = step.goal();
        if (goal.target() == null) {
            // 去哪必须用目标对象说明：没有目标对象就没有目的地，开局就结束，不猜脚边。
            return new StepDecision.Finish(TaskResult.failed("出行的目标没有说去哪",
                    Problem.of(Problem.Kind.UNSUPPORTED,
                            "出行的目标对象没有给：要用坐标、地点名、观察编号或方向说明去哪")));
        }
        // 目的地现在就解析：坐标没给 y 走那一柱列，地标没记过就问，观察编号失效就如实结束。
        var resolution = resolver.resolve(goal.target(), goal.params().number("radius"));
        if (resolution instanceof DestinationResolver.Resolution.Ready ready) {
            return new StepDecision.Run(new TravelInput(ready.destination(), maxSeconds(goal), TerrainPermit.of(goal.permissions())));
        }
        if (resolution instanceof DestinationResolver.Resolution.AlreadyThere) {
            return new StepDecision.Finish(TaskResult.done("出行：开始时已经站在目的地"));
        }
        if (resolution instanceof DestinationResolver.Resolution.Unclear unclear) {
            // 问过一次就不再问同一个问题：LLM 选了"这次不去"就取消，其余按说不清目的地结束，让它换个说法重新下达。
            if (step.answers().isEmpty()) {
                return new StepDecision.Ask(unclear.question());
            }
            if (step.answers().contains("give_up")) {
                return new StepDecision.Finish(TaskResult.cancelled("出行：这次不去了"));
            }
            return new StepDecision.Finish(TaskResult.failed("出行：目的地说不清",
                    Problem.of(Problem.Kind.NOT_FOUND, unclear.question().text(),
                            "改用坐标或观察编号重新下达出行")));
        }
        return new StepDecision.Finish(TaskResult.failed("出行：去不了",
                ((DestinationResolver.Resolution.DeadEnd) resolution).problem()));
    }

    @Override
    public void registerTasks(TaskFactories factories) {
        factories.register(TravelInput.class, input -> {
            // 每次运行新建出行任务；走到与垫方块记录通过闭包交给任务，不经全局单例。
            return new TravelTask(input.destination(), input.maxSeconds(), input.permit(),
                    walks, placedBlocks, progressListener);
        });
    }

    /** 秒换内部用的整数时限；没给就不设时限。 */
    private int maxSeconds(Goal goal) {
        if (!goal.params().has("max_seconds")) {
            return NO_TIME_LIMIT;
        }
        long seconds = goal.params().integer("max_seconds");
        if (seconds <= 0 || seconds > Integer.MAX_VALUE / 20) {
            // 不给正数与给得过大同义：都不设时限，交给进度跟踪判断。
            return NO_TIME_LIMIT;
        }
        return (int) seconds;
    }
}
