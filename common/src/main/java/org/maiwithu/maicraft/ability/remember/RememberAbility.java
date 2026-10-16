// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.remember;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.behavior.acquire.ReadsCharacterPosition;
import org.maiwithu.maicraft.behavior.travel.ReadsSeenTargets;
import org.maiwithu.maicraft.behavior.worldmemory.RememberedRegion;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 记地点能力：站在家门口想"这是家"，或指着远处的矿洞口说"那是矿洞"；主人说"这一片是我的，别动"时，
 * 把以那儿为中心的一片圈成玩家地盘。只改世界记忆，不移动角色、不读地形、不核实那里能不能站人——这些交给
 * 真正去那里的能力。execute 当场返回结果，不成为主任务，也不打断手上正在做的事；放进按顺序做事的目标里，轮到它才记。
 *
 * <p>记下的位置都带维度：目标对象没写维度的，按角色此刻所在的维度记。同名覆盖，结果写出原来记成什么；
 * 没变就直接完成，说"本来就这样记着"。地点和地盘是两本账，忘掉时同名的一并忘；两样都没记过就失败并附上
 * 记得的名字，免得写错名字被当成已经忘掉。圈下的地盘立刻生效：保护判断把里面的方块都当受保护。
 */
public final class RememberAbility implements AbilityModule {

    /** 圈地盘没给半径时圈多大：一座小屋连门口一圈。 */
    static final int DEFAULT_REGION_RADIUS = 8;
    /** 圈地盘最大的半径：再大就是半张地图，角色哪儿都动不了。 */
    static final int MAX_REGION_RADIUS = 64;

    private final WorldMemory memory;
    private final ReadsCharacterPosition characterPosition;
    private final ReadsSeenTargets seenTargets;

    public RememberAbility(WorldMemory memory, ReadsCharacterPosition characterPosition,
                           ReadsSeenTargets seenTargets) {
        this.memory = memory;
        this.characterPosition = characterPosition;
        this.seenTargets = seenTargets;
    }

    @Override
    public AbilitySpec spec() {
        return new AbilitySpec(
                "maicraft:remember",
                "记住或忘掉一个按名字叫的地点，或把一片地方圈成玩家地盘",
                AbilityDoc.forAbility("remember"),
                ParamSpecs.of(
                        ParamSpec.of("name", ParamType.TEXT).required()
                                .doc("地点或地盘的名字；首尾空白去掉后原样比较，不改大小写").build(),
                        ParamSpec.of("operation", ParamType.CHOICE).defaultValue("remember")
                                .choices("remember", "forget", "region")
                                .doc("remember 记住地点（默认，同名覆盖），forget 忘掉同名的地点与地盘，"
                                        + "region 以目标为中心圈一块玩家地盘（里面的方块不拆、箱子不取用）").build(),
                        ParamSpec.of("radius", ParamType.INTEGER).range(1, MAX_REGION_RADIUS)
                                .doc("只用于 region：地盘半径，单位格，默认 " + DEFAULT_REGION_RADIUS).build()),
                Set.of(TargetKind.HERE, TargetKind.SEEN, TargetKind.LANDMARK, TargetKind.POSITION),
                ExecutionMode.MEMORY_ONLY,
                Set.of(),
                List.of(),
                Listing.LISTED);
    }

    @Override
    public StepDecision decide(StepContext step) {
        ParamValues params = step.goal().params();
        // 名字按规格先去首尾空白再原样比较；参数规格已保证非空。
        String name = params.text("name").trim();
        String operation = params.has("operation") ? params.text("operation") : "remember";
        // 给了半径却没说圈地盘：多半是想圈地盘忘了写，只记一个点会让 LLM 以为那片已经受保护，主播的基地照样被挖。
        if (params.has("radius") && !"region".equals(operation)) {
            return new StepDecision.Finish(TaskResult.failed("radius 只用于圈地盘",
                    Problem.of(Problem.Kind.INVALID_PARAMETER, "给了 radius，但 operation 不是 region",
                            "要圈一片玩家地盘写 operation=region；只记一个地点就去掉 radius")));
        }
        return switch (operation) {
            case "forget" -> forget(name);
            case "region" -> region(name, step.goal().target(), params.has("radius")
                    ? (int) params.integer("radius") : DEFAULT_REGION_RADIUS);
            default -> remember(name, step.goal().target());
        };
    }

    /** 忘掉：同名的地点与地盘记过哪样忘哪样；两样都没记过就失败并附上记得的全部名字。 */
    private StepDecision forget(String name) {
        Optional<WorldPosition> previous = memory.place(name);
        Optional<RememberedRegion> region = memory.region(name);
        if (previous.isEmpty() && region.isEmpty()) {
            return new StepDecision.Finish(TaskResult.failed("没有叫「" + name + "」的记住的地点或地盘",
                    Problem.of(Problem.Kind.NOT_FOUND, rememberedNames(),
                            "核对名字再忘；要记下它先用 operation=remember 或 region")));
        }
        return new StepDecision.Run(RememberPlaceInput.forget(name, previous.orElse(null), region.orElse(null)));
    }

    // 忘错名字时把记得的都列出来：地点一行、地盘一行，一个都没有也照实说。
    private String rememberedNames() {
        List<String> places = new ArrayList<>(memory.places().keySet());
        List<String> regions = memory.regions().stream().map(RememberedRegion::name).toList();
        return "记得的地点有：" + (places.isEmpty() ? "（一个都没记过）" : String.join("、", places))
                + "；圈过的地盘有：" + (regions.isEmpty() ? "（一块都没圈过）" : String.join("、", regions));
    }

