// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import java.util.List;
import java.util.Optional;

import org.maiwithu.maicraft.ability.vehicle.spi.VehicleAssembler;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 组装或拆回一次：问各联动的组装办法谁能做 → 交给它走过去、拉一次、确认 → 照它的结局结算。
 * 已经是要的样子就直接完成；拉了没转换照实完成并写明（不判失败），动作本身做不下去才失败。
 */
final class VehicleAssembleTask extends PhasedTask<VehicleAssembleTask.Phase> {

    enum Phase { FIND, RUN }

    private final VehicleAssembler.Request request;
    private final List<VehicleAssembler> assemblers;
    private VehicleAssembler.Run run;
    private VehicleAssembler.Conversion conversion;

    VehicleAssembleTask(VehicleAssembler.Request request, List<VehicleAssembler> assemblers) {
        super(request.assemble() ? "组装载具" : "拆回载具", Phase.FIND, new ProgressTracker(20L * 60, 20L * 60 * 5));
        this.request = request;
        this.assemblers = List.copyOf(assemblers);
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

    // 问各联动谁能做这次组装：第一个答得上的来做；都答不上如实说附近没有组装器。
    private Next<Phase> find() {
        for (VehicleAssembler assembler : assemblers) {
            Optional<VehicleAssembler.Run> started = assembler.start(request);
            if (started.isPresent()) {
                run = started.get();
                return Next.go(Phase.RUN, request.assemble() ? "找到组装器，去拉杆" : "找到组装器，去拆回");
            }
        }
        return Next.fail(Problem.of(Problem.Kind.NOT_FOUND, request.assemble()
                ? request.near().toShortString() + " 附近找不到物理组装器"
                : (request.structureId() == null ? request.near().toShortString() + " 附近" : "指的结构上") + "找不到物理组装器",
                "物理组装器要贴着要组装的方块放；指组装器的坐标或结构的观察编号"));
    }

    // 结算：已经是要的样子、真的转换了、拉了没转换，三种都算做完，结果里分开写。
    private Next<Phase> settle() {
        conversion = run.conversion();
        if (conversion == null) return Next.fail(Problem.of(Problem.Kind.INTERNAL_ERROR, "组装做完了却没有交代结局"));
        String what = request.assemble() ? "组装" : "拆回";
        if (conversion.alreadyDone()) {
            return Next.done(TaskResult.done(request.assemble() ? "已经组装过，是一个会动的结构了" : "已经是方块了，不用拆"));
        }
        if (conversion.converted()) {
            return Next.done(TaskResult.done(what + "好了" + (conversion.blocks() > 0 ? "，" + conversion.blocks() + " 格方块" : "")));
        }
        return Next.done(TaskResult.done("拉了一次杆，但没有" + (request.assemble() ? "变成会动的结构" : "拆回方块")
                + (conversion.note().isBlank() ? "" : "：" + conversion.note())));
    }

    @Override protected ResultDetails details() {
        return conversion == null ? ResultDetails.NONE
                : new VehicleAssembleDetails(conversion.structureId(), conversion.alreadyDone(), conversion.converted(),
                        conversion.blocks(), conversion.note());
    }

    @Override protected String describePhase(Phase value) {
        return value == Phase.FIND ? "找组装器" : (request.assemble() ? "拉杆组装" : "拉杆拆回");
    }
}
