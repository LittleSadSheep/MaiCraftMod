// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.endgame;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * Semantic permission and safety envelope for one observed Ender Dragon encounter.
 * Concrete entities, routes, block cells and inventory choices never cross this record's
 * tool boundary; the first-person executor derives them again from the live client world.
 */
public final class DragonFightTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "dragon_fight";
    public static final float DEFAULT_MINIMUM_HEALTH = 10.0F;
    public static final float MAXIMUM_HEALTH_SETTING = 1024.0F;
    public static final int MAX_PROTECTED_LABELS = 64;

    public final boolean allowCombat;
    public final boolean mayAlterTerrain;
    public final boolean allowRareConsumables;
    public final float minimumHealth;
    public final List<String> protectedLabels;

    static {
        TaskFactory.register(DragonFightTaskRecord.class, DragonFightCompanionTask::new);
    }

    public DragonFightTaskRecord(
            String toolCallId,
            long deadlineGameTime,
            boolean allowCombat,
            boolean mayAlterTerrain,
            float minimumHealth,
            List<String> protectedLabels) {
        this(toolCallId, deadlineGameTime, allowCombat, mayAlterTerrain, false,
                minimumHealth, protectedLabels);
    }

    public DragonFightTaskRecord(
            String toolCallId,
            long deadlineGameTime,
            boolean allowCombat,
            boolean mayAlterTerrain,
            boolean allowRareConsumables,
            float minimumHealth,
            List<String> protectedLabels) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        if (!allowCombat) {
            throw new IllegalArgumentException(
                    "dragon_fight requires allow_combat=true; this encounter can destroy "
                            + "crystals and kill the Ender Dragon");
        }
        if (!Float.isFinite(minimumHealth)
                || minimumHealth < 1.0F
                || minimumHealth > MAXIMUM_HEALTH_SETTING) {
            throw new IllegalArgumentException(
                    "minimum_health must be between 1 and " + MAXIMUM_HEALTH_SETTING);
        }
        this.allowCombat = true;
        this.mayAlterTerrain = mayAlterTerrain;
        this.allowRareConsumables = allowRareConsumables;
        this.minimumHealth = minimumHealth;

        LinkedHashSet<String> labels = new LinkedHashSet<>();
        if (protectedLabels != null) {
            for (String raw : protectedLabels) {
                if (raw == null || raw.isBlank()) continue;
                labels.add(raw.trim());
                if (labels.size() > MAX_PROTECTED_LABELS) {
                    throw new IllegalArgumentException(
                            "protected_labels accepts at most " + MAX_PROTECTED_LABELS + " labels");
                }
            }
        }
        this.protectedLabels = List.copyOf(new ArrayList<>(labels));
    }

    @Override
    public String describe() {
        return "处理末影龙战斗（最低生命 " + minimumHealth + "）";
    }
}
