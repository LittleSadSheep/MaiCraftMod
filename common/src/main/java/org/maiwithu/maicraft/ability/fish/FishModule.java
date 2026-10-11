// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fish;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.inventory.InventorySpace;
import org.maiwithu.maicraft.behavior.inventory.MovesToMainhand;
import org.maiwithu.maicraft.behavior.inventory.PicksUpDrops;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.game.player.FishingHookFacts;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 钓鱼能力：在附近的水边钓鱼。鱼竿、钓位、抛竿、咬钩收竿与钓获记账都组合玩家行为层已有的模型；
 * 这里只有钓鱼特有的两件事——钓位几何与「钓到几次收竿还是常驻钓」。
 *
 * <p>不接受目标对象：只从当前位置找水，要去别的水域 LLM 先 travel。没有鱼竿会先去弄一根；
 * 不给 count 就建常驻任务一直钓，直到换活或取消。
 */
public final class FishModule implements AbilityModule {

    private final Supplier<PlayerContext> context;
    private final FishServices services;

    private final AbilitySpec spec = new AbilitySpec(
            "maicraft:fish",
            "在附近的水边钓鱼：自己找钓位、抛竿、咬钩就收，钓上来的照单全收",
            AbilityDoc.forAbility("fish"),
            ParamSpecs.of(
                    ParamSpec.of("count", ParamType.INTEGER)
                            .range(1, Long.MAX_VALUE)
                            .doc("钓到几次收竿（垃圾、宝藏也算一次）；不给就一直钓（常驻）")
                            .build(),
                    ParamSpec.of("max_seconds", ParamType.INTEGER)
                            .range(1, Long.MAX_VALUE)
                            .doc("最多钓多久（秒）；到时按没进展结束，已钓到的照算")
                            .build()),
            Set.of(),
            ExecutionMode.CONTROLS_PLAYER,
            Set.of(),
            List.of(),
            Listing.LISTED);

    public FishModule(Supplier<PlayerContext> context, ItemNeeds obtain, MovesToMainhand toMainhand,
                      WalkTo walks, PicksUpDrops pickups, InventorySpace space, FishingHookFacts hooks) {
        this.context = Objects.requireNonNull(context, "context");
        // 受保护的格暂无生产读端：钓位判断按"没有格子受保护"处理，保护判断接上后在清单里补一行。
        this.services = new FishServices(obtain, toMainhand, walks, pickups, space, hooks,
                FishServices.nothingProtected());
    }

    @Override public AbilitySpec spec() {
        return spec;
    }

    @Override public StepDecision decide(StepContext step) {
        PlayerContext current = context.get();
        if (current == null) {
            return StepDecision.NOT_READY;
        }
        // fish 不声明目标对象：给了地点或东西就是参数错误，要去别的水边先 travel，不悄悄从当前钓。
        if (step.goal().target() != null) {
            return new StepDecision.Finish(TaskResult.failed("钓鱼不指定目标",
                    Problem.of(Problem.Kind.INVALID_PARAMETER,
                            "fish 不接受目标对象：只从当前位置找水，要去别的水边先 travel")));
        }
        long maxSeconds = step.goal().params().has("max_seconds") ? step.goal().params().integer("max_seconds") : 0;
        int count = step.goal().params().has("count") ? (int) step.goal().params().integer("count") : 0;
        return new StepDecision.Run(new FishInput(count, maxSeconds));
    }

    @Override public void registerTasks(TaskFactories factories) {
        factories.register(FishInput.class, input -> input.count() > 0
                ? new FishTask(input, services, Permissions.DEFAULT)
                : new FishTask.StandingFish(input, services, Permissions.DEFAULT));
    }
}
