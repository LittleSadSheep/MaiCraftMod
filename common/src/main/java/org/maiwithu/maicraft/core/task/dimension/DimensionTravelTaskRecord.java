// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** One semantic request to reach another dimension through an observed physical portal. */
public final class DimensionTravelTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "dimension_travel";
    public static final int MIN_RADIUS = 16;
    public static final int MAX_RADIUS = 512;

    static {
        TaskFactory.register(DimensionTravelTaskRecord.class, DimensionTravelCompanionTask::new);
    }

    public final String destinationDimension;
    public final int searchRadius;
    public final boolean mayAlterTerrain;
    public final PortalPreparationPolicy preparation;

    public DimensionTravelTaskRecord(
            String toolCallId,
            long deadlineGameTime,
            String destinationDimension,
            int searchRadius,
            boolean mayAlterTerrain) {
        this(toolCallId, deadlineGameTime, destinationDimension, searchRadius, mayAlterTerrain, PortalPreparationPolicy.DISABLED);
    }

    public DimensionTravelTaskRecord(String toolCallId, long deadlineGameTime, String destinationDimension,
                                     int searchRadius, boolean mayAlterTerrain, PortalPreparationPolicy preparation) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        ResourceLocation parsed = ResourceLocation.tryParse(destinationDimension);
        if (parsed == null) throw new IllegalArgumentException("destination_dimension must be a namespaced id");
        this.destinationDimension = parsed.toString();
        this.searchRadius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, searchRadius));
        this.mayAlterTerrain = mayAlterTerrain;
        this.preparation = java.util.Objects.requireNonNull(preparation);
    }

    @Override
    public String describe() {
        return "travel to " + destinationDimension;
    }
}
