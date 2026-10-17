// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.obtain;

import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.maiwithu.maicraft.behavior.acquire.DropsFrom;
import org.maiwithu.maicraft.behavior.acquire.ItemAcquisition;
import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.ReadsItemRegistry;
import org.maiwithu.maicraft.behavior.acquire.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.StartsAcquisition;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.world.ReadsRegisteredIds;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;

/**
 * 拿东西的能力：让背包里某样东西再多几件。决定只做两件事——
 * 把参数在游戏里立得住与否一次查清（物品 ID 存在、标签下有注册物品、掉落线索里的方块与实体注册过，
 * 不进世界就能发现），以及把 via、radius、max_distance 换成引擎认的限定；真正问来源、挑路、动手、重新清点
 * 全在玩家行为层的拿到物品引擎里，这里不做第二份。
 */
public final class ObtainAbility implements AbilityModule {

    /** 搜索半径的防误扫上限：再大就容易把半张地图扫进一次问询。 */
    private static final long MAX_RADIUS = 64;

    private final StartsAcquisition acquisition;
    private final List<AcquireVia> vias;
    private final ReadsItemRegistry registry;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsItemTags tags;
    /** 方块与实体注册了哪些 ID：掉落线索按它分出方块与生物，写错的给相近的名字。 */
    private final ReadsRegisteredIds registeredIds;

    /**
     * @param vias 登记的来源自报的途径，按来源顺序、去重：via 的可选值与说明从它生成，
     *               联动模组接入的途径装了才在里面
     */
    public ObtainAbility(StartsAcquisition acquisition, List<AcquireVia> vias, ReadsItemRegistry registry,
            BackpackView backpack, OffhandContents offhand, ReadsItemTags tags, ReadsRegisteredIds registeredIds) {
        this.acquisition = acquisition;
        this.vias = List.copyOf(Objects.requireNonNull(vias, "vias"));
        if (this.vias.isEmpty()) throw new IllegalArgumentException("拿东西至少要有一条途径");
        this.registry = registry;
        this.backpack = backpack;
        this.offhand = offhand;
        this.tags = tags;
        this.registeredIds = Objects.requireNonNull(registeredIds, "registeredIds");
    }

    @Override public AbilitySpec spec() {
        return new AbilitySpec("maicraft:obtain",
                "让背包里某样东西再多几件；可用 via 指定途径",
                AbilityDoc.forAbility("obtain"),
                ParamSpecs.of(
                        ParamSpec.of("item", ParamType.ITEM_OR_TAG).required()
                                .doc("要多拿的物品 ID，或 # 开头的标签；#maicraft:foods/edible 是能吃的口粮一大类（含模组食物）").build(),
                        ParamSpec.of("count", ParamType.INTEGER).defaultValue(1L).range(1, Integer.MAX_VALUE)
                                .doc("再多拿几件（不是背包里的总数）").build(),
                        ParamSpec.of("via", ParamType.CHOICE)
                                .choices(vias.stream().map(AcquireVia::name).toArray(String[]::new))
                                .doc("只走指定途径，不给就按总代价自己挑：" + describeVias()).build(),
                        ParamSpec.of("radius", ParamType.INTEGER).range(1, MAX_RADIUS)
                                .doc("容器与采掘的搜索范围（格）；给了就冻结在这个范围里").build(),
                        ParamSpec.of("max_distance", ParamType.INTEGER).range(1, Integer.MAX_VALUE)
                                .doc("愿意为此走多远（格）").build(),
                        ParamSpec.of("prefer", ParamType.ITEM_LIST)
                                .doc("倾向用的材料（物品 ID 或 # 标签的列表）：几种做法都行时用到它们的排前面，"
                                        + "备料时沿配方链往下传；只影响先后，不禁用别的做法").build(),
                        ParamSpec.of("drops_from", ParamType.BLOCK_LIST)
                                .doc("要的东西从哪些方块挖得到、哪些生物打得到（方块 ID、# 方块标签或实体类型 ID 的列表），"
                                        + "模组矿、模组生物掉什么认不出时用，例如要 create:raw_zinc 写 [\"create:zinc_ore\"]；"
                                        + "采掘、收庄稼、狩猎照它去挖去打，拿到几件以实际进包为准；只管 item 这一样，备料不往下传").build()),
                Set.of(), ExecutionMode.CONTROLS_PLAYER, Set.of(), List.of(), Listing.LISTED);
    }

