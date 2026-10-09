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
import org.maiwithu.maicraft.kernel.goal.AbilityHooks;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.param.Param;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TaskInput;

import java.util.List;
import java.util.Set;

/**
 * 出行能力：走到一个地方。能力只做薄皮——把目标对象、容差、时限和许可凑成一次出行交给
 * 玩家行为层的出行任务；解析目的地、走到、到达确认与结算都是出行任务自己的事。
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

    /** 正在运行的出行任务；目的地解析不清时提问钩子要从它这里取问题。 */
    private TravelTask running;

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
        // 坐标是否给全 y、地标是否记得住，由目的地解析在任务里处理，这里不预判。
        TerrainPermit permit = terrainPermit(goal);
        return new StepDecision.Run(new TravelInput(goal.target(), step.goal().params().number("radius"),
                maxSeconds(goal), permit));
    }

    @Override
    public void registerTasks(TaskFactories factories) {
        factories.register(TravelInput.class, input -> {
            // 每次运行新建出行任务；走到与解析器通过闭包交给任务，不经全局单例。
            TravelTask task = new TravelTask(input.target(), input.radius(), input.maxSeconds(),
                    input.permit(), walks, resolver, placedBlocks, progressListener);
            running = task;
            return task;
        });
    }

    @Override
    public AbilityHooks hooks() {
        return new AbilityHooks() {
            @Override
            public Question duringTask(StepContext step, TaskInput input) {
                // 目的地解析不清时出行任务挂起等玩家回答；问题从这里交给内核暂停任务去问。
                return running == null ? null : running.pendingQuestion().orElse(null);
            }
        };
    }

    /** 把这次目标的方块许可折算成走到能动多少地形；能不能挖某一格由寻路的方块通行判断把关。 */
    private TerrainPermit terrainPermit(Goal goal) {
        // 四档直接对齐：只垫不挖的档走到实现方按"只垫不挖"开关寻路，垫上的临时方块逐格进结果。
        return new TerrainPermit(goal.permissions().changeBlocks(),
                goal.permissions().changeBlocks() != Permissions.BlockChanges.NONE);
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
