// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.travel;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.travel.DestinationResolver;
import org.maiwithu.maicraft.behavior.travel.DimensionTravelServices;
import org.maiwithu.maicraft.behavior.travel.DimensionTravelTask;
import org.maiwithu.maicraft.behavior.travel.JourneyTask;
import org.maiwithu.maicraft.behavior.travel.ReadsPlacedBlocks;
import org.maiwithu.maicraft.behavior.travel.TravelProgressListener;
import org.maiwithu.maicraft.behavior.travel.TravelTask;
import org.maiwithu.maicraft.behavior.travel.TravelWorldView;
import org.maiwithu.maicraft.behavior.travel.spi.TravelMode;
import org.maiwithu.maicraft.behavior.travel.spi.TravelVia;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;

import org.maiwithu.maicraft.kernel.goal.Goal;

import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 出行能力：走到一个地方，包括去别的维度。能力只做薄皮——做决定时用玩家行为层的目的地解析器
 * 把目标对象落成目的地（说不清就向 LLM 提问，已经站在那里就直接结束）：同维度的目的地交给
 * 出行任务步行；落在别的维度或"就是去那个维度"交给跨维度任务（找活门、走进门、没门按许可建门、
 * 末地去要塞激活，到了带坐标的目的地接着步行）。装了联动的出行方式（电梯、喷气背包、火车）时，同维度的
 * 出行交给行程任务：近处走路，远处或走不通比各方式的报价，可用 via 指定方式。需要的走到、解析、扫描、交互、
 * 施工这些现场部件都从构造函数传入；本能力不认任何具体的服务实现。
 */
public final class TravelAbility implements AbilityModule {

    /** 最多走多久没有给时的缺省：不设时限，交给进度跟踪按"有没有在前进"判断。 */
    private static final int NO_TIME_LIMIT = 0;
    /** 找活门范围的缺省（格）：查了这么远的已加载区没有门才轮到建门。 */
    private static final int DEFAULT_SEARCH_DISTANCE = 128;

    private final WalkTo walks;
    private final DestinationResolver resolver;
    private final ReadsPlacedBlocks placedBlocks;
    private final TravelProgressListener progressListener;
    private final DimensionTravelServices dimensionServices;
    private final TravelWorldView world;
    private final List<TravelMode> modes;

    /** 只走路：没有联动的出行方式，参数表里没有 via。 */
    public TravelAbility(WalkTo walks, DestinationResolver resolver,
            ReadsPlacedBlocks placedBlocks, TravelProgressListener progressListener,
            DimensionTravelServices dimensionServices) {
        this(walks, resolver, placedBlocks, progressListener, dimensionServices, null, List.of());
    }

    /**
     * @param world 行程任务读角色此刻在哪；没有出行方式时用不到，可以为 null
     * @param modes 联动登记的出行方式（包过一层）；为空时只走路，参数表里没有 via
     */
    public TravelAbility(WalkTo walks, DestinationResolver resolver,
            ReadsPlacedBlocks placedBlocks, TravelProgressListener progressListener,
            DimensionTravelServices dimensionServices, TravelWorldView world, List<TravelMode> modes) {
        this.walks = walks;
        this.resolver = resolver;
        this.placedBlocks = placedBlocks;
        this.progressListener = progressListener;
        this.dimensionServices = dimensionServices;
        this.modes = List.copyOf(modes);
        if (!this.modes.isEmpty() && world == null) throw new IllegalArgumentException("有出行方式时要能读角色在哪");
        this.world = world;
    }

    @Override
    public AbilitySpec spec() {
        return new AbilitySpec(
                "maicraft:travel",
                "走到一个地方：给坐标、地点名、看得见的东西或往某方向走多远；给维度就是去那个维度",
                AbilityDoc.forAbility("travel"),
                params(),
                Set.of(TargetKind.HERE, TargetKind.SEEN, TargetKind.LANDMARK,
                        TargetKind.POSITION, TargetKind.DIMENSION, TargetKind.DIRECTION),
                ExecutionMode.CONTROLS_PLAYER,
                Set.of(),
                List.of(),
                Listing.LISTED);
    }

    // 参数表：装了联动的出行方式才有 via，可选值与说明由各方式自报，walk 打头。
    private ParamSpecs params() {
        List<ParamSpec> specs = new ArrayList<>(List.of(
                ParamSpec.of("radius", ParamType.NUMBER)
                        .defaultValue(2.0).range(0, 2048)
                        .doc("到达容差，单位格，默认 2；给 0 表示必须站进那一格").build(),
                ParamSpec.of("max_distance", ParamType.NUMBER)
                        .defaultValue(128.0).range(16, 2048)
                        .doc("找传送门的范围，单位格，默认 128；查遍已加载区没有门才建门").build(),
                ParamSpec.of("max_seconds", ParamType.INTEGER)
                        .doc("最多走多久（秒）；超时以卡住结束，写明停在哪、离目标多远").build()));
        if (!modes.isEmpty()) {
            List<TravelVia> vias = vias();
            specs.add(ParamSpec.of("via", ParamType.CHOICE)
                    .choices(vias.stream().map(TravelVia::name).toArray(String[]::new))
                    .doc("只用这种方式去（首尾免不了的步行除外），用不了会说明原因，不悄悄改走路；不给就近处走、远处挑最快的："
                            + vias.stream().map(via -> via.name() + " " + via.description())
                                    .collect(Collectors.joining("；"))).build());
            // 各方式自己的参数（电梯的 floor……）跟在 via 后面，同名的只留第一个。
            for (TravelMode mode : modes) {
                for (ParamSpec option : mode.options()) {
                    if (specs.stream().noneMatch(spec -> spec.name().equals(option.name()))) specs.add(option);
                }
            }
        }
        return ParamSpecs.of(specs.toArray(ParamSpec[]::new));
    }

