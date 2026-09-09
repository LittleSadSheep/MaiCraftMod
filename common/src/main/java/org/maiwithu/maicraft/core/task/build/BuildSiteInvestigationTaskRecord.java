// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.SemanticBuildPlanner;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 保存“找地块并完成建筑”的内部任务；输入是语义建造目标，禁止直接混入逐格 ops。
 * 活动半径固定为 384 格；最终位置只由实际建筑结果写入，不来自尚未验证的候选地块。
 */
public final class BuildSiteInvestigationTaskRecord extends TaskRecord
        implements InternalPositionReceipt {
    public static final String TOOL_NAME = SemanticBuildPlanner.SITE_INVESTIGATION_TOOL;
    public static final int MAX_DISTANCE = 384;
    /** 最初给两分钟；之后父任务跟随有进展的移动或建筑子任务延长，并非固定两分钟后必停。 */
    public static final long INITIAL_LIVENESS_LEASE_TICKS = 2L * 60L * 20L;

    public final Goal goal;
    public final int maxDistance;
    private Position verifiedPosition;
    private BuildTaskRecord projectPlan;

    void projectPlan(BuildTaskRecord plan) { projectPlan = plan; }
    public BuildTaskRecord projectPlan() { return projectPlan; }

    static {
        TaskFactory.register(BuildSiteInvestigationTaskRecord.class,
                BuildSiteInvestigationCompanionTask::new);
    }

    public BuildSiteInvestigationTaskRecord(
            String toolCallId, long deadlineGameTime, Goal goal) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        if (goal == null || !"maicraft:build".equals(goal.ability())) {
            throw new IllegalArgumentException("build-site investigation needs one semantic build goal");
        }
        if (goal.parameters().has("ops")) {
            throw new IllegalArgumentException("build-site investigation never accepts per-cell ops");
        }
        this.goal = goal;
        this.maxDistance = MAX_DISTANCE;
    }

    /** 调用空方法也会触发本类初始化，执行上面的静态工厂注册。 */
    public static void ensureRegistered() {}

    void retainVerifiedPosition(Position position) {
        this.verifiedPosition = position;
    }

    @Override
    public Position internalVerifiedPosition() {
        return verifiedPosition;
    }

    @Override
    public String describe() {
        return "实地勘察并完成语义建筑";
    }
}
