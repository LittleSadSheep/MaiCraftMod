// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.endgame;

import java.util.LinkedHashSet;
import java.util.List;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** One bounded first-person goal: leave a real elytra in the main inventory. */
public final class SemanticElytraTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "obtain_elytra";
    public static final int MIN_SEARCH_DISTANCE = 128;
    public static final int MAX_SEARCH_DISTANCE = 4_096;
    public static final int DEFAULT_SEARCH_DISTANCE = 2_048;

    public final int maxSearchDistance;
    public final boolean mayAlterTerrain;
    public final boolean allowCombat;
    public final boolean allowRareConsumables;
    public final List<String> protectedLabels;

    static {
        TaskFactory.register(SemanticElytraTaskRecord.class,
                SemanticElytraCompanionTask::new);
    }

    public SemanticElytraTaskRecord(
            String toolCallId,
            long deadlineGameTime,
            int maxSearchDistance,
            boolean mayAlterTerrain,
            boolean allowCombat,
            List<String> protectedLabels) {
        this(toolCallId, deadlineGameTime, maxSearchDistance,
                mayAlterTerrain, allowCombat, false, protectedLabels);
    }

    public SemanticElytraTaskRecord(
            String toolCallId,
            long deadlineGameTime,
            int maxSearchDistance,
            boolean mayAlterTerrain,
            boolean allowCombat,
            boolean allowRareConsumables,
            List<String> protectedLabels) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.maxSearchDistance = Math.clamp(
                maxSearchDistance, MIN_SEARCH_DISTANCE, MAX_SEARCH_DISTANCE);
        this.mayAlterTerrain = mayAlterTerrain;
        this.allowCombat = allowCombat;
        this.allowRareConsumables = allowRareConsumables;
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        if (protectedLabels != null) {
            for (String label : protectedLabels) {
                if (label != null && !label.isBlank()) labels.add(label.strip());
            }
        }
        if (labels.size() > 64) {
            throw new IllegalArgumentException("protected_labels accepts at most 64 values");
        }
        this.protectedLabels = List.copyOf(labels);
    }

    /** Forces static TaskFactory registration during Mod initialization. */
    public static void ensureRegistered() {}

    @Override
    public String describe() {
        return "在末地寻找末地船并把鞘翅带进主背包"
                + (mayAlterTerrain ? "（允许开路）" : "")
                + (allowCombat ? "（允许处理明确敌对阻碍）" : "");
    }
}
