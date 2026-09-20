// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.core.task.base.NativeSubmissionTaskRecord;
import org.maiwithu.maicraft.task.TaskFactory;

/** 固定配方、加工位置和有限批数；首次投料前沿用总任务的持久消费屏障，中断后不会再投入一整批。 */
public final class WorldTransformTaskRecord extends NativeSubmissionTaskRecord {
    public final ResourceLocation recipeId;
    public final BlockPos receiver;
    public final int batches;
    static { TaskFactory.register(WorldTransformTaskRecord.class, WorldTransformTask::new); }
    public WorldTransformTaskRecord(String callId, long deadline, ResourceLocation recipeId, BlockPos receiver, int batches) {
        super("run_world_process", callId, deadline, "world-process");
        if (recipeId == null || receiver == null || batches < 1 || batches > 64)
            throw new IllegalArgumentException("invalid_world_process_request");
        this.recipeId = recipeId; this.receiver = receiver.immutable(); this.batches = batches;
    }
    @Override public String describe() { return "在现有加工区执行 " + batches + " 批 " + recipeId; }
}
