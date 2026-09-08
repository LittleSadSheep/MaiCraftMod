package org.maiwithu.maicraft.core.task.craft;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** One recipe-book craft through a real client menu. */
public final class CraftTaskRecord extends TaskRecord {
    static { TaskFactory.register(CraftTaskRecord.class, CraftCompanionTask::new); }
    public final ResourceLocation recipeId;
    /** Additional output promised by the planner, before unavoidable recipe-batch rounding. */
    public final int count;
    /** Exact number of result-slot takes authorized for this task; zero means a legacy record. */
    public final int plannedBatches;
    /** Exact recipe result count expected from each authorized take; zero means a legacy record. */
    public final int outputPerBatch;
    public final BlockPos station;
    public boolean inPlace;
    public CraftTaskRecord inPlace() { inPlace = true; return this; }

    /** Compatibility constructor for callers created before exact batch boundaries were recorded. */
    public CraftTaskRecord(String callId, long deadline, ResourceLocation recipeId,
                           int count, BlockPos station) {
        this(callId, deadline, recipeId, count, 0, 0, station);
    }

    public CraftTaskRecord(String callId, long deadline, ResourceLocation recipeId,
                           int count, int plannedBatches, int outputPerBatch, BlockPos station) {
        super("craft", callId, deadline);
        this.recipeId = recipeId;
        this.count = Math.max(1, count);
        this.plannedBatches = Math.max(0, plannedBatches);
        this.outputPerBatch = Math.max(0, outputPerBatch);
        this.station = station == null ? null : station.immutable();
    }
    @Override public String describe() {
        String boundary = plannedBatches > 0 && outputPerBatch > 0
                ? " (" + plannedBatches + "x" + outputPerBatch + ")" : "";
        return "craft " + count + " via " + recipeId + boundary;
    }
}