    @Override public StepDecision decide(StepContext step) {
        Goal goal = step.goal();
        ParamValues params = goal.params();
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
        // 写岔的物品或空标签不进世界：说成"…不存在；你是不是要 …"，LLM 照着改一次就对。
        boolean known = wanted.isTag() ? registry.tagHasItems(wanted.tagId()) : registry.itemExists(wanted.itemId());
        if (!known) {
            invalid.add(registry.missing(wanted.specifier()));
        }
        if (params.has("count") && params.integer("count") < 1) {
            invalid.add("count 至少为 1");
        }
        // 掉落线索里写错的方块、实体也不进世界：一项一句"…不存在；你是不是要 …"，和物品写错同一种说法。
        DropsFrom dropsFrom = DropsFrom.NONE;
        if (params.has("drops_from")) {
            DropsFrom.Sorted sorted = DropsFrom.sort(params.list("drops_from"), registeredIds);
            invalid.addAll(sorted.problems());
            dropsFrom = sorted.dropsFrom();
        }
        if (!invalid.isEmpty()) {
            return invalidGoal(invalid);
        }
        int count = count(params);
        List<String> prefer = params.has("prefer")
                ? params.list("prefer").stream().map(id -> id.toLowerCase(Locale.ROOT)).toList() : List.of();
        return new StepDecision.Run(new ObtainItems(wanted, count, scope(params),
                goal.permissions(), purposeLabel(goal),
                "再拿 " + count + " 个 " + wanted.specifier(), prefer, dropsFrom));
    }

    // 拿东西在哪做都一样：被自卫撤离带离原地后，从新地点重新看现场接着拿，不停在半路等 LLM。
    @Override public boolean resumesAnywhere() {
        return true;
    }

    @Override public void registerTasks(TaskFactories factories) {
        factories.register(ObtainItems.class, input ->
                new ObtainTask(input, acquisition, backpack, offhand, tags));
    }

    /** 每条途径一句说明，写进参数表：craft 工作台上做出来（含石切台）；smelt 熔炉里烧出来…… */
    private String describeVias() {
        return vias.stream().map(via -> via.name() + " " + via.description())
                .collect(Collectors.joining("；"));
    }

    /** 参数在游戏里立不住：一次报全，不进世界，不安排任何角色动作。 */
    private StepDecision invalidGoal(List<String> problems) {
        return new StepDecision.Finish(TaskResult.builder(TaskResult.Status.FAILED,
                        "没有开始拿：参数有问题——" + String.join("；", problems))
                .problem(Problem.of(Problem.Kind.INVALID_PARAMETER,
                        String.join("；", problems), "改一下参数再试")).build());
    }

    private int count(ParamValues params) {
        return params.has("count") ? (int) params.integer("count") : 1;
    }

    private String purposeLabel(Goal goal) {
        return goal.purpose() == null || goal.purpose().isBlank() ? "按需要拿取" : goal.purpose();
    }

    /** 把 via、radius、max_distance 换成引擎认的限定；via 没给就是全部途径都参与。 */
    private ItemAcquisition.Scope scope(ParamValues params) {
        Set<String> vias = params.has("via") ? Set.of(params.text("via")) : Set.of();
        Double maxDistance = params.has("max_distance") ? Double.valueOf(params.integer("max_distance")) : null;
        Integer radius = params.has("radius") ? Integer.valueOf((int) params.integer("radius")) : null;
        return new ItemAcquisition.Scope(vias, maxDistance, radius);
    }
}
