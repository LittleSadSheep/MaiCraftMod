// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 接网络的能力：把 target 那台机器接进 source 所在的那种网。已经同网就不动；没接上时按那种网的联动给的线材
 * 规划一条线路（直线优先、少拐弯、远处用链条这类跨越），写成机器蓝图交给施工铺好，再核对两端同网与运行读数。
 * source 不给就在 radius 内找在转的那张网。不会接线的网如实说铺不了，不猜着铺。
 */
final class MachineConnectModule implements AbilityModule {

    private final MachineServices services;
    private final MachineArchives archives;
    private final WorldMemory memory;
    private final Function<Permissions, ConstructionServices> construction;
    private final AbilitySpec spec = new AbilitySpec(
            ModIdentity.MOD_ID + ":machine_connect",
            "把 target 那台机器接进一张网络（应力、ME、能量……按已装的联动）：已经接着就不动，"
                    + "没接上就规划线路、铺好再核对两端同网与运行读数",
            AbilityDoc.forAbility("machine_connect"),
            ParamSpecs.of(
                    ParamSpec.of("network", ParamType.TEXT).required()
                            .doc("接哪种网：取值由已登记的网络读取器自报，例如 kinetic（应力）、me、energy").build(),
                    ParamSpec.of("source", ParamType.TEXT)
                            .doc("接到哪：写法与 target 相同（观察编号、[x,y,z] 坐标、名字）；不给就在 radius 内找").build(),
                    ParamSpec.of("radius", ParamType.INTEGER).defaultValue(32)
                            .doc("没给 source 时找来源的范围，单位格").build()),
            Set.of(TargetKind.HERE, TargetKind.SEEN, TargetKind.LANDMARK, TargetKind.POSITION),
            ExecutionMode.CONTROLS_PLAYER,
            Set.of(),
            List.of(),
            Listing.LISTED);

    MachineConnectModule(MachineServices services, MachineArchives archives, WorldMemory memory,
            Function<Permissions, ConstructionServices> construction) {
        this.services = Objects.requireNonNull(services, "services");
        this.archives = Objects.requireNonNull(archives, "archives");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.construction = Objects.requireNonNull(construction, "construction");
    }

    @Override public AbilitySpec spec() {
        return spec;
    }

    // 网络种类与 source 的写法在这里一次核清；目标是档案名时把档案带上，接上了好记进档案。
    @Override public StepDecision decide(StepContext step) {
        var params = step.goal().params();
        String kind = params.text("network");
        boolean registered = services.networkReaders().stream()
                .anyMatch(reader -> reader.kind().id().equals(kind));
        if (!registered) {
            return new StepDecision.Finish(TaskResult.failed("没有登记 " + kind + " 这种网络的读取器",
                    Problem.of(Problem.Kind.INVALID_PARAMETER, "没有登记 " + kind + " 这种网络的读取器",
                            services.networkReaders().isEmpty()
                                    ? "这个实例没有登记任何网络联动；装了对应模组后这些取值才出现"
                                    : "可选值：" + services.networkReaders().stream()
                                            .map(reader -> reader.kind().id()).reduce((a, b) -> a + " / " + b).orElse(""))));
        }
        Optional<Target> source = Optional.empty();
        if (params.has("source")) {
            source = parseSource(params.text("source"));
            if (source.isEmpty()) {
                return new StepDecision.Finish(TaskResult.failed("source 写法不对",
                        Problem.of(Problem.Kind.INVALID_PARAMETER,
                                "source 要是观察编号（b3）、坐标 [x,y,z] 或一个名字",
                                "和 target 同一种写法；不给就在 radius 内找")));
            }
        }
        MachineArchive archive = step.goal().target() instanceof Target.Landmark landmark
                ? archives.find(landmark.name()).orElse(null)
                : null;
        int radius = params.has("radius") ? (int) params.integer("radius") : 32;
        return new StepDecision.Run(new MachineConnectInput(kind, step.goal().target(), source, radius,
                archive, step.goal().permissions()));
    }

    /** source 的三种写法：观察编号、坐标、名字；都不是给空。 */
    private Optional<Target> parseSource(String text) {
        String trimmed = text.trim();
        if (trimmed.matches("(?i)[bme]\\d+")) {
            return Optional.of(new Target.Seen(trimmed.toLowerCase(Locale.ROOT)));
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            String[] parts = trimmed.substring(1, trimmed.length() - 1).split(",");
            if (parts.length == 3) {
                try {
                    return Optional.of(new Target.Position(Integer.parseInt(parts[0].trim()),
                            Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()), null));
                } catch (NumberFormatException notCoordinates) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
        }
        if (!trimmed.isEmpty()) {
            return Optional.of(new Target.Landmark(trimmed));
        }
        return Optional.empty();
    }

    @Override public void registerTasks(TaskFactories factories) {
        // 铺线路用按蓝图施工的同一套任务：施工服务按这次任务的许可拼一份。
        factories.register(MachineConnectInput.class,
                input -> new MachineConnectTask(input, services, archives, memory, construction));
    }
}
