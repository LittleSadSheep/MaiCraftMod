// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 试按一次：问各联动的驾驶办法谁开得了这辆 → 交给它坐下、接管、逐条按、松开 → 照它的经过结算。
 * 按了、松开了就算做完，载具动成什么样写进结果由 LLM 看；动作本身做不下去（坐不上、接管不了）才失败，
 * 失败时已经按过的几条照样写进细节。
 */
final class VehicleDriveTask extends PhasedTask<VehicleDriveTask.Phase> {

    enum Phase { FIND, RUN }

    private final VehicleDriver.TestRequest request;
    private final List<VehicleDriver> drivers;
    private VehicleDriver.Run run;

    VehicleDriveTask(VehicleDriver.TestRequest request, List<VehicleDriver> drivers) {
        super("试按载具", Phase.FIND, new ProgressTracker(20L * 60, 20L * 60 * 5));
        this.request = request;
        this.drivers = List.copyOf(drivers);
    }

    @Override protected Action enter(Phase phase) {
        return phase == Phase.RUN ? run : null;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case FIND -> find();
            case RUN -> runActionThen(context, this::settle);
        };
    }

    // 问各联动谁开得了这辆：第一个答得上的来做；都答不上如实说上面没有认得的控制件。
    private Next<Phase> find() {
        for (VehicleDriver driver : drivers) {
            Optional<VehicleDriver.Run> started = driver.test(request);
            if (started.isPresent()) {
                run = started.get();
                return Next.go(Phase.RUN, "去驾驶座接管控制件");
            }
        }
        return Next.fail(Problem.of(Problem.Kind.NOT_FOUND, "这辆载具上没有认得的控制件（无线红石打字机）",
                "observe(detail) 看结构的 controls；无线红石打字机要装在结构上"));
    }

    // 结算：按了几条、松开没有、交还没有，载具的反应逐条写。
    private Next<Phase> settle() {
        VehicleDriver.Report report = run.report();
        if (report == null) return Next.fail(Problem.of(Problem.Kind.INTERNAL_ERROR, "试按做完了却没有交代经过"));
        long pressed = report.tried().stream().filter(VehicleDriver.Tried::pressed).count();
        String each = report.tried().stream()
                .map(tried -> tried.control() + (tried.pressed() ? " → " + tried.response() : "：没按，" + tried.note()))
                .collect(Collectors.joining("；"));
        String ending = (report.released() ? "键都松开了" : "有键没确认松开")
                + (report.disconnected() ? "，控制件已交还" : "，控制件还没交还");
        return Next.done(TaskResult.done("试按了 " + pressed + "/" + report.tried().size() + " 条：" + each + "。" + ending));
    }

    @Override protected ResultDetails details() {
        VehicleDriver.Report report = run == null ? null : run.report();
        return report == null ? ResultDetails.NONE : VehicleDriveDetails.of(report);
    }

    @Override protected String describePhase(Phase value) {
        return value == Phase.FIND ? "找控制件" : "试按";
    }
}
