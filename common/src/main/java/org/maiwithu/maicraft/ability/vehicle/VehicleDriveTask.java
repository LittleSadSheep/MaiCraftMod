// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.maiwithu.maicraft.ability.vehicle.api.VehicleControls;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 试按一次：问各联动的驾驶办法谁开得了这辆 → 交给它坐下、接管、逐条按、松开 → 照它的经过结算，
 * 并按每条的效果认出角色记进操纵表（试过的；LLM 定过的那条不动），车头没定时按动力那一条定。
 * 按了、松开了就算做完，载具动成什么样写进结果由 LLM 看；动作本身做不下去（坐不上、接管不了）才失败，
 * 失败时已经按过的几条照样写进细节，但不记进操纵表。
 */
final class VehicleDriveTask extends PhasedTask<VehicleDriveTask.Phase> {

    enum Phase { FIND, RUN }

    private final VehicleDriver.TestRequest request;
    private final List<VehicleDriver> drivers;
    private final List<VehicleReader> readers;
    private final VehicleControls controls;
    private VehicleDriver.Run run;
    private VehicleControls.ControlMap controlMap;

    VehicleDriveTask(VehicleDriver.TestRequest request, List<VehicleDriver> drivers, List<VehicleReader> readers,
            VehicleControls controls) {
        super("试按载具", Phase.FIND, new ProgressTracker(20L * 60, 20L * 60 * 5));
        this.request = request;
        this.drivers = List.copyOf(drivers);
        this.readers = List.copyOf(readers);
        this.controls = controls;
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

    // 结算：按了几条、松开没有、交还没有，载具的反应逐条写；每条认出的角色记进操纵表。
    private Next<Phase> settle() {
        VehicleDriver.Report report = run.report();
        if (report == null) return Next.fail(Problem.of(Problem.Kind.INTERNAL_ERROR, "试按做完了却没有交代经过"));
        String recorded = record(report);
        long pressed = report.tried().stream().filter(VehicleDriver.Tried::pressed).count();
        String each = report.tried().stream()
                .map(tried -> tried.control() + (tried.pressed() ? " → " + tried.response() : "：没按，" + tried.note()))
                .collect(Collectors.joining("；"));
        String ending = (report.released() ? "键都松开了" : "有键没确认松开")
                + (report.disconnected() ? "，控制件已交还" : "，控制件还没交还");
        return Next.done(TaskResult.done("试按了 " + pressed + "/" + report.tried().size() + " 条：" + each + "。" + ending
                + recorded));
    }

    // 每条按过的认出角色，记进操纵表（试过的）；车头没定时按认成动力的那一条定。读不到种类按飞艇的叫法记偏航。
    private String record(VehicleDriver.Report report) {
        Optional<VehicleReader.View> view = readers.stream().map(reader -> reader.read(report.structureId()))
                .flatMap(Optional::stream).findFirst();
        String kind = view.map(VehicleReader.View::kind).orElse("unknown");
        String forward = controls.stored(report.structureId()).forward();
        String found = "";
        List<VehicleControls.Entry> entries = new ArrayList<>();
        for (VehicleDriver.Tried tried : report.tried()) {
            if (!tried.pressed() || tried.effect() == null) continue;
            TestedRoles.Verdict verdict = TestedRoles.of(tried.effect(), kind, forward);
            if (!verdict.forward().isEmpty()) {
                forward = verdict.forward();
                found = verdict.forward();
            }
            entries.add(new VehicleControls.Entry(tried.control(), verdict.role(), VehicleControls.TESTED, verdict.note()));
        }
        if (entries.isEmpty()) return "";
        boolean saved = controls.tested(report.structureId(), entries, found);
        controlMap = VehicleControls.merge(kind, view.map(VehicleReader.View::controls).orElse(List.of()),
                controls.stored(report.structureId()));
        String roles = entries.stream().map(entry -> entry.control() + " " + entry.role()).collect(Collectors.joining("、"));
        return "。操纵表记下：" + roles + (found.isEmpty() ? "" : "，车头 " + found) + (saved ? "" : "（没存住）")
                + (controlMap.missing().isEmpty() ? "" : "；还缺：" + String.join("、", controlMap.missing()));
    }

    @Override protected ResultDetails details() {
        VehicleDriver.Report report = run == null ? null : run.report();
        return report == null ? ResultDetails.NONE : VehicleDriveDetails.of(report, controlMap);
    }

    @Override protected String describePhase(Phase value) {
        return value == Phase.FIND ? "找控制件" : "试按";
    }
}
