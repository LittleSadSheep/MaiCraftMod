// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.remember;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.behavior.acquire.ReadsCharacterPosition;
import org.maiwithu.maicraft.behavior.travel.ReadsSeenTargets;
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
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 记地点能力：站在家门口想"这是家"，或指着远处的矿洞口说"那是矿洞"。只改世界记忆，不移动角色、
 * 不读地形、不核实那里能不能站人——这些交给真正去那里的能力。execute 当场返回结果，不成为主任务，
 * 也不打断手上正在做的事；放进按顺序做事的目标里，轮到它才记。
 *
 * <p>记下的位置都带维度：目标对象没写维度的，按角色此刻所在的维度记。同名覆盖，结果写出原来记在哪；
 * 位置没变时直接完成，说"本来就这样记着"。忘掉只忘名字对得上的那一个，名字没记过就失败并附上
 * 记得的全部地点名，免得写错名字被当成已经忘掉。
 */
public final class RememberAbility implements AbilityModule {

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
                "记住或忘掉一个按名字叫的地点",
                AbilityDoc.forAbility("remember"),
                ParamSpecs.of(
                        ParamSpec.of("name", ParamType.TEXT).required()
                                .doc("地点名；首尾空白去掉后原样比较，不改大小写").build(),
                        ParamSpec.of("operation", ParamType.CHOICE).defaultValue("remember")
                                .choices("remember", "forget")
                                .doc("remember 记住（默认，同名覆盖），forget 忘掉").build()),
                Set.of(TargetKind.HERE, TargetKind.SEEN, TargetKind.LANDMARK, TargetKind.POSITION),
                ExecutionMode.MEMORY_ONLY,
                Set.of(),
                List.of(),
                Listing.LISTED);
    }

    @Override
    public StepDecision decide(StepContext step) {
        // 名字按规格先去首尾空白再原样比较；参数规格已保证非空。
        String name = step.goal().params().text("name").trim();
        if (wantsForget(step)) {
            return forget(name);
        }
        return remember(name, step.goal().target());
    }

    /** 要不要忘掉：没给 operation 时按默认的记住处理。 */
    private static boolean wantsForget(StepContext step) {
        return step.goal().params().has("operation")
                && "forget".equals(step.goal().params().text("operation"));
    }

    /** 忘掉：只忘名字对得上的那一个；没记过就失败并附上记得的全部地点名。 */
    private StepDecision forget(String name) {
        Optional<WorldPosition> previous = memory.place(name);
        if (previous.isEmpty()) {
            String rememberedNames = memory.places().keySet().isEmpty()
                    ? "（一个都没记过）" : String.join("、", memory.places().keySet());
            return new StepDecision.Finish(TaskResult.failed("没有叫「" + name + "」的记住的地点",
                    Problem.of(Problem.Kind.NOT_FOUND, "记得的地点有：" + rememberedNames,
                            "核对名字再忘；要记下它先用 operation=remember")));
        }
        return new StepDecision.Run(new RememberPlaceInput(name, null, previous.get(), true));
    }

    /** 记住：先把目标对象落实成带维度的位置，再决定是当场说"本来就这样"还是动手写。 */
    private StepDecision remember(String name, Target target) {
        Target kind = target == null ? new Target.Here() : target;
        // 位置解析可能就地给出失败结论：查不到的编号、没记过的地标、缺高度的坐标，都如实失败。
        if (kind instanceof Target.Seen seen) {
            Optional<WorldPosition> position = seenTargets.positionOf(seen.id());
            if (position.isEmpty()) {
                return gone(seen.id());
            }
            return plan(name, position.get());
        }
        if (kind instanceof Target.Landmark landmark) {
            // 给同一个地方再起一个名字：位置从已经记住的地点读。
            Optional<WorldPosition> position = memory.place(landmark.name());
            if (position.isEmpty()) {
                return new StepDecision.Finish(TaskResult.failed("没有叫「" + landmark.name() + "」的记住的地点",
                        Problem.of(Problem.Kind.NOT_FOUND, "记得的地点里没有这个名字",
                                "先用它的坐标或 here 记下来，或核对名字")));
            }
            return plan(name, position.get());
        }
        if (kind instanceof Target.Position position) {
            // 按坐标记必须给 y（D4）：记住的地点会被出行当作确认过的目的地，不存猜的高度。
            if (position.y() == null) {
                return new StepDecision.Finish(TaskResult.failed("按坐标记地点必须给 y",
                        Problem.of(Problem.Kind.INVALID_PARAMETER, "坐标缺 y",
                                "先走到那里再用 here 记，或把确认过的高度补上")));
            }
            String dimension = position.dimension() == null
                    ? characterPosition.currentPosition().dimension() : position.dimension();
            return plan(name, new WorldPosition(position.x(), position.y(), position.z(), dimension));
        }
        if (kind instanceof Target.Here) {
            return plan(name, characterPosition.currentPosition());
        }
        // player、direction、previous 不接受：玩家用它的观察编号走 seen；previous 内核还没记各步的位置。
        return new StepDecision.Finish(TaskResult.failed("记地点不接受这种目标对象",
                Problem.of(Problem.Kind.UNSUPPORTED, "接受的种类：here、seen、landmark、position",
                        "玩家在场景里有观察编号，用 seen 指它")));
    }

    /** 位置已落实：位置没变就直接完成，否则开一个写记忆的任务，覆盖前记在哪随输入带上。 */
    private StepDecision plan(String name, WorldPosition desired) {
        WorldPosition previous = memory.place(name).orElse(null);
        if (previous != null && previous.equals(desired)) {
            return new StepDecision.Finish(TaskResult.builder(TaskResult.Status.DONE,
                            "「" + name + "」本来就这样记着")
                    .details(new RememberPlaceTask.RememberDetails(name, "remember", desired, null))
                    .build());
        }
        return new StepDecision.Run(new RememberPlaceInput(name, desired, previous, false));
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
