// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.SemanticBuildPlanner;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.TaskRecord;

/** Internal semantic build-site investigation. No generated position is an input or receipt. */
public final class BuildSiteInvestigationTaskRecord extends TaskRecord
        implements InternalPositionReceipt {
    public static final String TOOL_NAME = SemanticBuildPlanner.SITE_INVESTIGATION_TOOL;
    public static final int MAX_DISTANCE = 384;
    public static final int MAX_FRONTIER_LEGS = 12;
    public static final long MAX_INVESTIGATION_TICKS = 12L * 60L * 20L;
    public static final long MAX_TOTAL_TICKS = 60L * 60L * 20L;

    public final Goal goal;
    public final int maxDistance;
    public final int maxFrontierLegs;
    public final long maxInvestigationTicks;
    private Position verifiedPosition;

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
        this.maxFrontierLegs = MAX_FRONTIER_LEGS;
        this.maxInvestigationTicks = MAX_INVESTIGATION_TICKS;
    }

    /** Forces runner registration during Mod initialization. */
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
