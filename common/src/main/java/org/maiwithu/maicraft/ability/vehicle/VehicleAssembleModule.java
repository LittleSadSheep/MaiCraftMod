// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.vehicle.spi.VehicleAssembler;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.behavior.travel.LandmarkResolver;
import org.maiwithu.maicraft.behavior.travel.ReadsSeenTargets;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.ability.RequiredMod;
import org.maiwithu.maicraft.kernel.goal.Goal;
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
 * vehicle_assemble：把建好、胶好的方块组装成会动的结构（物理组装器拉一次杆），或者拆回方块。
 * 能力只落实"指的是哪一个"——组装器的坐标、记过的地点、看到的东西（b#），或结构的观察编号（s#）——
 * 怎么走过去、拉、确认转换没有由联动模组的组装办法做。拉过一次就不再拉（再拉会拆回去），没转换照实说。
 */
public final class VehicleAssembleModule implements AbilityModule {

    /** 需要的模组：物理组装器（Simulated，它本身要机械动力）与车站组车都要机械动力。 */
    static final String REQUIRED_MOD = "create";

    private final List<VehicleAssembler> assemblers;
    private final ReadsSeenTargets seen;
    private final Function<String, Optional<String>> structures;
    private final ReadsRememberedPlaces places;
    private final Supplier<BlockPos> feet;

    /**
     * @param structures 观察编号 s# 换成结构身份
     * @param feet       角色此刻脚下（坐标没给 y 时按它）
     */
    public VehicleAssembleModule(List<VehicleAssembler> assemblers, ReadsSeenTargets seen,
            Function<String, Optional<String>> structures, ReadsRememberedPlaces places, Supplier<BlockPos> feet) {
        this.assemblers = List.copyOf(assemblers);
        this.seen = Objects.requireNonNull(seen, "seen");
        this.structures = Objects.requireNonNull(structures, "structures");
        this.places = Objects.requireNonNull(places, "places");
        this.feet = Objects.requireNonNull(feet, "feet");
    }

    @Override public AbilitySpec spec() {
        return new AbilitySpec("maicraft:vehicle_assemble",
                "把建好胶好的方块组装成会动的结构（物理组装器拉一次杆），或拆回方块",
                AbilityDoc.forAbility("vehicle_assemble"),
                ParamSpecs.of(ParamSpec.of("operation", ParamType.CHOICE).choices("assemble", "disassemble")
                        .defaultValue("assemble").doc("assemble 组装（默认）/ disassemble 拆回方块").build()),
                Set.of(TargetKind.POSITION, TargetKind.SEEN, TargetKind.LANDMARK),
                ExecutionMode.CONTROLS_PLAYER,
                Set.of(RequiredMod.of(REQUIRED_MOD)),
                List.of(),
                Listing.LISTED);
    }

    // 落实指的是哪一个：s# 是结构（拆回用），别的落成组装器大概所在的那一格。
    @Override public StepDecision decide(StepContext step) {
        Goal goal = step.goal();
        boolean assemble = !"disassemble".equals(goal.params().text("operation"));
        if (assemblers.isEmpty()) {
            return new StepDecision.Finish(TaskResult.failed("组装不了", Problem.of(Problem.Kind.UNSUPPORTED,
                    "没有接上组装的联动（Simulated 装了，但它的联动没登记成功）")));
        }
        Target target = goal.target();
        if (target == null) {
            return invalid("要说组装哪一个：给物理组装器的坐标、记过的地点，或结构的观察编号 s#");
        }
        String structure = null;
        BlockPos near;
        if (target instanceof Target.Seen observed) {
            Optional<WorldPosition> at = seen.positionOf(observed.id());
            if (at.isEmpty()) {
                return new StepDecision.Finish(TaskResult.failed("指的东西不在了", Problem.of(Problem.Kind.TARGET_GONE,
                        "观察编号 " + observed.id() + " 指的东西已经不在了", "重新 observe 再指")));
            }
            structure = structures.apply(observed.id()).orElse(null);
            near = new BlockPos(at.get().x(), at.get().y(), at.get().z());
        } else if (target instanceof Target.Position position) {
            near = new BlockPos(position.x(), position.y() != null ? position.y() : feet.get().getY(), position.z());
        } else if (target instanceof Target.Landmark landmark) {
            // 地点名：记过的地点优先，没记过认附近唯一写着它的告示牌，在牌子那一格附近组装；都落不了就说清为什么。
            var located = LandmarkResolver.resolve(landmark.name(), places, seen::signs);
            if (located instanceof LandmarkResolver.Resolution.Missing missing) {
                return new StepDecision.Finish(TaskResult.failed("没记过这个地方", Problem.of(Problem.Kind.NOT_FOUND,
                        missing.reason(), missing.suggestionOr("改用坐标或观察编号"))));
            }
            WorldPosition at = ((LandmarkResolver.Resolution.Found) located).position();
            near = new BlockPos(at.x(), at.y(), at.z());
        } else {
            return invalid("组装只认坐标、记过的地点或观察编号");
        }
        return new StepDecision.Run(new VehicleAssembleInput(new VehicleAssembler.Request(assemble, near, structure,
                goal.permissions())));
    }

    @Override public void registerTasks(TaskFactories factories) {
        factories.register(VehicleAssembleInput.class, input -> new VehicleAssembleTask(input.request(), assemblers));
    }

    private static StepDecision invalid(String why) {
        return new StepDecision.Finish(TaskResult.failed("参数不对：" + why, Problem.of(Problem.Kind.INVALID_PARAMETER, why,
                null)));
    }
}
