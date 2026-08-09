// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.progression;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * One semantic survival-progression goal.  Concrete prerequisites, entities, structures,
 * portal cells, routes and inventory positions are deliberately absent from this boundary.
 */
public final class ReachMilestoneTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "reach_milestone";
    public static final int MIN_SEARCH_DISTANCE = 128;
    public static final int MAX_SEARCH_DISTANCE = 4_096;
    public static final int DEFAULT_SEARCH_DISTANCE = 4_096;
    public static final int MIN_PORTAL_RADIUS = 16;
    public static final int MAX_PORTAL_RADIUS = 512;
    public static final int DEFAULT_PORTAL_RADIUS = 128;

    public enum Milestone {
        NETHER("nether"),
        STRONGHOLD("stronghold"),
        DEFEAT_DRAGON("defeat_dragon"),
        ELYTRA("elytra");

        private final String id;

        Milestone(String id) { this.id = id; }
        public String id() { return id; }

        public static Milestone parse(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("milestone is required");
            }
            String normalized = value.strip().toLowerCase(Locale.ROOT);
            for (Milestone candidate : values()) {
                if (candidate.id.equals(normalized)) return candidate;
            }
            throw new IllegalArgumentException(
                    "milestone must be nether, stronghold, defeat_dragon or elytra");
        }
    }

    public final Milestone milestone;
    public final int maxSearchDistance;
    public final int portalSearchRadius;
    public final float minimumHealth;
    public final boolean allowCombat;
    public final boolean allowRareConsumables;
    public final boolean mayAlterTerrain;
    public final List<SemanticAcquireTaskRecord.Source> allowedSources;
    public final SemanticMaterialSupplyCoordinator.MaterialPolicy materialPolicy;
    public final List<String> protectedLabels;

    public ReachMilestoneTaskRecord(
            String toolCallId,
            long deadlineGameTime,
            Milestone milestone,
            int maxSearchDistance,
            int portalSearchRadius,
            float minimumHealth,
            boolean allowCombat,
            boolean allowRareConsumables,
            boolean mayAlterTerrain,
            List<SemanticAcquireTaskRecord.Source> allowedSources,
            SemanticMaterialSupplyCoordinator.MaterialPolicy materialPolicy,
            List<String> protectedLabels) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.milestone = java.util.Objects.requireNonNull(milestone, "milestone");
        this.maxSearchDistance = Math.clamp(
                maxSearchDistance, MIN_SEARCH_DISTANCE, MAX_SEARCH_DISTANCE);
        this.portalSearchRadius = Math.clamp(
                portalSearchRadius, MIN_PORTAL_RADIUS, MAX_PORTAL_RADIUS);
        if (!Float.isFinite(minimumHealth) || minimumHealth < 1.0F
                || minimumHealth > 1024.0F) {
            throw new IllegalArgumentException("minimum_health must be between 1 and 1024");
        }
        this.minimumHealth = minimumHealth;
        this.allowCombat = allowCombat;
        this.allowRareConsumables = allowRareConsumables;
        this.mayAlterTerrain = mayAlterTerrain;
        this.allowedSources = allowedSources == null
                ? List.of() : List.copyOf(new LinkedHashSet<>(allowedSources));
        this.materialPolicy = materialPolicy == null
                ? SemanticMaterialSupplyCoordinator.MaterialPolicy.ORDINARY : materialPolicy;
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        if (protectedLabels != null) {
            for (String value : protectedLabels) {
                if (value != null && !value.isBlank()) labels.add(value.strip());
            }
        }
        if (labels.size() > 64) {
            throw new IllegalArgumentException("protected_labels accepts at most 64 values");
        }
        this.protectedLabels = List.copyOf(labels);
    }

    @Override
    public String describe() {
        return "推进到 " + milestone.id() + " 里程碑";
    }
}