    /** 记住地点：先把目标对象落实成带维度的位置，再决定是当场说"本来就这样"还是动手写。 */
    private StepDecision remember(String name, Target target) {
        Located located = locate(target);
        if (located.failure() != null) return located.failure();
        WorldPosition desired = located.position();
        WorldPosition previous = memory.place(name).orElse(null);
        if (previous != null && previous.equals(desired)) {
            return new StepDecision.Finish(TaskResult.builder(TaskResult.Status.DONE,
                            "「" + name + "」本来就这样记着")
                    .details(RememberPlaceTask.RememberDetails.ofPlace(name, "remember", desired, null))
                    .build());
        }
        return new StepDecision.Run(RememberPlaceInput.place(name, desired, previous));
    }

    /** 圈地盘：中心按目标对象落实，同一个名字中心半径都没变就当场完成，否则开任务写进去。 */
    private StepDecision region(String name, Target target, int radius) {
        Located located = locate(target);
        if (located.failure() != null) return located.failure();
        WorldPosition center = located.position();
        RememberedRegion previous = memory.region(name).orElse(null);
        if (previous != null && previous.center().equals(center) && previous.radiusBlocks() == radius) {
            return new StepDecision.Finish(TaskResult.builder(TaskResult.Status.DONE,
                            "地盘「" + name + "」本来就这样记着")
                    .details(new RememberPlaceTask.RememberDetails(name, "region", center, radius, null, null, null))
                    .build());
        }
        return new StepDecision.Run(RememberPlaceInput.region(name, center, radius, previous));
    }

    /** 目标对象落实成的位置；落实不了时 failure 是就地给出的失败结论。 */
    private record Located(WorldPosition position, StepDecision failure) {
        static Located at(WorldPosition position) {
            return new Located(position, null);
        }

        static Located failed(StepDecision failure) {
            return new Located(null, failure);
        }
    }

    // 目标对象落成带维度的位置，记地点与圈地盘共用：查不到的编号、没记过的地标、缺高度的坐标都如实失败。
    private Located locate(Target target) {
        Target kind = target == null ? new Target.Here() : target;
        if (kind instanceof Target.Seen seen) {
            Optional<WorldPosition> position = seenTargets.positionOf(seen.id());
            return position.map(Located::at).orElseGet(() -> Located.failed(gone(seen.id())));
        }
        if (kind instanceof Target.Landmark landmark) {
            // 给同一个地方再起一个名字、或以记住的地点为中心圈地盘：位置从已经记住的地点读。
            Optional<WorldPosition> position = memory.place(landmark.name());
            if (position.isEmpty()) {
                return Located.failed(new StepDecision.Finish(TaskResult.failed(
                        "没有叫「" + landmark.name() + "」的记住的地点",
                        Problem.of(Problem.Kind.NOT_FOUND, "记得的地点里没有这个名字",
                                "先用它的坐标或 here 记下来，或核对名字"))));
            }
            return Located.at(position.get());
        }
        if (kind instanceof Target.Position position) {
            // 按坐标记必须给 y：记住的地点会被出行当作确认过的目的地，地盘中心的高度决定圈到哪几层，都不存猜的高度。
            if (position.y() == null) {
                return Located.failed(new StepDecision.Finish(TaskResult.failed("按坐标记地点必须给 y",
                        Problem.of(Problem.Kind.INVALID_PARAMETER, "坐标缺 y",
                                "先走到那里再用 here 记，或把确认过的高度补上"))));
            }
            // 没写维度按角色此刻所在的维度记；角色不在世界里读不到维度，如实说。
            String dimension = position.dimension();
            if (dimension == null) {
                WorldPosition here = characterPosition.currentPosition();
                if (here == null) return Located.failed(notInWorld());
                dimension = here.dimension();
            }
            return Located.at(new WorldPosition(position.x(), position.y(), position.z(), dimension));
        }
        if (kind instanceof Target.Here) {
            WorldPosition here = characterPosition.currentPosition();
            return here == null ? Located.failed(notInWorld()) : Located.at(here);
        }
        // player、direction、previous 不接受：玩家用它的观察编号走 seen；previous 内核还没记各步的位置。
        return Located.failed(new StepDecision.Finish(TaskResult.failed("记地点不接受这种目标对象",
                Problem.of(Problem.Kind.UNSUPPORTED, "接受的种类：here、seen、landmark、position",
                        "玩家在场景里有观察编号，用 seen 指它"))));
    }

    // 角色不在世界里（退出、换世界途中）读不到自己的位置：记不了 here，也定不了没写维度的坐标在哪个维度。
    private static StepDecision notInWorld() {
        return new StepDecision.Finish(TaskResult.failed("角色不在世界里，读不到自己在哪",
                Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "角色此刻不在世界里，记不了 here 或没写维度的坐标",
                        "进了世界再记，或给坐标写上维度")));
    }

    /** 观察编号指向的东西不在了（走远、被拆）：如实失败，不猜它最后在哪。 */
    private static StepDecision gone(String observationId) {
        return new StepDecision.Finish(TaskResult.failed("编号 " + observationId + " 指向的东西已经不在了",
                Problem.of(Problem.Kind.TARGET_GONE, "观察编号 " + observationId + " 已失效",
                        "重新观察拿到新的编号再记")));
    }

    @Override
    public void registerTasks(TaskFactories factories) {
        // 每次运行新建记地点任务；世界记忆从清单递进来，不经全局单例。
        factories.register(RememberPlaceInput.class, input -> new RememberPlaceTask(input, memory));
    }
}
