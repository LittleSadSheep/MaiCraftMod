// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.utility;

import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** Second phase: one explicitly selected city outlet to one already constructed machine input. */
public final class UtilityConnectionTaskRecord extends TaskRecord {
    public record Request(BlockPos sourceAnchor, BlockPos target, Direction targetFace,
            String targetBlockId, String medium, double minRpm, double minCapacity, String resource) {
        public Request {
            sourceAnchor = Objects.requireNonNull(sourceAnchor).immutable();
            target = Objects.requireNonNull(target).immutable();
            targetFace = Objects.requireNonNull(targetFace);
            targetBlockId = Objects.requireNonNull(targetBlockId);
            medium = Objects.requireNonNull(medium);
            resource = resource == null ? "" : resource;
            if (sourceAnchor.equals(target)) throw new IllegalArgumentException("utility_distinct_endpoints_required");
            if (!Double.isFinite(minRpm) || minRpm < 0 || minRpm > 256)
                throw new IllegalArgumentException("utility_invalid_minimum_rpm");
            if (minCapacity != 0) throw new IllegalArgumentException("utility_throughput_requirement_unsupported");
        }
    }
    static { TaskFactory.register(UtilityConnectionTaskRecord.class, UtilityConnectionTask::new); }
    public final String dimension, sourceLabel, inputId;
    public final Request request;
    public final MaterialPolicy materialPolicy;
    public final List<String> protectedLabels;

    public UtilityConnectionTaskRecord(String callId, long deadline, String dimension, String sourceLabel,
            String inputId, Request request, MaterialPolicy materialPolicy, List<String> protectedLabels) {
        super("connect_external_input", callId, deadline);
        this.dimension = Objects.requireNonNull(dimension);
        this.sourceLabel = Objects.requireNonNull(sourceLabel);
        this.inputId = Objects.requireNonNull(inputId);
        this.request = Objects.requireNonNull(request);
        this.materialPolicy = materialPolicy == null ? MaterialPolicy.ORDINARY : materialPolicy;
        this.protectedLabels = protectedLabels == null ? List.of() : List.copyOf(protectedLabels);
    }
    @Override public String describe() { return "connect " + sourceLabel + " to utility input " + inputId; }
}
