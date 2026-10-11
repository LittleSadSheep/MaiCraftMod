// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.maiwithu.maicraft.ability.vehicle.api.VehicleControls;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;
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
 * 看一辆载具（machine_inspect 指结构的观察编号 s#）：问各联动的读载具办法谁读得了，读一次交出 vehicle 一节——
 * 种类、座位、每个控制件输入接到谁与推出来的角色、姿态与接地，再合上操纵表（试过的、LLM 定的）查必需角色缺哪个。
 * 只读，读一刻就完。
 */
final class VehicleInspectTask extends PhasedTask<VehicleInspectTask.Phase> {

    enum Phase { READ }

    /**
     * 看哪辆。
     *
     * @param structureId 结构身份
     * @param seenId      指它的观察编号（说的时候用）
     */
    record Input(String structureId, String seenId) implements TaskInput {
        Input {
            Objects.requireNonNull(structureId, "structureId");
            Objects.requireNonNull(seenId, "seenId");
        }

        @Override public String describe() {
            return "看一眼载具 " + seenId;
        }
    }

    /** 结果细节：vehicle 一节与合并后的操纵表。 */
    record Details(VehicleReader.View vehicle, VehicleControls.ControlMap controlMap) implements ResultDetails {}

    private final Input input;
    private final MachineVehicles vehicles;
    private VehicleReader.View view;
    private VehicleControls.ControlMap controlMap;

    VehicleInspectTask(Input input, MachineVehicles vehicles) {
        super("看载具", Phase.READ, new ProgressTracker(100, 400));
        this.input = input;
        this.vehicles = vehicles;
    }

    @Override protected Action enter(Phase phase) {
        return null;
    }

    // 第一个读得了的来读；都读不了如实说。读到了一句话交代种类、座位、输入几个推出了角色。
    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        Optional<VehicleReader.View> read = vehicles.read(input.structureId());
        if (read.isPresent()) {
            view = read.get();
            controlMap = VehicleControls.merge(view.kind(), view.controls(), vehicles.controls().stored(view.structureId()));
            long known = view.controls().stream().filter(control -> !control.role().equals("unknown")).count();
            String roles = view.controls().stream().map(control -> control.control() + " " + control.role())
                    .collect(Collectors.joining("、"));
            return Next.done(TaskResult.done(input.seenId() + " 是 " + view.kind() + "：" + view.seats().size() + " 个座位、"
                    + view.controls().size() + " 个输入（推出角色的 " + known + " 个" + (roles.isEmpty() ? "" : "：" + roles)
                    + "），" + view.state().contact() + (view.complete() ? "" : "；没读全")
                    + (controlMap.missing().isEmpty() ? "；开它要的角色都齐了" : "；开它还缺：" + String.join("、", controlMap.missing()))));
        }
        return Next.fail(Problem.of(Problem.Kind.NOT_FOUND, input.seenId() + " 读不了：附近没有这个结构，或没有能读它的联动",
                "离近一点重新 observe 再指"));
    }

    @Override protected ResultDetails details() {
        return view == null ? ResultDetails.NONE : new Details(view, controlMap);
    }

    @Override protected String describePhase(Phase value) {
        return "读载具";
    }
}
