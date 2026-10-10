// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;

import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 审阅机器蓝图的任务：蓝图在计划阶段就解析好了，这里只跑一遍审阅判断并交出报告。
 * 只读分析，不动角色；审阅报了问题也不拦着建，那不是它的职责。
 */
final class MachineReviewTask extends PhasedTask<MachineReviewTask.Phase> {

    /** 只有一个阶段：审。 */
    enum Phase { REVIEW }

    private final MachineReviewInput input;
    private final MachineServices services;
    private MachineReview.Report report;

    MachineReviewTask(MachineReviewInput input, MachineServices services) {
        super("审阅机器蓝图", Phase.REVIEW, new ProgressTracker(200, 1200));
        this.input = input;
        this.services = services;
    }

    @Override protected Action enter(Phase phase) {
        return null;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        var backpack = context.player() == null ? null : context.player().backpack();
        report = MachineReview.review(input.blueprint(), services, backpack);
        recordProgress("审完了：" + report.issues().size() + " 条发现，"
                + report.materials().size() + " 种材料");
        return Next.done(TaskResult.done(summary()));
    }

    private String summary() {
        long missing = report.materials().stream().filter(material -> material.missing() > 0).count();
        StringBuilder line = new StringBuilder(input.what()).append("：")
                .append(report.issues().size()).append(" 条发现");
        if (missing > 0) {
            line.append("，").append(missing).append(" 种材料身上不够");
        }
        line.append("。审阅不是开工的门，报了问题也可以照建");
        return line.toString();
    }

    @Override protected ResultDetails details() {
        return report == null ? ResultDetails.NONE
                : new MachineReviewDetails(report.issues(), report.materials(),
                        report.power(), report.channels());
    }

    @Override protected String describePhase(Phase value) {
        return "审蓝图";
    }
}
