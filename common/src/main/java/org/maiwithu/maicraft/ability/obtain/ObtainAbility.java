// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.obtain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.maiwithu.maicraft.behavior.acquire.ItemAcquisition;
import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.ReadsItemRegistry;
import org.maiwithu.maicraft.behavior.acquire.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.StartsAcquisition;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.Param;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.param.Params;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireRoute;

/**
 * 拿东西的能力：让背包里某样东西再多几件。决定只做两件事——
 * 把参数在游戏里立得住与否一次查清（物品 ID 存在、标签下有注册物品，不进世界就能发现），
 * 以及把 via、radius、max_distance 换成引擎认的限定；真正问来源、挑路、动手、重新清点
 * 全在玩家行为层的拿到物品引擎里，这里不做第二份。
 */
public final class ObtainAbility implements AbilityModule {

    /** 搜索半径的防误扫上限：再大就容易把半张地图扫进一次问询。 */
    private static final long MAX_RADIUS = 64;

    private final StartsAcquisition acquisition;
    private final List<AcquireRoute> routes;
    private final ReadsItemRegistry registry;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsItemTags tags;

    /**
     * @param routes 登记的来源自报的途径，按来源顺序、去重：via 的可选值与说明从它生成，
     *               联动模组接入的途径装了才在里面
     */
    public ObtainAbility(StartsAcquisition acquisition, List<AcquireRoute> routes, ReadsItemRegistry registry,
            BackpackView backpack, OffhandContents offhand, ReadsItemTags tags) {
        this.acquisition = acquisition;
        this.routes = List.copyOf(Objects.requireNonNull(routes, "routes"));
        if (this.routes.isEmpty()) throw new IllegalArgumentException("拿东西至少要有一条途径");
        this.registry = registry;
        this.backpack = backpack;
        this.offhand = offhand;
        this.tags = tags;
    }

    @Override public AbilitySpec spec() {
        return new AbilitySpec("maicraft:obtain",
                "让背包里某样东西再多几件；可用 via 指定途径",
                AbilityDoc.forAbility("obtain"),
                ParamSpec.of(
                        Param.of("item", ParamType.ITEM_OR_TAG).required()
                                .doc("要多拿的物品 ID，或 # 开头的标签").build(),
                        Param.of("count", ParamType.INTEGER).defaultValue(1L).range(1, Integer.MAX_VALUE)
                                .doc("再多拿几件（不是背包里的总数）").build(),
                        Param.of("via", ParamType.CHOICE)
                                .choices(routes.stream().map(AcquireRoute::name).toArray(String[]::new))
                                .doc("只走指定途径，不给就按总代价自己挑：" + describeRoutes()).build(),
                        Param.of("radius", ParamType.INTEGER).range(1, MAX_RADIUS)
                                .doc("容器与采掘的搜索范围（格）；给了就冻结在这个范围里").build(),
                        Param.of("max_distance", ParamType.INTEGER).range(1, Integer.MAX_VALUE)
                                .doc("愿意为此走多远（格）").build()),
                Set.of(), ExecutionMode.CONTROLS_PLAYER, Set.of(), List.of(), Listing.LISTED);
    }

    @Override public StepDecision decide(StepContext step) {
        Goal goal = step.goal();
        Params params = goal.params();
        List<String> invalid = new ArrayList<>();
        if (!params.has("item")) {
            invalid.add("缺少要拿的东西（item）");
            return invalidGoal(invalid);
        }
        WantedItem wanted;
        try {
            wanted = new WantedItem(params.text("item"));
        } catch (IllegalArgumentException wrong) {
            return invalidGoal(List.of("要拿的东西写法不对：" + wrong.getMessage()));
        }
        if (wanted.isTag()) {
            if (!registry.tagHasItems(wanted.tagId())) {
                invalid.add("标签 " + wanted.specifier() + " 下一件注册物品都没有，要不了东西");
            }
        } else if (!registry.itemExists(wanted.itemId())) {
            invalid.add("物品 " + wanted.specifier() + " 不在这个游戏里（注册表里查不到）");
        }
        if (params.has("count") && params.integer("count") < 1) {
            invalid.add("count 至少为 1");
        }
        if (!invalid.isEmpty()) {
            return invalidGoal(invalid);
        }
        int count = count(params);
        return new StepDecision.Run(new ObtainItems(wanted, count, scope(params),
                goal.permissions(), purposeLabel(goal),
                "再拿 " + count + " 个 " + wanted.specifier()));
    }

    @Override public void registerTasks(TaskFactories factories) {
        factories.register(ObtainItems.class, input ->
                new ObtainTask(input, acquisition, backpack, offhand, tags));
    }

    /** 每条途径一句说明，写进参数表：craft 工作台上做出来（含石切台）；smelt 熔炉里烧出来…… */
    private String describeRoutes() {
        return routes.stream().map(route -> route.name() + " " + route.description())
                .collect(Collectors.joining("；"));
    }

    /** 参数在游戏里立不住：一次报全，不进世界，不安排任何角色动作。 */
    private StepDecision invalidGoal(List<String> problems) {
        return new StepDecision.Finish(TaskResult.builder(TaskResult.Status.FAILED,
                        "没有开始拿：参数有问题——" + String.join("；", problems))
                .problem(Problem.of(Problem.Kind.INVALID_PARAMETER,
                        String.join("；", problems), "改一下参数再试")).build());
    }

    private int count(Params params) {
        return params.has("count") ? (int) params.integer("count") : 1;
    }

    private String purposeLabel(Goal goal) {
        return goal.purpose() == null || goal.purpose().isBlank() ? "按需要拿取" : goal.purpose();
    }

    /** 把 via、radius、max_distance 换成引擎认的限定；via 没给就是全部途径都参与。 */
    private ItemAcquisition.Scope scope(Params params) {
        Set<String> routes = params.has("via") ? Set.of(params.text("via")) : Set.of();
        Double maxDistance = params.has("max_distance") ? Double.valueOf(params.integer("max_distance")) : null;
        Integer radius = params.has("radius") ? Integer.valueOf((int) params.integer("radius")) : null;
        return new ItemAcquisition.Scope(routes, maxDistance, radius);
    }
}
