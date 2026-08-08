package org.maiwithu.maicraft.core.task.craft;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** One recipe-book craft through a real client menu. */
public final class CraftTaskRecord extends TaskRecord {
    static { TaskFactory.register(CraftTaskRecord.class, CraftCompanionTask::new); }
    public final ResourceLocation recipeId;
    public final int count;
    public final BlockPos station;

    public CraftTaskRecord(String callId, long deadline, ResourceLocation recipeId,
                           int count, BlockPos station) {
        super("craft", callId, deadline);
        this.recipeId = recipeId;
        this.count = Math.max(1, count);
        this.station = station == null ? null : station.immutable();
    }
    @Override public String describe() { return "craft " + count + " via " + recipeId; }
}
