package org.maiwithu.maicraft.core.task.craft;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** 一次具体配方的合成单：要额外产出多少、要取几批结果、每批应有几件，以及是否只能原地操作。 */
public final class CraftTaskRecord extends TaskRecord {
    static { TaskFactory.register(CraftTaskRecord.class, CraftCompanionTask::new); }
    public final ResourceLocation recipeId;
    /** 本次要新增的产物数量，不是最终背包总数；配方每批产量可能让实际新增略多。 */
    public final int count;
    /** 允许拿取结果的准确批数，零表示旧调用方未记录，由执行时重新推导。 */
    public final int plannedBatches;
    /** 每批结果应有几件，执行时会核对配方是否仍一致，防止刷新配方后按旧数量操作。 */
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
