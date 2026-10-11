// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.maiwithu.maicraft.ability.vehicle.api.VehicleControls;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TaskInput;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 改一辆载具的操纵表（machine_configure 指结构的 s#）：settings 的键是输入（{@code 打字机:W}）、值是角色，
 * 或者键 forward 给车头朝结构自己的哪根水平轴。LLM 定的以它为准，试按不会盖掉；只写表，不碰方块。
 * 写完合上推出来的与试过的，查开这一类载具必需的角色缺哪个。
 */
final class VehicleControlsTask extends PhasedTask<VehicleControlsTask.Phase> {

    enum Phase { WRITE }

    /**
     * 改哪辆、改成什么。
     *
     * @param structureId 结构身份
     * @param seenId      指它的观察编号（说的时候用）
     * @param roles       输入 → 角色
     * @param forward     车头；不改为空串
     */
    record Input(String structureId, String seenId, Map<String, String> roles, String forward) implements TaskInput {
        Input {
            Objects.requireNonNull(structureId, "structureId");
            Objects.requireNonNull(seenId, "seenId");
            roles = Map.copyOf(roles);
            forward = forward == null ? "" : forward;
        }

        @Override public String describe() {
            return "改载具 " + seenId + " 的操纵表";
        }
    }

    /** 结果细节：改完的操纵表。 */
    record Details(VehicleControls.ControlMap controlMap) implements ResultDetails {}

    private final Input input;
    private final MachineVehicles vehicles;
    private VehicleControls.ControlMap controlMap;

    VehicleControlsTask(Input input, MachineVehicles vehicles) {
        super("改操纵表", Phase.WRITE, new ProgressTracker(100, 400));
        this.input = input;
        this.vehicles = vehicles;
    }

    /**
     * machine_configure 指 s# 时的决定：查结构还在不在、每项写得对不对（输入的值是角色之一，forward 是四根水平轴之一），
     * 都对就跑一次写表。
     */
    static StepDecision decide(String seenId, Map<String, String> settings, MachineVehicles vehicles) {
        Optional<String> structure = vehicles.structureOf().apply(seenId);
        if (structure.isEmpty()) {
            return new StepDecision.Finish(TaskResult.failed("指的结构不在了", Problem.of(Problem.Kind.TARGET_GONE,
                    "观察编号 " + seenId + " 指的结构已经不在了", "重新 observe 再指")));
        }
        Map<String, String> roles = new LinkedHashMap<>();
        String forward = "";
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            String value = entry.getValue().trim().toLowerCase(Locale.ROOT);
            if (entry.getKey().trim().equals("forward")) {
                if (!VehicleControls.AXES.contains(value)) {
                    return invalid("forward 写车头朝结构自己的哪根水平轴：" + String.join(" / ", VehicleControls.AXES));
                }
                forward = value;
            } else if (entry.getKey().contains(":") || entry.getKey().contains("：")) {
                if (!VehicleControls.ROLES.contains(value)) {
                    return invalid("「" + entry.getKey() + "」的角色写这些之一：" + String.join(" / ", VehicleControls.ROLES));
                }
                roles.put(entry.getKey().trim(), value);
            } else {
                return invalid("改载具的操纵表时，键写输入（例如 打字机:W，取自 machine_inspect 的 controls）或 forward，"
                        + "收到「" + entry.getKey() + "」");
            }
        }
        return new StepDecision.Run(new Input(structure.get(), seenId, roles, forward));
    }

    private static StepDecision invalid(String why) {
        return new StepDecision.Finish(TaskResult.failed("操纵表写法不对", Problem.of(Problem.Kind.INVALID_PARAMETER, why,
                null)));
    }

    @Override protected Action enter(Phase phase) {
        return null;
    }

    // 写表 → 读回合并 → 交代改了什么、还缺什么；存不住照实说。
    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        if (!vehicles.controls().assign(input.structureId(), input.roles(), input.forward())) {
            return Next.fail(Problem.of(Problem.Kind.INTERNAL_ERROR, "操纵表没存住（这个世界的文档库写不进去）"));
        }
        Optional<VehicleReader.View> view = vehicles.read(input.structureId());
        controlMap = VehicleControls.merge(view.map(VehicleReader.View::kind).orElse("unknown"),
                view.map(VehicleReader.View::controls).orElse(List.of()), vehicles.controls().stored(input.structureId()));
        String changed = input.roles().entrySet().stream().map(entry -> entry.getKey() + " " + entry.getValue())
                .collect(Collectors.joining("、"));
        String head = input.forward().isEmpty() ? "" : (changed.isEmpty() ? "" : "，") + "车头 " + input.forward();
        return Next.done(TaskResult.done("操纵表改好了：" + changed + head
                + (controlMap.missing().isEmpty() ? "；开它要的角色都齐了" : "；还缺：" + String.join("、", controlMap.missing()))));
    }

    @Override protected ResultDetails details() {
        return controlMap == null ? ResultDetails.NONE : new Details(controlMap);
    }

    @Override protected String describePhase(Phase value) {
        return "写操纵表";
    }
}
