// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.List;
import java.util.UUID;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.task.TaskRecord;

/** Typed record captured by semantic intent execution and run by the single client scheduler. */
public final class CreateMechanicalPowerTaskRecord extends TaskRecord {
    public final CreateMechanicalPower.Request request;
    public final UUID continuationToken;
    public final SemanticMaterialSupplyCoordinator.MaterialPolicy materialPolicy;
    public final List<SemanticAcquireTaskRecord.Source> allowedSources;
    public final boolean allowHarm;
    public final List<String> protectedLabels;

    CreateMechanicalPowerTaskRecord(
            String callId,
            long deadlineGameTime,
            CreateMechanicalPower.Request request,
            UUID continuationToken,
            SemanticMaterialSupplyCoordinator.MaterialPolicy materialPolicy,
            List<SemanticAcquireTaskRecord.Source> allowedSources,
            boolean allowHarm,
            List<String> protectedLabels) {
        super("connect_mechanical_power", callId, deadlineGameTime);
        this.request = request;
        this.continuationToken = continuationToken;
        this.materialPolicy = materialPolicy == null
                ? SemanticMaterialSupplyCoordinator.MaterialPolicy.ORDINARY : materialPolicy;
        this.allowedSources = allowedSources == null ? List.of() : List.copyOf(allowedSources);
        this.allowHarm = allowHarm;
        this.protectedLabels = protectedLabels == null ? List.of() : protectedLabels.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip).distinct().toList();
    }

    @Override
    public String describe() {
        return "connect mechanical power from " + request.source().name()
                + " to " + request.destination().name();
    }
}
