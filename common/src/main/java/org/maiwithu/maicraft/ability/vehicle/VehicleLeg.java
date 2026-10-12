// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import java.util.List;
import java.util.Optional;

import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;
import org.maiwithu.maicraft.behavior.travel.spi.TravelLeg;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 开载具的一段行程：走到驾驶位旁（够得着就跳过）→ 交给驾驶办法坐下、接管、开到目的地附近、停稳、交还。
 * 开到了算这一段做完；没开到（时间用完就地降落、半路停下）以"卡住"结束，行程从停下的地方接着规划；
 * 上不去、飞不起来、中途断开照驾驶办法的问题结束。驾驶办法在走到驾驶位以后才建——驾驶位按那时离人最近的算。
 * 被叫先到安全的地方再停：开着的交给驾驶办法就近降落、刹停，停稳了这一段以"按要求停下"结束；还没开起来的当场结束。
 */
final class VehicleLeg implements TravelLeg {

    private enum Step { BOARD, DRIVE, DONE }

    private final List<VehicleDriver> drivers;
    private final VehicleDriver.DriveRequest request;
    private Step step = Step.BOARD;
    private Action current;
    private VehicleDriver.Run run;
    /** 被叫先到安全的地方再停；被打断（暂停）时清掉，回来照原样开。 */
    private boolean stopping;

    VehicleLeg(List<VehicleDriver> drivers, VehicleDriver.DriveRequest request) {
        this.drivers = List.copyOf(drivers);
        this.request = request;
    }

    @Override public ActionStatus tick(TickContext context) {
        if (stopping) {
            ActionStatus stopped = stopHere();
            if (stopped != null) return stopped;
        }
        if (current == null) {
            ActionStatus started = startStep();
            if (started != null) return started;
        }
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        current.close();
        current = null;
        if (status instanceof ActionStatus.Failed failed) return failed;
        if (step == Step.BOARD) {
            step = Step.DRIVE;
            return ActionStatus.progressed();
        }
        step = Step.DONE;
        VehicleDriver.Report report = run.report();
        if (report != null && report.drove() != null && !report.drove().arrived()) {
            return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "开到半路停下了，离目的地还有 "
                    + report.drove().distanceLeft() + " 格" + (report.note().isBlank() ? "" : "：" + report.note())));
        }
        return ActionStatus.done();
    }

    // 这一步该做的动作：上船（够得着驾驶位就直接开），再交给驾驶办法开；没有认得这辆的驾驶办法如实说。
    private ActionStatus startStep() {
        if (step == Step.BOARD) {
            for (VehicleDriver driver : drivers) {
                Optional<Action> board = driver.board(request.structureId(), request.permissions());
                if (board.isPresent()) {
                    current = board.get();
                    return null;
                }
            }
            step = Step.DRIVE;
        }
        if (step == Step.DRIVE) {
            for (VehicleDriver driver : drivers) {
                Optional<VehicleDriver.Run> started = driver.drive(request);
                if (started.isPresent()) {
                    run = started.get();
                    current = run;
                    return null;
                }
            }
            return ActionStatus.failed(Problem.of(Problem.Kind.NOT_FOUND, "这辆载具上没有认得的控制件（无线红石打字机）"));
        }
        return ActionStatus.done();
    }

    @Override public void requestSafeStop() {
        // 开着的：飞艇就近找落点快降、车船就地刹住，停稳了再结束这一段；还没开起来的下一刻就结束。
        if (stopping) return;
        stopping = true;
        if (current != null && current == run) run.stopSafely();
    }

    // 按要求先停：驾驶办法还在往安全处停就接着推进；停稳了（或还没开起来）就收掉，这一段以停在半路结束，行程从这里接着规划。
    private ActionStatus stopHere() {
        if (step == Step.DRIVE && current != null && current == run && !run.stoppedSafely()) return null;
        if (step == Step.DONE) return null;
        if (current != null) {
            current.close();
            current = null;
        }
        step = Step.DONE;
        VehicleDriver.Report report = run == null ? null : run.report();
        String where = report == null || report.drove() == null ? "还没开起来"
                : "就近停下，离目的地还有 " + report.drove().distanceLeft() + " 格";
        return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "按要求先停下：" + where));
    }

    @Override public void pause() {
        // 暂停就是不用收尾了：回来照原样开（驾驶办法从停下的地方重新定一趟）。
        stopping = false;
        if (current != null) current.pause();
    }

    @Override public void close() {
        if (current != null) {
            current.close();
            current = null;
        }
    }

    @Override public Interruptibility interruptibility() {
        return current == null ? Interruptibility.BETWEEN_ACTIONS : current.interruptibility();
    }

    @Override public String summary() {
        VehicleDriver.Report report = run == null ? null : run.report();
        if (report == null || report.drove() == null) return "开" + kindName() + "去 " + target();
        VehicleDriver.Drove drove = report.drove();
        return "开" + kindName() + "去 " + target() + "：" + (drove.arrived() ? "开到了" : "没开到（差 " + drove.distanceLeft() + " 格）")
                + "，" + (drove.parked() ? "停稳了" : "没停稳") + "，挪了 " + drove.traveled() + " 格";
    }

    @Override public String describe() {
        return switch (step) {
            case BOARD -> "走到驾驶位旁" + (current == null ? "" : "：" + current.describe());
            case DRIVE -> current == null ? "开过去" : current.describe();
            case DONE -> "开完了";
        };
    }

    private String kindName() {
        return switch (request.kind()) {
            case "airship" -> "飞艇";
            case "car" -> "车";
            case "boat" -> "船";
            default -> "载具";
        };
    }

    private String target() {
        return Math.round(request.destination().x) + "," + Math.round(request.destination().z);
    }
}