    /** 可选的方式：走路打头，再按登记顺序列各方式，同名的只留第一个。 */
    private List<TravelVia> vias() {
        List<TravelVia> vias = new ArrayList<>(List.of(TravelVia.WALK));
        for (TravelMode mode : modes) {
            if (vias.stream().noneMatch(via -> via.name().equals(mode.via().name()))) vias.add(mode.via());
        }
        return vias;
    }

    @Override
    public StepDecision decide(StepContext step) {
        Goal goal = step.goal();
        // 给方式的话（floor、vehicle）就是用那种方式：没给 via 时按它算，via 给了别的就是参数自相矛盾。
        Choice choice = choose(goal.params());
        if (choice.problem() != null) return new StepDecision.Finish(TaskResult.failed("出行：参数对不上", choice.problem()));
        if (goal.target() == null) {
            if (choice.via() != null && !choice.options().isEmpty()) {
                // 只用那种方式、不往别处去：只坐电梯换楼层（via=elevator 加 floor），不给 target。
                return new StepDecision.Run(new JourneyInput(new JourneyTask.Journey(null, choice.via(), choice.options(),
                        goal.permissions(), maxSeconds(goal)), TerrainPermit.of(goal.permissions())));
            }
            // 去哪必须用目标对象说明：没有目标对象就没有目的地，开局就结束，不猜脚边。
            return new StepDecision.Finish(TaskResult.failed("出行的目标没有说去哪",
                    Problem.of(Problem.Kind.UNSUPPORTED,
                            "出行的目标对象没有给：要用坐标、地点名、观察编号、维度或方向说明去哪")));
        }
        // 目的地现在就解析：坐标没给 y 走那一柱列，地标没记过就问，观察编号失效就如实结束；
        // 落在别的维度按维度路由认门，认不出的维度对在这里就以走不了结束。
        var resolution = resolver.resolve(goal.target(), goal.params().number("radius"));
        if (resolution instanceof DestinationResolver.Resolution.Ready ready) {
            // 同维度：没有出行方式或指定只走路，就是出行任务；否则交给行程，近处走、远处比报价、给了 via 只用它。
            String via = choice.via();
            TerrainPermit permit = TerrainPermit.of(goal.permissions());
            if (modes.isEmpty() || TravelVia.WALK.name().equals(via)) {
                return new StepDecision.Run(new TravelInput(ready.destination(), maxSeconds(goal), permit));
            }
            return new StepDecision.Run(new JourneyInput(new JourneyTask.Journey(ready.destination(), via,
                    choice.options(), goal.permissions(), maxSeconds(goal)), permit));
        }
        if (resolution instanceof DestinationResolver.Resolution.CrossDimension cross) {
            return new StepDecision.Run(new DimensionTravelInput(cross.targetDimension(), cross.destination(),
                    searchDistance(goal), maxSeconds(goal), goal.permissions()));
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

    /** 用什么方式、给它的话；参数自相矛盾时带问题。 */
    private record Choice(String via, Map<String, String> options, Problem problem) {}

    // via 与各方式自己的参数凑一起：给了哪种方式的参数就是那种方式，via 给了别的就说清对不上。
    private Choice choose(ParamValues params) {
        String via = params.has("via") ? params.text("via") : null;
        Map<String, String> options = new LinkedHashMap<>();
        String owner = null;
        for (TravelMode mode : modes) {
            for (ParamSpec option : mode.options()) {
                if (!params.has(option.name()) || options.containsKey(option.name())) continue;
                JsonElement value = params.toJson().get(option.name());
                options.put(option.name(), value.isJsonPrimitive() ? value.getAsString() : value.toString());
                owner = owner == null ? mode.via().name() : owner;
            }
        }
        if (owner != null && via != null && !via.equals(owner)) {
            return new Choice(via, options, Problem.of(Problem.Kind.INVALID_PARAMETER,
                    String.join("、", options.keySet()) + " 只配 via=" + owner + "，这次给的是 via=" + via,
                    "去掉 via，或改成 via=" + owner));
        }
        return new Choice(via != null ? via : owner, options, null);
    }

    @Override
    public void registerTasks(TaskFactories factories) {
        factories.register(TravelInput.class, input -> {
            // 每次运行新建出行任务；走到与垫方块记录通过闭包交给任务，不经全局单例。
            return new TravelTask(input.destination(), input.maxSeconds(), input.permit(),
                    walks, placedBlocks, progressListener, world);
        });
        factories.register(JourneyInput.class, input -> {
            // 换交通的行程：走路的几段每段新建一个出行任务，挖垫、泅渡、落地救援照只走路的那一套。
            JourneyTask.Journey journey = input.journey();
            return new JourneyTask(journey, world, modes, destination -> new TravelTask(destination,
                    journey.maxSeconds(), input.permit(), walks, placedBlocks, progressListener, world));
        });
        factories.register(DimensionTravelInput.class, input -> {
            // 跨维度行程：找门、进门、建门与末地门房间都从这个任务里按阶段走。
            return new DimensionTravelTask(new DimensionTravelTask.Journey(input.targetDimension(),
                    input.destination(), input.searchDistance(), input.maxSeconds(), input.permissions()),
                    dimensionServices);
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

    /** 找门范围（格）；没给按缺省，给得不合理也按缺省。 */
    private int searchDistance(Goal goal) {
        if (!goal.params().has("max_distance")) {
            return DEFAULT_SEARCH_DISTANCE;
        }
        double given = goal.params().number("max_distance");
        return given <= 0 ? DEFAULT_SEARCH_DISTANCE : (int) Math.min(given, Integer.MAX_VALUE / 2);
    }
}
