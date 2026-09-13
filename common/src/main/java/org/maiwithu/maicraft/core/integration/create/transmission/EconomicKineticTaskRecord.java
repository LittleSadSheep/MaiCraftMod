// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** A specified source remains fixed; omission requests bounded nearest suitable-source discovery. */
public final class EconomicKineticTaskRecord extends TaskRecord {
    static { TaskFactory.register(EconomicKineticTaskRecord.class,EconomicKineticTask::new); }
    public final String dimension,sourceLabel,targetLabel,targetBlockId;
    public final BlockPos source,target;
    public final Direction sourceFace,targetFace;
    public final double minimumRpm;
    public final int sourceRadius;
    public final boolean serverProofRequired;
    public final MaterialPolicy materialPolicy;
    public final List<String> protectedLabels;
    public final List<org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source> allowedSources;
    public final boolean allowHarm;
    public EconomicKineticTaskRecord(String callId,long deadline,String dimension,String sourceLabel,BlockPos source,Direction sourceFace,
            String targetLabel,BlockPos target,Direction targetFace,String targetBlockId,double minimumRpm,int sourceRadius,
            boolean serverProofRequired,MaterialPolicy materialPolicy,List<String> protectedLabels) {
        this(callId,deadline,dimension,sourceLabel,source,sourceFace,targetLabel,target,targetFace,targetBlockId,
                minimumRpm,sourceRadius,serverProofRequired,materialPolicy,protectedLabels,List.of(),false);
    }
    public EconomicKineticTaskRecord(String callId,long deadline,String dimension,String sourceLabel,BlockPos source,Direction sourceFace,
            String targetLabel,BlockPos target,Direction targetFace,String targetBlockId,double minimumRpm,int sourceRadius,
            boolean serverProofRequired,MaterialPolicy materialPolicy,List<String> protectedLabels,
            List<org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source> allowedSources,boolean allowHarm) {
        super("connect_economic_kinetics",callId,deadline);
        this.dimension=java.util.Objects.requireNonNull(dimension);this.sourceLabel=sourceLabel==null?"nearest suitable source":sourceLabel;
        this.source=source==null?null:source.immutable();this.sourceFace=sourceFace;
        this.targetLabel=java.util.Objects.requireNonNull(targetLabel);this.target=target.immutable();this.targetFace=targetFace;
        this.targetBlockId=targetBlockId;this.minimumRpm=minimumRpm;this.sourceRadius=sourceRadius;this.serverProofRequired=serverProofRequired;
        if(!Double.isFinite(minimumRpm)||minimumRpm<0||minimumRpm>256||sourceRadius<8||sourceRadius>128)
            throw new IllegalArgumentException("kinetic_request_bounds");
        this.materialPolicy=materialPolicy==null?MaterialPolicy.ORDINARY:materialPolicy;
        this.protectedLabels=protectedLabels==null?List.of():List.copyOf(protectedLabels);
        this.allowedSources=allowedSources==null?List.of():List.copyOf(allowedSources);this.allowHarm=allowHarm;
    }
    @Override public String describe(){return "比较供能成本并连接 · "+targetLabel;}
}
