// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** Links two already built conveyor wheels through ordinary held-chain interactions. */
public final class ChainConveyorLinkTaskRecord extends TaskRecord {
    static { TaskFactory.register(ChainConveyorLinkTaskRecord.class, ChainConveyorLinkTask::new); }
    public final String dimension;
    public final BlockPos first, second;
    public final MaterialPolicy materialPolicy;
    public final List<String> protectedLabels;
    public final List<Source> allowedSources;
    public final boolean allowHarm;
    public ChainConveyorLinkTaskRecord(String callId, long deadline, String dimension, BlockPos first, BlockPos second,
            MaterialPolicy materialPolicy, List<String> protectedLabels) {
        this(callId, deadline, dimension, first, second, materialPolicy, List.of(), false, protectedLabels);
    }
    public ChainConveyorLinkTaskRecord(String callId, long deadline, String dimension, BlockPos first, BlockPos second,
            MaterialPolicy materialPolicy, List<Source> allowedSources, boolean allowHarm, List<String> protectedLabels) {
        super("connect_chain_conveyor", callId, deadline);
        this.dimension = Objects.requireNonNull(dimension); this.first = Objects.requireNonNull(first).immutable(); this.second = Objects.requireNonNull(second).immutable();
        if (first.equals(second)) throw new IllegalArgumentException("chain_conveyor_distinct_endpoints_required");
        this.materialPolicy = materialPolicy == null ? MaterialPolicy.ORDINARY : materialPolicy;
        this.protectedLabels = protectedLabels == null ? List.of() : List.copyOf(protectedLabels);
        this.allowedSources = allowedSources == null ? List.of() : List.copyOf(allowedSources); this.allowHarm = allowHarm;
    }
    @Override public String describe() { return "connect the two constructed chain conveyors with carried chains"; }
}
