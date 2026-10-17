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
 * 要受力时再问服务端（要几刻）：开着动力与关着动力两种静态情况往哪边倒、升力比重量，要配重再给几处位置；
 * 最多等 10 秒，问不到照实写原因，载具的其余部分照样交。只读。
 */
final class VehicleInspectTask extends PhasedTask<VehicleInspectTask.Phase> {

    enum Phase { READ, BALANCE }

    /** 等受力的回答最多几刻。 */
    static final int BALANCE_TICKS = 200;

    /**
     * 看哪辆。
     *
     * @param structureId 结构身份
     * @param seenId      指它的观察编号（说的时候用）
     * @param balance     要不要受力：forces 只看受力 / ballast 再给配重建议；不要为 null
     */
    record Input(String structureId, String seenId, String balance) implements TaskInput {
        Input {
            Objects.requireNonNull(structureId, "structureId");
            Objects.requireNonNull(seenId, "seenId");
        }

        @Override public String describe() {
            return "看一眼载具 " + seenId;
        }
    }

    /** 结果细节：vehicle 一节、合并后的操纵表、受力（没要为 null）。 */
    record Details(VehicleReader.View vehicle, VehicleControls.ControlMap controlMap, VehicleReader.Balance balance)
            implements ResultDetails {}

    private final Input input;
    private final MachineVehicles vehicles;
    private VehicleReader.View view;
    private VehicleControls.ControlMap controlMap;
    private VehicleReader.BalanceQuery query;
    private VehicleReader.Balance balance;
    private String summary;
    private int waited;

    VehicleInspectTask(Input input, MachineVehicles vehicles) {
        super("看载具", Phase.READ, new ProgressTracker(100, 400));
        this.input = input;
        this.vehicles = vehicles;
    }

    @Override protected Action enter(Phase phase) {
        return null;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return phase == Phase.READ ? read() : balance();
    }

    // 第一个读得了的来读；都读不了如实说。读到了一句话交代种类、座位、输入几个推出了角色；要受力就去问。
    private Next<Phase> read() {
        Optional<VehicleReader.View> read = vehicles.read(input.structureId());
        if (read.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.NOT_FOUND, input.seenId() + " 读不了：附近没有这个结构，或没有能读它的联动",
                    "离近一点重新 observe 再指"));
        }
        view = read.get();
        controlMap = VehicleControls.merge(view.kind(), view.controls(), vehicles.controls().stored(view.structureId()));
        long known = view.controls().stream().filter(control -> !control.role().equals("unknown")).count();
        String roles = view.controls().stream().map(control -> control.control() + " " + control.role())
                .collect(Collectors.joining("、"));
        summary = input.seenId() + " 是 " + view.kind() + "：" + view.seats().size() + " 个座位、"
                + view.controls().size() + " 个输入（推出角色的 " + known + " 个" + (roles.isEmpty() ? "" : "：" + roles)
                + "），" + view.state().contact() + (view.complete() ? "" : "；没读全")
                + (controlMap.missing().isEmpty() ? "；开它要的角色都齐了" : "；开它还缺：" + String.join("、", controlMap.missing()));
        if (input.balance() == null) return Next.done(TaskResult.done(summary));
        query = vehicles.readers().stream().map(reader -> reader.balance(input.structureId(), "ballast".equals(input.balance())))
                .flatMap(Optional::stream).findFirst().orElse(null);
        if (query == null) {
            balance = unknown("没有能问这辆受力的联动");
            return Next.done(TaskResult.done(summary + "；受力读不了：" + balance.why()));
        }
        return Next.go(Phase.BALANCE, "问服务端受力");
    }

    // 等受力的回答：答上了一句话说两种情况；10 秒还没答上照实说。
    private Next<Phase> balance() {
        Optional<VehicleReader.Balance> answer = query.poll();
        if (answer.isEmpty()) {
            if (++waited < BALANCE_TICKS) return Next.stay();
            balance = unknown("问了 " + BALANCE_TICKS / 20 + " 秒服务端还没答上");
            return Next.done(TaskResult.done(summary + "；受力：" + balance.why()));
        }
        balance = answer.get();
        if (!balance.known()) return Next.done(TaskResult.done(summary + "；受力读不了：" + balance.why()));
        String cases = balance.cases().stream().map(found -> found.name() + found.tendency()).collect(Collectors.joining("；"));
        return Next.done(TaskResult.done(summary + "；受力：" + cases + "，升力比重量 " + balance.liftRatio()
                + (balance.ballast().isEmpty() ? "" : "；配重建议 " + balance.ballast().size() + " 处")));
    }

    private static VehicleReader.Balance unknown(String why) {
        return new VehicleReader.Balance(false, why, 0, 0, false, List.of(), List.of(), "");
    }

    @Override protected ResultDetails details() {
        return view == null ? ResultDetails.NONE : new Details(view, controlMap, balance);
    }

    @Override protected String describePhase(Phase value) {
        return value == Phase.READ ? "读载具" : "问受力";
    }
}
